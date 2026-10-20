package cn.guoxy.xunwen.repo;

import java.nio.file.Path;

public final class RepoPaths {
  private RepoPaths() {}

  public static Path xunwenDir(Path repoRoot) {
    return repoRoot.resolve(".xunwen");
  }

  public static Path indexDir(Path repoRoot) {
    return xunwenDir(repoRoot).resolve("index");
  }

  public static Path categoriesYaml(Path repoRoot) {
    return xunwenDir(repoRoot).resolve("categories.yaml");
  }

  public static Path operationsJsonl(Path repoRoot) {
    return xunwenDir(repoRoot).resolve("operations.jsonl");
  }

  public static Path pendingDir(Path repoRoot) {
    return repoRoot.resolve(CategoryTree.PENDING_DIR_NAME);
  }
}
