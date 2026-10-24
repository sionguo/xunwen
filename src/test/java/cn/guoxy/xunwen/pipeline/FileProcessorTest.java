package cn.guoxy.xunwen.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
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

class FileProcessorTest {

  private static final GlobalConfig CFG =
      new GlobalConfig("http://localhost:9/v1", "", "m", "e", 4, 0.85, 0.5, 2);

  /** LLM 恒选「工作文档」（高置信）；向量路按文件内容返回「其他」/「工作文档」方向向量。 */
  private static final class FakeLlm implements LlmClient, cn.guoxy.xunwen.llm.EmbeddingClient {
    @Override
    public String chat(List<Msg> messages) {
      return """
      {"category":"工作文档","candidates":[
        {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
      """;
    }

    @Override
    public float[] embed(String text) {
      if (text.contains("杂项")) {
        return new float[] {0f, 0f, 1f};
      }
      if (text.contains("其他") || text.contains("别的内容")) {
        return new float[] {0f, 1f, 0f};
      }
      return new float[] {1f, 0f, 0f};
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
      return texts.stream().map(this::embed).toList();
    }
  }

  @Test
  void vectorDisagreementSendsFileToPending(@TempDir Path root) throws Exception {
    new CategoryTree(
            List.of(
                new Category("工作文档", "", List.of()),
                new Category("其他", "", List.of(new Category("杂项", "", List.of())))))
        .save(RepoPaths.categoriesYaml(root));
    // 文件内容不含「别的内容」→ 文件向量 (1,0)；类别文本均含「工作文档/其他」不含「别的内容」
    // → 类别向量全 (1,0) → top1 与 LLM 一致。改为让文件内容触发 (0,1)：
    Path f = root.resolve("报告.txt");
    Files.writeString(f, "别的内容");
    ContentExtractor ex = file -> new ContentExtractor.Extracted("别的内容", true);

    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(root))) {
      FileProcessor.Result r =
          new FileProcessor(root, CFG, new FakeLlm(), ex)
              .process(f, CategoryTree.load(RepoPaths.categoriesYaml(root)), "b1", index);
      // LLM conf=0.936 但向量 top1=其他 → 钳 0.468 < 0.5 → 待分类（状态 UNKNOWN）
      assertEquals(root.resolve("待分类/报告.txt"), r.dest());
      assertEquals("UNKNOWN", r.status());
    }
  }

  @Test
  void vectorAgreementAutoMoves(@TempDir Path root) throws Exception {
    new CategoryTree(
            List.of(
                new Category("工作文档", "", List.of()),
                new Category("其他", "", List.of(new Category("杂项", "", List.of())))))
        .save(RepoPaths.categoriesYaml(root));
    Path f = root.resolve("报告.txt");
    Files.writeString(f, "工作文档相关内容");
    ContentExtractor ex = file -> new ContentExtractor.Extracted("工作文档相关内容", true);

    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(root))) {
      FileProcessor.Result r =
          new FileProcessor(root, CFG, new FakeLlm(), ex)
              .process(f, CategoryTree.load(RepoPaths.categoriesYaml(root)), "b1", index);
      // 向量 top1=工作文档 与 LLM 一致 → conf=0.936 ≥ 0.85 → 自动归位
      assertEquals(root.resolve("工作文档/报告.txt"), r.dest());
      assertEquals("INDEXED", r.status());
      // 分类元数据写入索引（待审页直接读，不再重新调 LLM）
      var stored = index.findByPath("工作文档/报告.txt").orElseThrow();
      assertEquals(0.936, stored.confidence(), 0.01);
    }
  }

  @Test
  void processReturnsMoveDestination(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path f = root.resolve("报告.txt");
    Files.writeString(f, "内容");
    LlmClient llm =
        msgs ->
            """
            {"category":"工作文档","candidates":[
              {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
            """;
    ContentExtractor ex = file -> new ContentExtractor.Extracted("足够长的测试文本内容用于提取流程", true);

    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(root))) {
      FileProcessor.Result r =
          new FileProcessor(root, CFG, llm, ex)
              .process(f, CategoryTree.load(RepoPaths.categoriesYaml(root)), "b1", index);
      assertNotNull(r.dest());
      assertEquals(root.resolve("工作文档/报告.txt"), r.dest());
    }
  }

  @Test
  void prepareCommitSplitMatchesProcess(@TempDir Path root) throws Exception {
    // 两阶段拆分与串行 process 语义等价（并发化正确性依据）
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path f = root.resolve("报告.txt");
    Files.writeString(f, "拆分等价内容");
    ContentExtractor ex = file -> new ContentExtractor.Extracted("足够长的拆分等价测试文本内容", true);
    LlmClient llm =
        msgs ->
            """
            {"category":"工作文档","candidates":[
              {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
            """;

    FileProcessor processor = new FileProcessor(root, CFG, llm, ex);
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(root))) {
      var tree = CategoryTree.load(RepoPaths.categoriesYaml(root));
      processor.warmVectors(tree); // 并发 prepare 前的预构建
      FileProcessor.Prepared p = processor.prepare(f, tree, index);
      assertNotNull(p);
      assertEquals(root.resolve("报告.txt"), p.src());

      FileProcessor.Result r = processor.commit(p, "b-split", index);
      assertEquals(root.resolve("工作文档/报告.txt"), r.dest());
      assertEquals("INDEXED", r.status());
      // 候选类别写入索引（待审预选数据源）
      assertEquals("工作文档", index.findByPath("工作文档/报告.txt").orElseThrow().candidatePath());
      // 已入库的新路径再次 prepare：去重命中返回 null（中断续跑）
      assertNull(processor.prepare(root.resolve("工作文档/报告.txt"), tree, index));
    }
  }

  @Test
  void duplicateSkipReturnsNull(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Files.createDirectories(root.resolve("工作文档"));
    Files.writeString(root.resolve("工作文档/报告.txt"), "相同");
    Path src = root.resolve("报告.txt"); // 与已入库文件同名同内容 → FR-5 判重
    Files.writeString(src, "相同");
    LlmClient llm =
        msgs ->
            """
            {"category":"工作文档","candidates":[{"name":"工作文档","score":0.95}],"self":0.95}
            """;
    ContentExtractor ex = file -> new ContentExtractor.Extracted("相同", true);

    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(root))) {
      FileProcessor.Result r =
          new FileProcessor(root, CFG, llm, ex)
              .process(src, CategoryTree.load(RepoPaths.categoriesYaml(root)), "b1", index);
      assertNull(r.dest());
      assertEquals("DUPLICATE", r.status());
    }
  }
}
