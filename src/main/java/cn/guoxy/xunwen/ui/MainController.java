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

/** 主导航:左侧五个页签按钮(带选中态),右侧 StackPane 按需懒加载子视图并缓存;顶栏显示当前仓库与最近后台活动。 */
public class MainController {
  private enum Page {
    OVERVIEW,
    TREE,
    PENDING,
    SEARCH,
    SETTINGS
  }

  private final AppState state;

  private record Loaded(Node node, Object controller) {}

  private final Map<Page, Loaded> cache = new EnumMap<>(Page.class);
  private Page currentPage;

  @FXML private ToggleButton overviewBtn;
  @FXML private ToggleButton treeBtn;
  @FXML private ToggleButton pendingBtn;
  @FXML private ToggleButton searchBtn;
  @FXML private ToggleButton settingsBtn;
  @FXML private ToggleGroup navGroup;
  @FXML private Label currentRepoLabel;
  @FXML private Label activityLabel;
  @FXML private StackPane content;

  public MainController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    wireNav(overviewBtn, Page.OVERVIEW, "dashboard-view.fxml");
    wireNav(treeBtn, Page.TREE, "tree-view.fxml");
    wireNav(pendingBtn, Page.PENDING, "pending-view.fxml");
    wireNav(searchBtn, Page.SEARCH, "search-view.fxml");
    wireNav(settingsBtn, Page.SETTINGS, "settings-view.fxml");
    // 导航中枢：其他控制器通过 state.requestNavigation 请求跳页，控制器间零耦合
    state.setNavigationHandler(
        view -> {
          Page page = Page.valueOf(view);
          switch (page) {
            case OVERVIEW -> overviewBtn.setSelected(true);
            case TREE -> treeBtn.setSelected(true);
            case PENDING -> pendingBtn.setSelected(true);
            case SEARCH -> searchBtn.setSelected(true);
            case SETTINGS -> settingsBtn.setSelected(true);
          }
        });
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
    activityLabel.textProperty().bind(state.activityProperty());
    activityLabel.visibleProperty().bind(activityLabel.textProperty().isNotEmpty());
    // 点击活动摘要查看最近活动历史（新在前，最多 5 条）——重要任务完成信息不被后续活动顶丢
    activityLabel.setOnMouseClicked(
        e -> {
          var history = state.activityHistory();
          if (history.isEmpty()) {
            return;
          }
          javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
          for (String item : history) {
            javafx.scene.control.MenuItem mi = new javafx.scene.control.MenuItem(item);
            mi.setDisable(true); // 历史只读展示
            menu.getItems().add(mi);
          }
          menu.show(activityLabel, javafx.geometry.Side.BOTTOM, 0, 4);
        });
    overviewBtn.setSelected(true); // 默认进总览页
  }

  /** 选中即导航：监听 selectedProperty 而非 onAction——键盘焦点遍历也会改变 Toggle 选中， 若只在 onAction 里导航，页签选中态会与内容页脱节。 */
  private void wireNav(ToggleButton btn, Page page, String fxml) {
    btn.selectedProperty()
        .addListener(
            (obs, was, selected) -> {
              if (selected) {
                show(page, fxml);
              }
            });
    btn.setOnAction(
        e -> {
          if (!btn.isSelected()) {
            btn.setSelected(true); // 再点已选中页签不允许取消选中
          }
        });
  }

  private void show(Page page, String fxml) {
    if (currentPage == page) {
      return; // 同页重复选中（初始化/键盘遍历回环）不重建视图
    }
    currentPage = page;
    Loaded loaded = cache.computeIfAbsent(page, p -> load(fxml));
    content.getChildren().setAll(loaded.node());
    if (loaded.controller() instanceof PageAware aware) {
      aware.onPageShown();
    }
  }

  private Loaded load(String fxml) {
    try {
      FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
      // 视图 FXML 不声明 fx:controller，必须显式 setController；
      // setControllerFactory 只对声明了 fx:controller 的 FXML 生效
      Object controller = newController(fxml);
      loader.setController(controller);
      Node node = loader.load();
      return new Loaded(node, controller);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Object newController(String fxml) {
    return switch (fxml) {
      case "dashboard-view.fxml" -> new DashboardController(state);
      case "tree-view.fxml" -> new TreeController(state);
      case "pending-view.fxml" -> new PendingController(state);
      case "search-view.fxml" -> new SearchController(state);
      case "settings-view.fxml" -> new SettingsController(state);
      default -> throw new IllegalArgumentException(fxml);
    };
  }
}
