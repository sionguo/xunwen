package cn.guoxy.xunwen.ui;

import java.io.IOException;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class XunwenApp extends Application {
  private AppState state;

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
    stage.setScene(new Scene(root, 1280, 800));
    stage.show();
  }
}
