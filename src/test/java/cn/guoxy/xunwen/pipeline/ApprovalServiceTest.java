package cn.guoxy.xunwen.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.Doc;
import cn.guoxy.xunwen.index.FileStatus;
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
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      idx.upsert(
          new Doc(
              "待分类/文档.txt",
              "文档.txt",
              "txt",
              "h",
              1L,
              2L,
              FileStatus.PENDING,
              "",
              "内容",
              new float[] {1, 0, 0, 0}));
    }
    new ApprovalService(new RepoSession(root, CFG, HIGH_LLM, EX)).approve(pending, "工作文档");

    assertTrue(Files.exists(root.resolve("工作文档/文档.txt")));
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      var doc = idx.findByPath("工作文档/文档.txt").orElseThrow();
      assertEquals(FileStatus.INDEXED, doc.status());
      assertEquals("工作文档", doc.categoryPath());
      assertTrue(idx.findByPath("待分类/文档.txt").isEmpty());
    }
  }

  @Test
  void approvePreservesSemanticVector(@TempDir Path root) throws Exception {
    // 人工归位不得把真实向量写成中性向量：归位后 KNN 邻居查询应仍能以原向量命中它
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path pending = Files.createDirectories(RepoPaths.pendingDir(root)).resolve("文档.txt");
    Files.writeString(pending, "内容");
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      idx.upsert(
          new Doc(
              "待分类/文档.txt",
              "文档.txt",
              "txt",
              "h",
              1L,
              2L,
              FileStatus.PENDING,
              "",
              "内容",
              new float[] {0, 1, 0, 0}));
    }
    new ApprovalService(new RepoSession(root, CFG, HIGH_LLM, EX)).approve(pending, "工作文档");

    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      // 中性向量 {1,0,0,0} 与 {0,1,0,0} 的 cos=0：写丢即过不了相似度门槛
      var neighbors = idx.knnNeighbors(new float[] {0, 1, 0, 0}, 1);
      assertFalse(neighbors.isEmpty());
      assertEquals("工作文档", neighbors.getFirst().categoryPath());
      assertTrue(neighbors.getFirst().similarity() > 0.5f);
    }
  }

  @Test
  void approveProposalAddsCategoryAndReclassifiesUnknowns(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("其他", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path unknown = Files.createDirectories(RepoPaths.pendingDir(root)).resolve("发票.txt");
    Files.writeString(unknown, "内容");
    // 先以 UNKNOWN 入索引（模拟已重排的低置信文件）
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      idx.upsert(
          new Doc(
              "待分类/发票.txt",
              "发票.txt",
              "txt",
              "h",
              1L,
              2L,
              FileStatus.UNKNOWN,
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
    new ApprovalService(new RepoSession(root, CFG, llm, EX))
        .approveProposal(tree, "发票凭证", "各类票据", true, null);

    var updated = CategoryTree.load(RepoPaths.categoriesYaml(root));
    assertTrue(updated.find("发票凭证").isPresent());
    assertTrue(Files.exists(root.resolve("发票凭证/发票.txt"))); // 重分类后移出待分类
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      assertEquals(FileStatus.INDEXED, idx.findByPath("发票凭证/发票.txt").orElseThrow().status());
    }
  }

  @Test
  void removeCategoryMovesFilesBackToPending(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Files.createDirectories(root.resolve("工作文档"));
    Path f = root.resolve("工作文档/a.txt");
    Files.writeString(f, "内容");
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      idx.upsert(
          new Doc(
              "工作文档/a.txt",
              "a.txt",
              "txt",
              "h",
              1L,
              2L,
              FileStatus.INDEXED,
              "工作文档",
              "内容",
              new float[] {1, 0, 0, 0}));
    }
    LlmClient unknown = msgs -> "{\"category\":\"\",\"candidates\":[],\"self\":0.1}";

    int n =
        new ApprovalService(new RepoSession(root, CFG, unknown, EX))
            .removeCategory(CategoryTree.load(RepoPaths.categoriesYaml(root)), "工作文档");

    assertEquals(1, n);
    assertFalse(Files.exists(root.resolve("工作文档/a.txt")));
    assertTrue(Files.exists(RepoPaths.pendingDir(root).resolve("a.txt")));
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      assertEquals(FileStatus.UNKNOWN, idx.findByPath("待分类/a.txt").orElseThrow().status());
    }
    assertTrue(CategoryTree.load(RepoPaths.categoriesYaml(root)).find("工作文档").isEmpty());
  }

  @Test
  void removeSecondLevelCategoryKeepsTopLevel(@TempDir Path root) throws Exception {
    // 回归：删除「一级/二级」曾误删整个一级子树
    new CategoryTree(
            List.of(
                new Category(
                    "工作文档",
                    "",
                    List.of(new Category("报告", "", List.of()), new Category("纪要", "", List.of())))))
        .save(RepoPaths.categoriesYaml(root));
    Path dir = Files.createDirectories(root.resolve("工作文档/报告"));
    Path f = dir.resolve("a.txt");
    Files.writeString(f, "内容");
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      idx.upsert(
          new Doc(
              "工作文档/报告/a.txt",
              "a.txt",
              "txt",
              "h",
              1L,
              2L,
              FileStatus.INDEXED,
              "工作文档/报告",
              "内容",
              new float[] {1, 0, 0, 0}));
    }
    LlmClient unknown = msgs -> "{\"category\":\"\",\"candidates\":[],\"self\":0.1}";

    int n =
        new ApprovalService(new RepoSession(root, CFG, unknown, EX))
            .removeCategory(CategoryTree.load(RepoPaths.categoriesYaml(root)), "工作文档/报告");

    assertEquals(1, n); // 只有「报告」下的文件被移回待分类
    var updated = CategoryTree.load(RepoPaths.categoriesYaml(root));
    assertTrue(updated.find("工作文档").isPresent()); // 一级保留
    assertTrue(updated.find("工作文档/纪要").isPresent()); // 兄弟二级保留
    assertTrue(updated.find("工作文档/报告").isEmpty()); // 目标二级已删
    assertTrue(Files.exists(RepoPaths.pendingDir(root).resolve("a.txt")));
    assertFalse(Files.exists(root.resolve("工作文档/报告")));
  }

  @Test
  void approveBatchMovesAllFilesInSingleSession(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path pending = Files.createDirectories(RepoPaths.pendingDir(root));
    Path a = pending.resolve("甲.txt");
    Path b = pending.resolve("乙.txt");
    Files.writeString(a, "内容甲");
    Files.writeString(b, "内容乙");
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      idx.upsert(
          new Doc(
              "待分类/甲.txt",
              "甲.txt",
              "txt",
              "h",
              1L,
              2L,
              FileStatus.PENDING,
              "",
              "内容甲",
              new float[] {1, 0, 0, 0}));
      idx.upsert(
          new Doc(
              "待分类/乙.txt",
              "乙.txt",
              "txt",
              "h",
              1L,
              2L,
              FileStatus.PENDING,
              "",
              "内容乙",
              new float[] {1, 0, 0, 0}));
    }

    int n =
        new ApprovalService(new RepoSession(root, CFG, HIGH_LLM, EX))
            .approveBatch(
                List.of(
                    new ApprovalService.PendingApproval(a, "工作文档"),
                    new ApprovalService.PendingApproval(b, "工作文档")));

    assertEquals(2, n);
    assertTrue(Files.exists(root.resolve("工作文档/甲.txt")));
    assertTrue(Files.exists(root.resolve("工作文档/乙.txt")));
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      assertEquals(FileStatus.INDEXED, idx.findByPath("工作文档/甲.txt").orElseThrow().status());
      assertEquals("工作文档", idx.findByPath("工作文档/甲.txt").orElseThrow().candidatePath());
      assertTrue(idx.findByPath("待分类/乙.txt").isEmpty());
    }
  }
}
