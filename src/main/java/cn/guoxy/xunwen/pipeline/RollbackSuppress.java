package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 回滚抑制清单：被回滚还原的文件原相对路径。自动入库（ingest/sync）跳过清单内文件， 避免以相同置信度把文件移回去而撤销回滚（FR-4/FR-12）；「重排仓库」时清空 =
 * 用户重新确认。
 */
public final class RollbackSuppress {
  private RollbackSuppress() {}

  private static Path file(Path repoRoot) {
    return RepoPaths.xunwenDir(repoRoot).resolve("rollback-suppressed.txt");
  }

  public static Set<String> load(Path repoRoot) throws IOException {
    Path f = file(repoRoot);
    if (!Files.exists(f)) {
      return Set.of();
    }
    Set<String> out = new HashSet<>();
    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
      String s = line.strip();
      if (!s.isEmpty()) {
        out.add(s);
      }
    }
    return out;
  }

  public static void addAll(Path repoRoot, Collection<String> relPaths) throws IOException {
    if (relPaths.isEmpty()) {
      return;
    }
    Set<String> merged = new HashSet<>(load(repoRoot)); // load 空清单返回不可变 Set.of()
    merged.addAll(relPaths);
    Files.createDirectories(file(repoRoot).getParent());
    Files.write(file(repoRoot), merged.stream().sorted().toList(), StandardCharsets.UTF_8);
  }

  public static void clear(Path repoRoot) throws IOException {
    Files.deleteIfExists(file(repoRoot));
  }
}
