package cn.guoxy.xunwen.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.move.OperationLog;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FR-4 批次回滚：整批逆序还原、索引同步撤销、重名冲突处理、单条撤销、批次列表。 */
class RollbackServiceTest {

  private static final GlobalConfig CFG =
      new GlobalConfig("http://localhost:9/v1", "", "m", "e", 4, 0.85, 0.5, 2);

  private static final ContentExtractor EX =
      file -> new ContentExtractor.Extracted("足够长的测试文本内容用于提取分类流程", true);

  private static LlmClient fakeLlm() {
    return _ ->
        """
        {"category":"工作文档","candidates":[
          {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
        """;
  }

  private static void prepareTree(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
  }

  @Test
  void rollbackBatchRestoresOriginalStructureAndIndex(@TempDir Path root) throws Exception {
    prepareTree(root);
    Path nested = Files.createDirectories(root.resolve("手动目录"));
    Files.writeString(root.resolve("报告.txt"), "内容");
    Files.writeString(nested.resolve("旧文件.txt"), "旧内容");

    var stats = new ReshuffleTask(root, CFG, fakeLlm(), EX).run(ReshuffleTask.Progress.noop());
    assertEquals(2, stats.indexed());
    assertTrue(Files.exists(root.resolve("工作文档/报告.txt")));

    int restored = new RollbackService(root).rollbackBatch(stats.batchId());

    assertEquals(2, restored);
    assertTrue(Files.exists(root.resolve("报告.txt"))); // 回到原位置
    assertTrue(Files.exists(root.resolve("手动目录/旧文件.txt")));
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      assertTrue(idx.findByPath("工作文档/报告.txt").isEmpty()); // 索引条目同步撤销
      assertTrue(idx.countByStatus().isEmpty());
    }
    // 回滚留痕但不再列为可回滚批次（kind=ROLLBACK 被 batches 排除）
    var batches = new RollbackService(root).batches();
    assertEquals(1, batches.size());
    assertEquals(stats.batchId(), batches.getFirst().batchId());
    assertEquals("重排", batches.getFirst().kind());
  }

  @Test
  void rollbackHandlesSourceConflictByDedup(@TempDir Path root) throws Exception {
    prepareTree(root);
    Files.writeString(root.resolve("报告.txt"), "相同内容");
    var stats = new ReshuffleTask(root, CFG, fakeLlm(), EX).run(ReshuffleTask.Progress.noop());
    // 回滚前用户又在原位置放了同内容文件 → 回滚应去重而非报错/双份
    Files.createDirectories(root);
    Files.writeString(root.resolve("报告.txt"), "相同内容");

    int restored = new RollbackService(root).rollbackBatch(stats.batchId());

    assertEquals(1, restored);
    assertEquals("相同内容", Files.readString(root.resolve("报告.txt"))); // 原位置保留一份
    assertFalse(Files.exists(root.resolve("工作文档/报告.txt"))); // 副本被清理
  }

  @Test
  void undoSingleOperation(@TempDir Path root) throws Exception {
    prepareTree(root);
    Files.writeString(root.resolve("a.txt"), "内容a");
    Files.writeString(root.resolve("b.txt"), "内容b");
    var stats = new ReshuffleTask(root, CFG, fakeLlm(), EX).run(ReshuffleTask.Progress.noop());

    List<OperationLog.Op> ops =
        new OperationLog(RepoPaths.operationsJsonl(root)).batch(stats.batchId());
    assertEquals(2, ops.size());
    OperationLog.Op first = ops.get(0);
    OperationLog.Op second = ops.get(1);
    int restored = new RollbackService(root).undo(first);

    assertEquals(1, restored);
    assertTrue(Files.exists(Path.of(first.src()))); // 单条还原
    assertTrue(Files.exists(Path.of(second.dest()))); // 其余不受影响
  }

  @Test
  void rollbackUnknownBatchIsNoOp(@TempDir Path root) throws Exception {
    prepareTree(root);
    assertEquals(0, new RollbackService(root).rollbackBatch("no-such-batch"));
    assertTrue(new RollbackService(root).batches().isEmpty());
  }
}
