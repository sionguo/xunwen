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

  /** 未生效的类别树提案（持久化：应用重启后可恢复继续编辑）。 */
  public static Path proposalYaml(Path repoRoot) {
    return xunwenDir(repoRoot).resolve("proposal.yaml");
  }

  public static Path pendingDir(Path repoRoot) {
    return repoRoot.resolve(CategoryTree.PENDING_DIR_NAME);
  }

  /**
   * 遍历仓库时是否跳过该文件：相对路径任意一段以「.」或「~$」开头（隐藏文件/目录、Office 锁文件）。 按路径段判断而非仅文件名，否则 .index/app.db
   * 这类隐藏目录内的缓存文件会漏过过滤。
   */
  public static boolean isSkippable(Path repoRoot, Path file) {
    Path rel = repoRoot.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
    for (Path part : rel) {
      String n = part.toString();
      if (n.startsWith(".") || n.startsWith("~$")) {
        return true;
      }
    }
    return false;
  }
}
