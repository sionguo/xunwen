package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.index.HybridSearcher;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.index.StoredDoc;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * 混合搜索页:关键词 + 语义双路 RRF 融合,高亮渲染,双击用系统默认程序打开（FR-5）。 左侧为 App Search 式分面：进页面即按当前范围装载分类/状态分面（计数来自索引全量），
 * 未搜索时点选分面直接浏览该类文件；搜索后分面收敛为命中集计数，点选在结果内过滤。
 */
public class SearchController implements PageAware {
  /** 浏览模式单仓库装载上限：分面计数仍是索引全量，仅列表截断。 */
  private static final int BROWSE_CAP = 200;

  private final AppState state;
  // 搜索任务在后台线程写入、FX 线程双击时读取，用并发容器保证可见性
  private final Map<String, Path> repoRootByName = new ConcurrentHashMap<>();

  // 当前未过滤结果与勾选中的分面值（FX 线程读写）
  private List<HybridSearcher.Hit> allHits = List.of();
  private final Set<String> activeCategories = new LinkedHashSet<>();
  private final Set<String> activeStatuses = new LinkedHashSet<>();
  private boolean queryMode; // false = 浏览模式（尚未搜索）
  private Map<String, Long> browseCatCounts = Map.of();
  private Map<String, Long> browseStCounts = Map.of();
  private String loadedBrowseSig; // 已装载的浏览范围签名，避免每次进页重扫

  @FXML private TextField query;
  @FXML private ComboBox<String> scope;
  @FXML private ListView<HybridSearcher.Hit> results;
  @FXML private VBox categoryFacets;
  @FXML private VBox statusFacets;
  @FXML private Hyperlink clearFilters;
  @FXML private Label resultSummary;
  @FXML private Label facetHint;

