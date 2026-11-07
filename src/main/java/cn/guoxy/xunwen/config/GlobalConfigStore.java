package cn.guoxy.xunwen.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class GlobalConfigStore {
  private static final Logger LOG = LoggerFactory.getLogger(GlobalConfigStore.class);
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
    try {
      return JSON.readValue(file.toFile(), GlobalConfig.class);
    } catch (JacksonException e) {
      backupCorrupt(e);
      return GlobalConfig.defaults(); // 损坏自愈：回退默认保证应用起得动
    }
  }

  /** 坏文件改名留存（.corrupt 后缀）供用户找回，随后按默认值重建。 */
  private void backupCorrupt(JacksonException e) throws IOException {
    Path backup = file.resolveSibling(file.getFileName() + ".corrupt");
    Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
    LOG.warn("配置文件解析失败，已备份到 {} 并回退默认", backup, e);
  }

  public void save(GlobalConfig config) throws IOException {
    Files.createDirectories(file.getParent());
    JSON.writeValue(file.toFile(), config);
  }
}
