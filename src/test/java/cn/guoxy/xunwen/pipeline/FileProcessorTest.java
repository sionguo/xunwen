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
      Path dest =
          new FileProcessor(root, CFG, llm, ex)
              .process(f, CategoryTree.load(RepoPaths.categoriesYaml(root)), "b1", index);
      assertNotNull(dest);
      assertEquals(root.resolve("工作文档/报告.txt"), dest);
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
      assertNull(
          new FileProcessor(root, CFG, llm, ex)
              .process(src, CategoryTree.load(RepoPaths.categoriesYaml(root)), "b1", index));
    }
  }
}
