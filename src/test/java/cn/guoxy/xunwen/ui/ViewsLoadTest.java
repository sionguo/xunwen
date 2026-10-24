package cn.guoxy.xunwen.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 回归：五个子视图 FXML 都必须能连同控制器一起加载（事件处理引用与 fx:id 注入齐全）。 */
class ViewsLoadTest {

  @TempDir Path dir;

  @BeforeAll
  static void startFxToolkit() throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    try {
      Platform.startup(ready::countDown);
    } catch (IllegalStateException alreadyRunning) {
      ready.countDown();
    }
    assertTrue(ready.await(10, TimeUnit.SECONDS));
  }

  @Test
  void allViewsLoad() throws Exception {
    AppState state = new AppState(dir.resolve("config.json"), dir.resolve("repos.json"));
    record View(String fxml, Object controller) {}
    List<View> views =
        List.of(
            new View("dashboard-view.fxml", new DashboardController(state)),
            new View("tree-view.fxml", new TreeController(state)),
            new View("pending-view.fxml", new PendingController(state)),
            new View("search-view.fxml", new SearchController(state)),
            new View("settings-view.fxml", new SettingsController(state)));
    for (View v : views) {
      FXMLLoader loader = new FXMLLoader(getClass().getResource(v.fxml()));
      loader.setController(v.controller());
      loader.load(); // LoadException 即失败
    }
  }
}
