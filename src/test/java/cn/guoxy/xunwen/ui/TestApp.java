package cn.guoxy.xunwen.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** TestFX 专用空壳 Application：由 ViewTestBase 换入被测视图，不加载用户真实配置。 */
public class TestApp extends Application {
  static Stage stage;

  @Override
  public void start(Stage stage) {
    TestApp.stage = stage;
    stage.setScene(new Scene(new javafx.scene.layout.StackPane(), 1000, 700));
    stage.show();
  }
}
