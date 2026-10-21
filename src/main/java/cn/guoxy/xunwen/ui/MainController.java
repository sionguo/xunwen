package cn.guoxy.xunwen.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;

/** 主导航:左侧五个页签按钮,右侧 StackPane 按需懒加载子视图并缓存。 */
public class MainController {
  private enum Page {
    REPOS,
    TREE,
    PENDING,
    SEARCH,
    SETTINGS
  }

  private final AppState state;
  private final Map<Page, Node> cache = new EnumMap<>(Page.class);

  @FXML private Button reposBtn;
  @FXML private Button treeBtn;
  @FXML private Button pendingBtn;
  @FXML private Button searchBtn;
  @FXML private Button settingsBtn;
  @FXML private StackPane content;

  public MainController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    reposBtn.setOnAction(e -> show(Page.REPOS, "repos-view.fxml"));
    treeBtn.setOnAction(e -> show(Page.TREE, "tree-view.fxml"));
    pendingBtn.setOnAction(e -> show(Page.PENDING, "pending-view.fxml"));
    searchBtn.setOnAction(e -> show(Page.SEARCH, "search-view.fxml"));
    settingsBtn.setOnAction(e -> show(Page.SETTINGS, "settings-view.fxml"));
    show(Page.REPOS, "repos-view.fxml");
  }

  private void show(Page page, String fxml) {
    content.getChildren().setAll(cache.computeIfAbsent(page, p -> load(fxml)));
  }

  private Node load(String fxml) {
    try {
      FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
      loader.setControllerFactory(type -> newController(fxml));
      return loader.load();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Object newController(String fxml) {
    return switch (fxml) {
      case "repos-view.fxml" -> new ReposController(state);
      case "tree-view.fxml" -> new TreeController(state);
      case "pending-view.fxml" -> new PendingController(state);
      case "search-view.fxml" -> new SearchController(state);
      case "settings-view.fxml" -> new SettingsController(state);
      default -> throw new IllegalArgumentException(fxml);
    };
  }
}
