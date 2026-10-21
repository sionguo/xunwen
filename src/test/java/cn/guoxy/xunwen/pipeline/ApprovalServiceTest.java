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

class ApprovalServiceTest {

  private static final GlobalConfig CFG =
      new GlobalConfig("http://localhost:9/v1", "", "m", "e", 4, 0.85, 0.5, 2);

  private static final ContentExtractor EX =
      file -> new ContentExtractor.Extracted("足够长的测试文本内容用于提取分类流程", true);

  private static final LlmClient HIGH_LLM =
      msgs ->
          """
          {"category":"工作文档","candidates":[
            {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
          """;

  @Test
  void approveMovesPendingFileIntoCategory(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path pending = Files.createDirectories(RepoPaths.pendingDir(root)).resolve("文档.txt");
    Files.writeString(pending, "内容");
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      idx.upsert(
          new Doc(
              "待分类/文档.txt",
              "文档.txt",
              "txt",
              "h",
              1L,
              2L,
              "PENDING",
              "",
              "内容",
              new float[] {1, 0, 0, 0}));
    }
    new ApprovalService(root, CFG, HIGH_LLM, EX).approve(pending, "工作文档");

    assertTrue(Files.exists(root.resolve("工作文档/文档.txt")));
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      var doc = idx.findByPath("工作文档/文档.txt").orElseThrow();
      assertEquals("INDEXED", doc.status());
      assertEquals("工作文档", doc.categoryPath());
      assertTrue(idx.findByPath("待分类/文档.txt").isEmpty());
    }
  }

  @Test
  void approveProposalAddsCategoryAndReclassifiesUnknowns(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("其他", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path unknown = Files.createDirectories(RepoPaths.pendingDir(root)).resolve("发票.txt");
    Files.writeString(unknown, "内容");
    // 先以 UNKNOWN 入索引（模拟已重排的低置信文件）
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      idx.upsert(
          new Doc(
              "待分类/发票.txt",
              "发票.txt",
              "txt",
              "h",
              1L,
              2L,
              "UNKNOWN",
              "",
              "内容",
              new float[] {1, 0, 0, 0}));
    }
    // LLM 第二次分类时命中新类别「发票凭证」（confidence = 0.95×(0.5+0.5×0.85) ≈ 0.879 ≥ 0.85 → AUTO_MOVE）
    LlmClient llm =
        msgs ->
            """
            {"category":"发票凭证","candidates":[
              {"name":"发票凭证","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
            """;

    CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(root));
    new ApprovalService(root, CFG, llm, EX).approveProposal(tree, "发票凭证", "各类票据", true, null);

    var updated = CategoryTree.load(RepoPaths.categoriesYaml(root));
    assertTrue(updated.find("发票凭证").isPresent());
    assertTrue(Files.exists(root.resolve("发票凭证/发票.txt"))); // 重分类后移出待分类
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      assertEquals("INDEXED", idx.findByPath("发票凭证/发票.txt").orElseThrow().status());
    }
  }

  @Test
  void removeCategoryMovesFilesBackToPending(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Files.createDirectories(root.resolve("工作文档"));
    Path f = root.resolve("工作文档/a.txt");
    Files.writeString(f, "内容");
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      idx.upsert(
          new Doc(
              "工作文档/a.txt",
              "a.txt",
              "txt",
              "h",
              1L,
              2L,
              "INDEXED",
              "工作文档",
              "内容",
              new float[] {1, 0, 0, 0}));
    }
    LlmClient unknown = msgs -> "{\"category\":\"\",\"candidates\":[],\"self\":0.1}";

    int n =
        new ApprovalService(root, CFG, unknown, EX)
            .removeCategory(CategoryTree.load(RepoPaths.categoriesYaml(root)), "工作文档");

    assertEquals(1, n);
    assertFalse(Files.exists(root.resolve("工作文档/a.txt")));
    assertTrue(Files.exists(RepoPaths.pendingDir(root).resolve("a.txt")));
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      assertEquals("UNKNOWN", idx.findByPath("待分类/a.txt").orElseThrow().status());
    }
    assertTrue(CategoryTree.load(RepoPaths.categoriesYaml(root)).find("工作文档").isEmpty());
  }

  @Test
  void rollbackSuppressesResyncUntilReshuffleConfirms(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path f = root.resolve("报告.txt");
    Files.writeString(f, "内容");
    var records = new IngestTask(root, CFG, HIGH_LLM, EX).ingest(List.of(f));
    assertEquals(1, records.size());
    String batch = records.getFirst().batchId();
    assertTrue(Files.exists(root.resolve("工作文档/报告.txt")));

    var service = new ApprovalService(root, CFG, HIGH_LLM, EX);
    assertEquals(1, service.rollbackBatch(batch));
    assertTrue(Files.exists(root.resolve("报告.txt"))); // 文件还原到原位
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      assertTrue(idx.findByPath("工作文档/报告.txt").isEmpty()); // 落点索引条目已删
    }

    // 回滚抑制：sync 不再把文件按旧置信度移回类别（否则回滚被撤销）
    new IngestTask(root, CFG, HIGH_LLM, EX).sync();
    assertTrue(Files.exists(root.resolve("报告.txt")));

    // 重排即用户重新确认：清空抑制清单后 sync 重新归类
    RollbackSuppress.clear(root);
    new IngestTask(root, CFG, HIGH_LLM, EX).sync();
    assertTrue(Files.exists(root.resolve("工作文档/报告.txt")));
  }
}
