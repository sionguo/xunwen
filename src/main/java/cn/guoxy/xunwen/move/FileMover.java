package cn.guoxy.xunwen.move;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class FileMover {
  public enum MoveResult {
    MOVED,
    RENAMED,
    DUPLICATE_SKIPPED
  }

  public record MoveOutcome(MoveResult result, Path dest, String hash) {}

  private final Path repoRoot;

  public FileMover(Path repoRoot) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
  }

  public MoveOutcome move(Path src, Path destDir) throws IOException {
    Path absDestDir = destDir.toAbsolutePath().normalize();
    Files.createDirectories(absDestDir);
    String name = src.getFileName().toString();
    Path target = absDestDir.resolve(name);
    MoveResult result = MoveResult.MOVED;
    String hash = ""; // lazy：无同名冲突时不读内容，纯 rename（零拷贝且零读取）

    if (Files.exists(target)) {
      hash = sha256(src);
      if (sha256(target).equals(hash)) {
        return new MoveOutcome(MoveResult.DUPLICATE_SKIPPED, target, hash); // FR-5
      }
      String base = name;
      String ext = "";
      int dot = name.lastIndexOf('.');
      if (dot > 0) {
        base = name.substring(0, dot);
        ext = name.substring(dot);
      }
      int i = 2;
      do {
        target = absDestDir.resolve(base + " (" + i + ")" + ext);
        i++;
      } while (Files.exists(target) && !sha256(target).equals(hash));
      if (Files.exists(target)) {
        return new MoveOutcome(MoveResult.DUPLICATE_SKIPPED, target, hash);
      }
      result = MoveResult.RENAMED;
    }

    // ATOMIC_MOVE：同文件系统 = rename(2) 零拷贝；跨挂载点直接抛异常快速失败，
    // 绝不静默退化为 copy+delete（失败由 FileProcessor 捕获跳过）
    Files.move(src, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    cleanupEmptyDirs(src.getParent());
    return new MoveOutcome(result, target, hash);
  }

  private void cleanupEmptyDirs(Path dir) throws IOException {
    Path cur = dir.toAbsolutePath().normalize();
    while (!cur.equals(repoRoot)
        && cur.startsWith(repoRoot)
        && !cur.getFileName().toString().equals(".xunwen")
        && !cur.getFileName().toString().equals("待分类")) {
      if (!isDirEmpty(cur)) {
        return;
      }
      Files.delete(cur);
      cur = cur.getParent();
    }
  }

  private static boolean isDirEmpty(Path dir) throws IOException {
    try (var stream = Files.list(dir)) {
      return stream.findAny().isEmpty();
    }
  }

  static String sha256(Path file) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
          md.update(buf, 0, n);
        }
      }
      StringBuilder sb = new StringBuilder();
      for (byte b : md.digest()) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
