package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.index.HybridSearcher;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/** 混合搜索页:关键词 + 语义双路 RRF 融合,高亮渲染,双击用系统默认程序打开（FR-5）。 */
public class SearchController {
  private final AppState state;
  // 搜索任务在后台线程写入、FX 线程双击时读取，用并发容器保证可见性
  private final Map<String, Path> repoRootByName = new ConcurrentHashMap<>();

  @FXML private TextField query;
  @FXML private ComboBox<String> scope;
  @FXML private ListView<HybridSearcher.Hit> results;

  public SearchController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    scope.getItems().setAll("当前仓库", "全部仓库");
    scope.getSelectionModel().selectFirst();
    results.setCellFactory(
        lv ->
            new ListCell<>() {
              @Override
              protected void updateItem(HybridSearcher.Hit hit, boolean empty) {
                super.updateItem(hit, empty);
                if (empty || hit == null) {
                  setGraphic(null);
                  setText(null);
                  return;
                }
                setText(null);
                setGraphic(buildCell(hit));
              }
            });
    results.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) {
            open(results.getSelectionModel().getSelectedItem());
          }
        });
  }

  private TextFlow buildCell(HybridSearcher.Hit hit) {
    TextFlow flow = new TextFlow();
    Text title = new Text(hit.name());
    title.setFont(Font.font(null, FontWeight.BOLD, 13));
    flow.getChildren().add(title);
    flow.getChildren().add(new Text("   " + hit.categoryPath() + " · " + hit.repoName() + "\n"));
    for (Highlighter.Segment seg : Highlighter.parse(hit.highlight())) {
      Text t = new Text(seg.text());
      if (seg.emphasized()) {
        t.setFont(Font.font(null, FontWeight.BOLD, 12));
        t.setFill(Color.DARKRED);
      } else {
        t.setFont(Font.font(12));
        t.setFill(Color.DIMGRAY);
      }
      flow.getChildren().add(t);
    }
    return flow;
  }

  private void open(HybridSearcher.Hit hit) {
    if (hit == null) {
      return;
    }
    Path root = repoRootByName.get(hit.repoName());
    if (root == null) {
      return;
    }
    Path file = root.resolve(hit.path());
    Fx.runAsync(
        () -> {
          Desktop.getDesktop().open(file.toFile());
          return null;
        },
        v -> {},
        t -> Fx.error("无法打开文件", t));
  }

  @FXML
  private void onSearch() {
    String q = query.getText().strip();
    if (q.isEmpty()) {
      return;
    }
    boolean all = "全部仓库".equals(scope.getValue());
    List<Path> repos = all ? List.copyOf(state.reposProperty()) : onlyCurrent();
    if (repos.isEmpty()) {
      Fx.alert("没有可搜索的仓库");
      return;
    }
    Fx.runEngine(
        state,
        () -> doSearch(repos, q),
        hits -> results.getItems().setAll(FXCollections.observableList(hits)),
        t -> Fx.error("搜索失败", t));
  }

  private List<Path> onlyCurrent() {
    Path cur = state.currentRepo();
    return cur == null ? List.of() : List.of(cur);
  }

  private List<HybridSearcher.Hit> doSearch(List<Path> repos, String q) throws Exception {
    float[] vec;
    try {
      vec = state.llm().embed(q);
    } catch (RuntimeException e) {
      vec = new float[state.config().vectorDim()]; // embedding 不可用退关键词路
    }
    repoRootByName.clear();
    List<RepoIndex> opened = new ArrayList<>();
    try {
      var indices = new java.util.LinkedHashMap<String, RepoIndex>();
      for (Path repo : repos) {
        Path idxDir = RepoPaths.indexDir(repo);
        if (Files.exists(idxDir)) {
          RepoIndex idx = new RepoIndex(idxDir);
          indices.put(repo.getFileName().toString(), idx);
          opened.add(idx);
          repoRootByName.put(repo.getFileName().toString(), repo);
        }
      }
      return HybridSearcher.search(indices, q, vec, 50);
    } finally {
      for (RepoIndex idx : opened) {
        idx.close();
      }
    }
  }
}
