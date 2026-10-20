package cn.guoxy.xunwen.repo;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CategoryTreeTest {

  private CategoryTree sample() {
    return new CategoryTree(
        java.util.List.of(
            new Category(
                "工作文档",
                "与工作相关的文档",
                java.util.List.of(new Category("报告", "各类报告", java.util.List.of()))),
            new Category("其他", "未明确归类", java.util.List.of())));
  }

  @Test
  void flattenPathsReturnsTwoLevelsInDfsOrder() {
    var paths = sample().flattenPaths();
    assertEquals(java.util.List.of("工作文档", "工作文档/报告", "其他"), paths);
  }

  @Test
  void findResolvesSlashPath() {
    assertTrue(sample().find("工作文档/报告").isPresent());
    assertTrue(sample().find("不存在").isEmpty());
  }

  @Test
  void findRejectsDeeperThanTwoLevels() {
    assertTrue(sample().find("工作文档/报告/2024").isEmpty()); // 超两级不在体系内 → UNKNOWN 分流
    assertTrue(sample().find("工作文档/报告").isPresent()); // 两级仍正常解析
  }

  @Test
  void yamlRoundTrip(@TempDir Path dir) throws IOException {
    Path yaml = dir.resolve("categories.yaml");
    sample().save(yaml);
    assertTrue(Files.exists(yaml));
    CategoryTree loaded = CategoryTree.load(yaml);
    assertEquals(sample().flattenPaths(), loaded.flattenPaths());
  }

  @Test
  void repoPathsLayout(@TempDir Path root) {
    assertEquals(root.resolve(".xunwen"), RepoPaths.xunwenDir(root));
    assertEquals(root.resolve(".xunwen/index"), RepoPaths.indexDir(root));
    assertEquals(root.resolve(".xunwen/categories.yaml"), RepoPaths.categoriesYaml(root));
    assertEquals(root.resolve(".xunwen/operations.jsonl"), RepoPaths.operationsJsonl(root));
    assertEquals(root.resolve("待分类"), RepoPaths.pendingDir(root));
  }
}