  public SearchController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    scope.getItems().setAll("当前仓库", "全部仓库");
    scope.getSelectionModel().selectFirst();
    var searchPlaceholder = new Label("输入关键词，回车或点「搜索」开始");
    searchPlaceholder.getStyleClass().add("placeholder-label");
    results.setPlaceholder(searchPlaceholder);
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
    clearFilters.setOnAction(
        e -> {
          activeCategories.clear();
          activeStatuses.clear();
          refreshFacetChecks();
          applyFilters();
        });
    scope.valueProperty().addListener((obs, o, v) -> onScopeChanged());
    state.currentRepoProperty().addListener((obs, o, r) -> onScopeChanged());
    state.dataChangedProperty().addListener((obs, o, v) -> onScopeChanged());
  }

  @Override
  public void onPageShown() {
    // 进页面即打开左侧分面（浏览模式）；范围未变的重复进入不重扫
    if (query.getText().isBlank()) {
      loadBrowseFacets(false);
    }
  }

  private void onScopeChanged() {
    if (!query.getText().isBlank()) {
      return; // 已有关键词：保留现有结果，下次搜索按新范围执行
    }
    loadBrowseFacets(true);
  }

  private TextFlow buildCell(HybridSearcher.Hit hit) {
    TextFlow flow = new TextFlow();
    Text title = new Text(hit.name());
    title.getStyleClass().add("hit-title");
    flow.getChildren().add(title);
    String where = hit.categoryPath().isBlank() ? "待分类" : hit.categoryPath();
    Text meta =
        new Text(
            "   " + where + " · " + StatusLabels.of(hit.status()) + " · " + hit.repoName() + "\n");
    meta.getStyleClass().add("hit-meta");
    flow.getChildren().add(meta);
    for (Highlighter.Segment seg : Highlighter.parse(hit.highlight())) {
      Text t = new Text(seg.text());
      t.getStyleClass().add(seg.emphasized() ? "hit-emphasized" : "hit-body");
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
    List<Path> repos = scopeRepos();
    if (repos.isEmpty()) {
      Fx.alert("没有可搜索的仓库");
      return;
    }
    Fx.runEngine(
        state,
        () -> doSearch(repos, q),
        hits -> {
          queryMode = true;
          allHits = hits;
          activeCategories.clear(); // 新查询重置筛选：分面值可能已不存在，残留勾选会造成「看着有数却筛空」
          activeStatuses.clear();
          facetHint.setVisible(false);
          facetHint.setManaged(false);
          buildFacets();
          applyFilters();
          state.activity("搜索「" + q + "」：" + hits.size() + " 个结果");
        },
        t -> Fx.error("搜索失败", t));
  }

  /** 浏览模式：按当前范围扫索引，装载分面计数与文件列表（不调 LLM，不开搜索引擎）。 */
  private void loadBrowseFacets(boolean force) {
    if (!force
        && !queryMode
        && loadedBrowseSig != null
        && loadedBrowseSig.equals(scopeSignature())) {
      return;
    }
    List<Path> repos = scopeRepos();
    if (repos.isEmpty()) {
      showFacetHint("先在「总览」页添加并打开仓库");
      return;
    }
    queryMode = false;
    loadedBrowseSig = null;
    Fx.runAsync(
        () -> {
          Map<String, Long> cats = new LinkedHashMap<>();
          Map<String, Long> sts = new LinkedHashMap<>();
          List<HybridSearcher.Hit> docs = new ArrayList<>();
          var indices = new java.util.LinkedHashMap<String, RepoIndex>();
          try {
            for (Path repo : repos) {
              Path idxDir = RepoPaths.indexDir(repo);
              if (!Files.exists(idxDir)) {
                continue;
              }
              String repoName = repo.getFileName().toString();
              RepoIndex idx = new RepoIndex(idxDir);
              indices.put(repoName, idx);
              repoRootByName.put(repoName, repo);
              for (String status : List.of("INDEXED", "PENDING", "UNKNOWN")) {
                for (StoredDoc d : idx.byStatus(status, 10_000)) {
                  cats.merge(displayCategory(d.categoryPath()), 1L, Long::sum);
                  sts.merge(StatusLabels.of(d.status()), 1L, Long::sum);
                  if (docs.size() < BROWSE_CAP) {
                    docs.add(toBrowseHit(repoName, d));
                  }
                }
              }
            }
          } finally {
            for (RepoIndex idx : indices.values()) {
              idx.close();
            }
          }
          return new FacetData(cats, sts, docs);
        },
        d -> {
          loadedBrowseSig = scopeSignature();
          browseCatCounts = d.catCounts();
          browseStCounts = d.stCounts();
          allHits = d.hits();
          activeCategories.clear();
          activeStatuses.clear();
          updateFacetHint();
          buildFacets();
          applyFilters();
        },
        t -> {
          showFacetHint("分面加载失败（详见日志）");
          Fx.error("加载分面失败", t);
        });
  }

  private static HybridSearcher.Hit toBrowseHit(String repoName, StoredDoc d) {
    String content = d.content() == null ? "" : d.content();
    String snippet = content.substring(0, Math.min(120, content.length()));
    return new HybridSearcher.Hit(
        repoName, d.path(), d.name(), d.categoryPath(), d.status(), snippet, 0);
  }

  private static String displayCategory(String categoryPath) {
    return categoryPath == null || categoryPath.isBlank() ? "待分类" : categoryPath;
  }

  private void showFacetHint(String text) {
    facetHint.setText(text);
    facetHint.setVisible(true);
    facetHint.setManaged(true);
  }

  private void updateFacetHint() {
    if (!browseCatCounts.isEmpty()) {
      facetHint.setVisible(false);
      facetHint.setManaged(false);
    } else {
      showFacetHint("该仓库还没有已入库文件");
    }
  }

  private List<Path> scopeRepos() {
    return "全部仓库".equals(scope.getValue()) ? List.copyOf(state.reposProperty()) : onlyCurrent();
  }

  private String scopeSignature() {
    return scope.getValue() + "|" + state.currentRepo() + "|" + state.reposProperty().size();
  }

  /** 用相应来源重建分面：查询模式按命中集计数，浏览模式按索引全量计数。 */
  private void buildFacets() {
    Map<String, Long> catCounts;
    Map<String, Long> stCounts;
    if (queryMode) {
      catCounts = new LinkedHashMap<>();
      stCounts = new LinkedHashMap<>();
      for (HybridSearcher.Hit h : allHits) {
        catCounts.merge(displayCategory(h.categoryPath()), 1L, Long::sum);
        stCounts.merge(StatusLabels.of(h.status()), 1L, Long::sum);
      }
    } else {
      catCounts = browseCatCounts;
      stCounts = browseStCounts;
    }
    fillFacets(categoryFacets, catCounts);
    fillFacets(statusFacets, stCounts);
  }

  private void fillFacets(VBox box, Map<String, Long> counts) {
    box.getChildren().clear();
    if (counts.isEmpty()) {
      Label none = new Label("当前结果无此项");
      none.getStyleClass().add("facet-count");
      box.getChildren().add(none);
      return;
    }
    counts.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .forEach(
            e -> {
              CheckBox check = new CheckBox(e.getKey());
              var count = new Label(String.valueOf(e.getValue()));
              count.getStyleClass().add("facet-count");
              HBox row = new HBox(check, count);
              row.getStyleClass().add("facet-row");
              HBox.setHgrow(check, Priority.ALWAYS);
              check.setOnAction(
                  ev -> {
                    if (check.isSelected()) {
                      activeOf(box).add(e.getKey());
                    } else {
                      activeOf(box).remove(e.getKey());
                    }
                    applyFilters();
                  });
              box.getChildren().add(row);
            });
    refreshFacetChecks();
  }

  /** 勾选状态只认 active 集合：分面重建（新查询/清筛选/进页装载）后与集合对齐。 */
  private void refreshFacetChecks() {
    alignChecks(categoryFacets, activeCategories);
    alignChecks(statusFacets, activeStatuses);
  }

  private void alignChecks(VBox box, Set<String> active) {
    for (javafx.scene.Node n : box.getChildren()) {
      if (n instanceof HBox row) {
        for (javafx.scene.Node c : row.getChildren()) {
          if (c instanceof CheckBox check) {
            check.setSelected(active.contains(check.getText()));
          }
        }
      }
    }
  }

  private Set<String> activeOf(VBox box) {
    return box == categoryFacets ? activeCategories : activeStatuses;
  }

  private void applyFilters() {
    List<HybridSearcher.Hit> view =
        allHits.stream()
            .filter(
                h ->
                    activeCategories.isEmpty()
                        || activeCategories.contains(displayCategory(h.categoryPath())))
            .filter(
                h ->
                    activeStatuses.isEmpty()
                        || activeStatuses.contains(StatusLabels.of(h.status())))
            .toList();
    results.getItems().setAll(FXCollections.observableList(view));
    boolean filtered = !activeCategories.isEmpty() || !activeStatuses.isEmpty();
    String unit = queryMode ? "个结果" : "个文件";
    resultSummary.setText(
        filtered
            ? "筛出 " + view.size() + " / " + allHits.size() + " " + unit
            : "共 " + allHits.size() + " " + unit);
    resultSummary.setVisible(!allHits.isEmpty());
    resultSummary.setManaged(!allHits.isEmpty());
    clearFilters.setVisible(filtered);
    clearFilters.setManaged(filtered);
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

  private record FacetData(
      Map<String, Long> catCounts, Map<String, Long> stCounts, List<HybridSearcher.Hit> hits) {}
}
