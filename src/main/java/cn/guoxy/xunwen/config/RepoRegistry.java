package cn.guoxy.xunwen.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public final class RepoRegistry {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path file;

  public RepoRegistry(Path file) {
    this.file = file;
  }

  public List<Path> list() throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<String> raw = JSON.readValue(file.toFile(), new TypeReference<>() {});
    return raw.stream().map(Path::of).toList();
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
