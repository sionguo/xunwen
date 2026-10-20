package cn.guoxy.xunwen.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

  @Test
  void globalConfigDefaults() {
    var c = GlobalConfig.defaults();
    assertEquals("http://localhost:11434/v1", c.baseUrl());
    assertEquals(0.85, c.autoThreshold());
    assertEquals(0.5, c.pendingThreshold());
    assertEquals(1024, c.vectorDim());
  }

  @Test
  void configStoreRoundTripAndMissingFile(@TempDir Path dir) throws IOException {
    var store = new GlobalConfigStore(dir.resolve("config.json"));
    assertEquals(GlobalConfig.defaults(), store.load()); // 文件缺失返回默认
    var custom = new GlobalConfig("http://x/v1", "sk-1", "m1", "e1", 768, 0.9, 0.4, 4);
    store.save(custom);
    assertEquals(custom, new GlobalConfigStore(dir.resolve("config.json")).load());
  }

  @Test
  void registryAddDedupAndRemove(@TempDir Path dir) throws IOException {
    var reg = new RepoRegistry(dir.resolve("repos.json"));
    reg.add(dir.resolve("repoA"));
    reg.add(dir.resolve("repoA")); // 重复
    reg.add(dir.resolve("repoB"));
    assertEquals(List.of(dir.resolve("repoA"), dir.resolve("repoB")), reg.list());
    reg.remove(dir.resolve("repoA"));
    assertEquals(List.of(dir.resolve("repoB")), reg.list());
  }
}
