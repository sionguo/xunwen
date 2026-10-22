package cn.guoxy.xunwen.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;

/** 主导航:左侧五个页签按钮(带选中态),右侧 StackPane 按需懒加载子视图并缓存;顶栏显示当前仓库。 */
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

  @FXML private ToggleButton reposBtn;
  @FXML private ToggleButton treeBtn;
  @FXML private ToggleButton pendingBtn;
  @FXML private ToggleButton searchBtn;
  @FXML private ToggleButton settingsBtn;
  @FXML private ToggleGroup navGroup;
  @FXML private Label currentRepoLabel;
  @FXML private StackPane content;

  public MainController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    wireNav(reposBtn, Page.REPOS, "repos-view.fxml");
    wireNav(treeBtn, Page.TREE, "tree-view.fxml");
    wireNav(pendingBtn, Page.PENDING, "pending-view.fxml");
    wireNav(searchBtn, Page.SEARCH, "search-view.fxml");
    wireNav(settingsBtn, Page.SETTINGS, "settings-view.fxml");
    currentRepoLabel
        .textProperty()
        .bind(
            Bindings.createStringBinding(
                () -> {
                  var repo = state.currentRepo();
                  return repo == null ? "" : "· " + repo.getFileName();
                },
                state.currentRepoProperty()));
    currentRepoLabel.visibleProperty().bind(currentRepoLabel.textProperty().isNotEmpty());
    reposBtn.setSelected(true); // 默认进仓库页
    show(Page.REPOS, "repos-view.fxml");
  }

  private void wireNav(ToggleButton btn, Page page, String fxml) {
    btn.setOnAction(
        e -> {
          if (!btn.isSelected()) {
            btn.setSelected(true); // 再点已选中页签不允许取消选中
            return;
          }
          show(page, fxml);
        });
  }

  private void show(Page page, String fxml) {
    content.getChildren().setAll(cache.computeIfAbsent(page, p -> load(fxml)));
  }

  private Node load(String fxml) {
    try {
      FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
      // 视图 FXML 不声明 fx:controller，必须显式 setController；
      // setControllerFactory 只对声明了 fx:controller 的 FXML 生效
      loader.setController(newController(fxml));
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
