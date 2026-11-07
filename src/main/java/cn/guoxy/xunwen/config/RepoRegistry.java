package cn.guoxy.xunwen.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public final class RepoRegistry {
  private static final Logger LOG = LoggerFactory.getLogger(RepoRegistry.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path file;

  public RepoRegistry(Path file) {
    this.file = file;
  }

  public List<Path> list() throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    try {
      List<String> raw = JSON.readValue(file.toFile(), new TypeReference<>() {});
      return raw.stream().map(Path::of).toList();
    } catch (JacksonException e) {
      backupCorrupt(e);
      return List.of(); // 损坏自愈：回退空列表保证应用起得动
    }
  }

  /** 坏文件改名留存（.corrupt 后缀）供用户找回。 */
  private void backupCorrupt(JacksonException e) throws IOException {
    Path backup = file.resolveSibling(file.getFileName() + ".corrupt");
    Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
    LOG.warn("仓库列表解析失败，已备份到 {} 并回退空", backup, e);
  }

  public void add(Path repo) throws IOException {
    Set<Path> all = new LinkedHashSet<>(list());
    all.add(repo.toAbsolutePath().normalize());
    write(all);
  }

  public void remove(Path repo) throws IOException {
    Set<Path> all = new LinkedHashSet<>(list());
    all.remove(repo.toAbsolutePath().normalize());
    write(all);
  }

  private void write(Set<Path> all) throws IOException {
    Files.createDirectories(file.getParent());
    JSON.writeValue(file.toFile(), all.stream().map(Path::toString).toList());
  }
}
