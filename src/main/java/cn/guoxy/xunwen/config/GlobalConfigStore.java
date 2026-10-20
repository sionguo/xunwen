package cn.guoxy.xunwen.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class GlobalConfigStore {
  private static final ObjectMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
  private final Path file;

  public GlobalConfigStore(Path file) {
    this.file = file;
  }

  public GlobalConfig load() throws IOException {
    if (!Files.exists(file)) {
      return GlobalConfig.defaults();
    }
    return JSON.readValue(file.toFile(), GlobalConfig.class);
  }

  public void save(GlobalConfig config) throws IOException {
    Files.createDirectories(file.getParent());
    JSON.writeValue(file.toFile(), config);
  }
}
