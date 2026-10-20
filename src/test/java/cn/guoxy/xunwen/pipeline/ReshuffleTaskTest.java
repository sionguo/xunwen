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

class ReshuffleTaskTest {

  private static final GlobalConfig CFG =
      new GlobalConfig("http://localhost:9/v1", "", "m", "e", 4, 0.85, 0.5, 2);

  /** fake LLM：分类一律高置信「工作文档」 */
  private static LlmClient fakeLlm() {
    return _ ->
        """
        {"category":"工作文档","candidates":[
          {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
        """;
  }

  private static ContentExtractor fakeExtractor() {
    return _ -> new ContentExtractor.Extracted("测试内容足够长的一些文字用于通过文本层判断逻辑", true);
  }

  @Test
  void reshufflesHighConfidenceIntoCategory(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("报告.txt"), "内容");
    Path nested = Files.createDirectories(root.resolve("手动目录")).resolve("a.pdf");
    Files.writeString(nested, "pdf 内容");

    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));

    var task = new ReshuffleTask(root, CFG, fakeLlm(), fakeExtractor());
    String batch = task.run(ReshuffleTask.Progress.noop());

    assertTrue(Files.exists(root.resolve("工作文档/报告.txt")));
    assertTrue(Files.exists(root.resolve("工作文档/a.pdf")));
    assertFalse(Files.exists(root.resolve("手动目录"))); // 空壳目录已清理
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      assertTrue(idx.findByPath("工作文档/报告.txt").isPresent());
    }
    var log = new OperationLog(RepoPaths.operationsJsonl(root));
    assertEquals(2, log.batch(batch).size());
    log.rollback(batch); // FR-4：批次回滚还原
    assertTrue(Files.exists(root.resolve("报告.txt")));
    assertTrue(Files.exists(nested));
  }

  @Test
  void lowConfidenceGoesToPendingDir(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("神秘文件.bin"), "短");
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    LlmClient unknown = _ -> "{\"category\":\"\",\"candidates\":[],\"self\":0.1}";
    new ReshuffleTask(root, CFG, unknown, fakeExtractor()).run(ReshuffleTask.Progress.noop());
    assertTrue(Files.exists(RepoPaths.pendingDir(root).resolve("神秘文件.bin")));
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      assertEquals("UNKNOWN", idx.findByPath("待分类/神秘文件.bin").orElseThrow().status());
    }
  }

  @Test
  void missingTreeFails(@TempDir Path root) {
    var task = new ReshuffleTask(root, CFG, fakeLlm(), fakeExtractor());
    assertThrows(IllegalStateException.class, () -> task.run(ReshuffleTask.Progress.noop()));
  }

  @Test
  void alreadyIndexedFileIsSkippedOnRerun(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("报告.txt"), "内容");
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    var task = new ReshuffleTask(root, CFG, fakeLlm(), fakeExtractor());
    task.run(ReshuffleTask.Progress.noop());
    // 中断续跑：第二次运行不重复移动（文件已在类别目录且已索引）
    assertDoesNotThrow(() -> task.run(ReshuffleTask.Progress.noop()));
    assertTrue(Files.exists(root.resolve("工作文档/报告.txt")));
  }
}
