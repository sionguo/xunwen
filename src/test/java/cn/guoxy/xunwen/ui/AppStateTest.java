package cn.guoxy.xunwen.ui;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppStateTest {

  @Test
  void loadFallsBackToDefaultsAndSavePersists(@TempDir Path dir) throws IOException {
    var state = new AppState(dir.resolve("config.json"), dir.resolve("repos.json"));
    assertEquals(GlobalConfig.defaults().baseUrl(), state.configProperty().get().baseUrl());
    var custom = new GlobalConfig("http://x/v1", "", "m", "e", 4, 0.9, 0.4, 2);
    state.saveConfig(custom);
    var reloaded = new AppState(dir.resolve("config.json"), dir.resolve("repos.json"));
    reloaded.load(); // 持久化验证：重新构造并显式加载
    assertEquals(custom.baseUrl(), reloaded.configProperty().get().baseUrl());
  }
}
