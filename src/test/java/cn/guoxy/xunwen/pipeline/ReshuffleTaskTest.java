package cn.guoxy.xunwen.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.Doc;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.LlmClient;
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
    BatchStats stats = task.run(BatchRunner.Progress.noop());

    assertTrue(Files.exists(root.resolve("工作文档/报告.txt")));
    assertTrue(Files.exists(root.resolve("工作文档/a.pdf")));
    assertFalse(Files.exists(root.resolve("手动目录"))); // 空壳目录已清理
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      assertTrue(idx.findByPath("工作文档/报告.txt").isPresent());
    }
    assertEquals(2, stats.indexed()); // 统计可见：两个文件全部自动归档
    assertEquals(0, stats.failed());
  }

  @Test
  void lowConfidenceGoesToPendingDir(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("神秘文件.bin"), "短");
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    LlmClient unknown = _ -> "{\"category\":\"\",\"candidates\":[],\"self\":0.1}";
    new ReshuffleTask(root, CFG, unknown, fakeExtractor()).run(BatchRunner.Progress.noop());
    assertTrue(Files.exists(RepoPaths.pendingDir(root).resolve("神秘文件.bin")));
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      assertEquals("UNKNOWN", idx.findByPath("待分类/神秘文件.bin").orElseThrow().status());
    }
  }

  @Test
  void missingTreeFails(@TempDir Path root) {
    var task = new ReshuffleTask(root, CFG, fakeLlm(), fakeExtractor());
    assertThrows(IllegalStateException.class, () -> task.run(BatchRunner.Progress.noop()));
  }

  @Test
  void llmTransportFailureLeavesFilesInPlace(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("报告.txt"), "内容");
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    LlmClient down =
        _ -> {
          throw new IllegalStateException("LLM chat failed");
        };
    var task = new ReshuffleTask(root, CFG, down, fakeExtractor());
    BatchStats stats = assertDoesNotThrow(() -> task.run(BatchRunner.Progress.noop())); // 跳过而非整批崩
    assertEquals(1, stats.failed()); // 失败计入统计（可见），不静默
    assertTrue(Files.exists(root.resolve("报告.txt"))); // 留在原处，不进待分类
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      assertTrue(idx.findByPath("待分类/报告.txt").isEmpty()); // 未被当作 UNKNOWN 入库
      assertTrue(idx.findByPath("工作文档/报告.txt").isEmpty()); // 未被索引
    }
  }

  @Test
  void alreadyIndexedFileIsSkippedOnRerun(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("报告.txt"), "内容");
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    var task = new ReshuffleTask(root, CFG, fakeLlm(), fakeExtractor());
    task.run(BatchRunner.Progress.noop());
    // 中断续跑：第二次运行不重复移动（文件已在类别目录且已索引）
    assertDoesNotThrow(() -> task.run(BatchRunner.Progress.noop()));
    assertTrue(Files.exists(root.resolve("工作文档/报告.txt")));
  }

  @Test
  void fullReshuffleRelocatesMisplacedIndexedFile(@TempDir Path root) throws Exception {
    // 全量重排：已入库但放错位置的文件被重新分类归位（增量模式会跳过它）
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path misplaced = root.resolve("错位.txt");
    Files.writeString(misplaced, "内容");
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      idx.upsert(
          new Doc(
              "错位.txt",
              "错位.txt",
              "txt",
              "h",
              Files.getLastModifiedTime(misplaced).toMillis(),
              Files.size(misplaced),
              "INDEXED",
              "工作文档",
              "内容",
              new float[] {1, 0, 0, 0}));
    }
    var task = new ReshuffleTask(root, CFG, fakeLlm(), fakeExtractor());

    BatchStats full = task.run(BatchRunner.Progress.noop(), ReshuffleTask.Mode.FULL);

    assertEquals(1, full.indexed(), "summary=" + full.summary() + " failed=" + full.failed());
    assertTrue(Files.exists(root.resolve("工作文档/错位.txt"))); // 重新归位
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      var all = idx.all();
      assertTrue(
          idx.findByPath("工作文档/错位.txt").isPresent(),
          "all=" + all.stream().map(d -> d.path() + ":" + d.status()).toList());
      assertTrue(idx.findByPath("错位.txt").isEmpty());
    }
    assertEquals(1, full.indexed());
    // 增量重排对已入库文件跳过
    BatchStats inc = task.run(BatchRunner.Progress.noop());
    assertEquals(1, inc.alreadyIndexed());
  }

  @Test
  void fullReshuffleRescuesPendingFiles(@TempDir Path root) throws Exception {
    // 全量重排包含待分类目录：卡在待分类的文件重新分类救援
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path pending = Files.createDirectories(RepoPaths.pendingDir(root)).resolve("卡住.txt");
    Files.writeString(pending, "内容");
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      idx.upsert(
          new Doc(
              "待分类/卡住.txt",
              "卡住.txt",
              "txt",
              "h",
              1L,
              2L,
              "UNKNOWN",
              "",
              "内容",
              new float[] {1, 0, 0, 0}));
    }
    var task = new ReshuffleTask(root, CFG, fakeLlm(), fakeExtractor());

    BatchStats stats = task.run(BatchRunner.Progress.noop(), ReshuffleTask.Mode.FULL);

    assertTrue(Files.exists(root.resolve("工作文档/卡住.txt"))); // 从待分类救出
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      assertEquals("INDEXED", idx.findByPath("工作文档/卡住.txt").orElseThrow().status());
    }
    assertEquals(1, stats.indexed());
    assertEquals(0, stats.unknown());
  }
}
