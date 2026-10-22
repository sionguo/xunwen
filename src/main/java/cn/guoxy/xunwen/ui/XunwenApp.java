package cn.guoxy.xunwen.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import java.io.IOException;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class XunwenApp extends Application {
  private AppState state;
  private WatchServiceBinder binder;

  @Override
  public void init() throws IOException {
    Path dir = Path.of(System.getProperty("user.home"), ".xunwen");
    state = new AppState(dir.resolve("config.json"), dir.resolve("repos.json"));
    state.load();
  }

  @Override
  public void start(Stage stage) throws IOException {
    FXMLLoader loader = new FXMLLoader(getClass().getResource("main-view.fxml"));
    loader.setController(new MainController(state));
    Parent root = loader.load();
    stage.setTitle("寻文");
    Scene scene = new Scene(root, 1280, 800);
    scene.getStylesheets().add(getClass().getResource("app.css").toExternalForm());
    stage.setScene(scene);
    followSystemColorScheme();
    stage.show();
    binder = new WatchServiceBinder(state); // 打开仓库即自动启动监视，关闭应用时停止
  }

  /** 跟随系统明暗切换 AtlantaFX Primer 主题；系统未声明偏好时用浅色。 */
  private void followSystemColorScheme() {
    var prefs = Platform.getPreferences();
    Runnable apply =
        () ->
            Application.setUserAgentStylesheet(
                prefs.getColorScheme() == ColorScheme.DARK
                    ? new PrimerDark().getUserAgentStylesheet()
                    : new PrimerLight().getUserAgentStylesheet());
    prefs.colorSchemeProperty().addListener((obs, ov, nv) -> apply.run());
    apply.run();
  }

  @Override
  public void stop() {
    if (binder != null) {
      binder.close();
    }
  }
}
