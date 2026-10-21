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

  @Test
  void embedBaseUrlFallsBackWhenAbsentAndRoundTripsSeparately(@TempDir Path dir)
      throws IOException {
    var store = new GlobalConfigStore(dir.resolve("config.json"));
    // 8 参旧构造：向量服务未单独配置 → 与对话同址
    var legacy = new GlobalConfig("http://chat.local/v1", "sk", "m", "e", 4, 0.85, 0.5, 2);
    assertEquals("http://chat.local/v1", legacy.embedBaseUrl());
    // 分离配置的往返
    var custom =
        new GlobalConfig(
            "http://chat.local/v1", "http://embed.local/v1", "sk", "m", "e", 4, 0.85, 0.5, 2);
    store.save(custom);
    assertEquals("http://embed.local/v1", store.load().embedBaseUrl());
    // 旧版 JSON（无 embedBaseUrl 键）加载后回落到 baseUrl
    java.nio.file.Files.writeString(
        dir.resolve("legacy.json"),
        """
        {"baseUrl":"http://old.local/v1","apiKey":"","chatModel":"m","embedModel":"e",
         "vectorDim":4,"autoThreshold":0.85,"pendingThreshold":0.5,"concurrency":2}
        """);
    var loaded = new GlobalConfigStore(dir.resolve("legacy.json")).load();
    assertEquals("http://old.local/v1", loaded.embedBaseUrl());
  }
}
