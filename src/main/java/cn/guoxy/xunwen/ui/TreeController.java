package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.classify.ColdStartSampler;
import cn.guoxy.xunwen.classify.TreeProposer;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.index.StoredDoc;
import cn.guoxy.xunwen.pipeline.ApprovalService;
import cn.guoxy.xunwen.pipeline.BatchRunner;
import cn.guoxy.xunwen.pipeline.ReshuffleTask;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

/** 类别树页：冷启动提案、批准入树、重排（可取消）、右键编辑提案（FR-6/FR-3）。 */
public class TreeController implements PageAware {
  private final AppState state;
  private CategoryTree proposal; // 非空 = 正在编辑提案
  private AtomicBoolean activeCancelFlag;
  // 各类别已归档文件数（类别路径 → 数量），提案态为空表
  private Map<String, Long> docCounts = Map.of();

  @FXML private TreeView<String> tree;
  @FXML private Label treeHint;
  @FXML private ProgressBar progress;
  @FXML private Label status;
  @FXML private Button generateBtn;
  @FXML private Button approveBtn;
  @FXML private Button discardBtn;
  @FXML private Button reshuffleBtn;
  @FXML private Button cancelBtn;

  public TreeController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    tree.setCellFactory(
        tv ->
            new javafx.scene.control.TreeCell<>() {
              private final Label count = new Label();

              {
                count.getStyleClass().add("tree-count");
              }

              @Override
              protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                  setText(null);
                  setGraphic(null);
                  return;
                }
                setText(item);
                TreeItem<String> node = getTreeItem();
                Long n =
                    node == null || node.getParent() == null ? null : docCounts.get(pathOf(node));
                count.setText(n == null ? null : String.valueOf(n));
                setGraphic(n == null ? null : count);
              }
            });
    state.currentRepoProperty().addListener((obs, o, repo) -> reload());
    reload();
    tree.setContextMenu(buildMenu());
  }

  @Override
  public void onPageShown() {
    // 引导式初始化：仓库页「完成冷启动」跳转过来后自动开始生成提案（标志读后即清）
    if (state.consumeAutoGenerateProposal()) {
      onGenerateProposal();
    }
  }

  private void showHint(String text) {
    treeHint.setText(text);
    treeHint.setVisible(text != null);
  }

  private void reload() {
    proposal = null;
    docCounts = Map.of();
    Path repo = state.currentRepo();
    if (repo == null) {
      tree.setRoot(null);
      showHint("先在「总览」页添加并打开仓库");
      return;
    }
    // 有未生效提案（含应用重启前的）优先恢复展示：提案编辑中的状态不能丢
    Path proposalYaml = RepoPaths.proposalYaml(repo);
    if (Files.exists(proposalYaml)) {
      tree.setRoot(null);
      showHint(null);
      Fx.runAsync(
          () -> CategoryTree.load(proposalYaml),
          t -> {
            proposal = t;
            tree.setRoot(buildRoot("提案（未生效·已恢复）", t));
            status.setText("已恢复上次未生效的提案：右键编辑，确认后「批准入树」");
          },
          t -> Fx.error("恢复提案失败", t));
      return;
    }
    if (!Files.exists(RepoPaths.categoriesYaml(repo))) {
      tree.setRoot(null);
      showHint("该仓库还没有类别树，点上方「生成类别树提案」完成冷启动");
      return;
    }
    showHint(null);
    Fx.runAsync(
        () -> {
          CategoryTree t = CategoryTree.load(RepoPaths.categoriesYaml(repo));
          Map<String, Long> counts = new LinkedHashMap<>();
          try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(repo))) {
            for (StoredDoc d : idx.byStatus("INDEXED", 100_000)) {
              counts.merge(d.categoryPath(), 1L, Long::sum);
            }
          }
          return Map.entry(t, counts);
        },
        e -> {
          docCounts = e.getValue();
          tree.setRoot(buildRoot(repo.getFileName().toString(), e.getKey()));
        },
        t -> Fx.error("加载类别树失败", t));
  }

  private TreeItem<String> buildRoot(String repoName, CategoryTree t) {
    TreeItem<String> root = new TreeItem<>(repoName);
    root.setExpanded(true);
    for (String path : t.flattenPaths()) {
      TreeItem<String> node = root;
      for (String part : path.split("/")) {
        TreeItem<String> child = find(node, part);
        if (child == null) {
          child = new TreeItem<>(part);
          node.getChildren().add(child);
        }
        node = child;
      }
    }
    return root;
  }

  private static TreeItem<String> find(TreeItem<String> parent, String name) {
    for (TreeItem<String> c : parent.getChildren()) {
      if (c.getValue().equals(name)) {
        return c;
      }
    }
    return null;
  }

  private ContextMenu buildMenu() {
    MenuItem addChild = new MenuItem("加子类别");
    addChild.setOnAction(e -> onAddChild());
    MenuItem rename = new MenuItem("重命名");
    rename.setOnAction(e -> onRename());
    MenuItem remove = new MenuItem("删除类别");
    remove.setOnAction(e -> onRemove());
    return new ContextMenu(addChild, rename, remove);
  }

  /** 任务运行期间禁用会改变状态的按钮（防重入/连点排队重复任务），取消键仅重排时可用。 */
  private void setBusy(boolean busy, boolean reshuffling) {
    generateBtn.setDisable(busy);
    approveBtn.setDisable(busy);
    discardBtn.setDisable(busy);
    reshuffleBtn.setDisable(busy);
    cancelBtn.setDisable(!reshuffling);
  }

  @FXML
  private void onGenerateProposal() {
    Path repo = state.currentRepo();
    if (repo == null) {
      Fx.alert("类别树", "先在「总览」页添加并打开仓库");
      return;
    }
    setBusy(true, false);
    status.setText("正在采样并生成类别树提案…");
    Fx.runAsync(
        () -> {
          var samples = ColdStartSampler.sample(repo, state.extractor(), 20);
          javafx.application.Platform.runLater(
              () -> status.setText("采样完成（" + samples.size() + " 个），正在调用 LLM 归纳类别树…"));
          var t = new TreeProposer(state.llm()).propose(samples);
          t.save(RepoPaths.proposalYaml(repo)); // 提案落盘：应用重启后可恢复继续编辑
          return t;
        },
        t -> {
          setBusy(false, false);
          proposal = t;
          docCounts = Map.of(); // 提案无归档计数
          tree.setRoot(buildRoot("提案（未生效）", t));
          status.setText("提案已生成：右键可增删改，确认后「批准入树」");
          state.activity("类别树提案已生成（" + t.roots().size() + " 个一级类别），待批准");
        },
        t -> {
          setBusy(false, false);
          status.setText("");
          Fx.error("生成失败（检查 LLM 配置）", t);
        });
  }

  @FXML
  private void onApproveProposal() {
    Path repo = state.currentRepo();
    if (repo == null || proposal == null) {
      Fx.alert("类别树", "没有待批准的提案");
      return;
    }
    CategoryTree effective = treeFromUi(); // 右键编辑改的是 UI 树，批准时回读
    if (effective.roots().isEmpty()) {
      Fx.alert("类别树", "提案还没有任何类别：右键「加子类别」先添加");
      return;
    }
    if (effective.find("其他").isEmpty()) {
      List<Category> roots = new ArrayList<>(effective.roots());
      roots.add(new Category("其他", "", List.of())); // 兜底类别与 LLM 路径同规则强制补上
      effective = new CategoryTree(roots);
    }
    try {
      effective.save(RepoPaths.categoriesYaml(repo));
      Files.deleteIfExists(RepoPaths.proposalYaml(repo)); // 提案已生效，清掉持久化副本
      proposal = null;
      reload();
      state.markDataChanged(); // 总览/待审页刷新类别选项
      state.activity("类别树已生效（" + repo.getFileName() + "），可执行「重排仓库」");
      offerReshuffle();
    } catch (Exception e) {
      Fx.error("保存失败", e);
    }
  }

  /** 从 UI 树收集提案（右键编辑改的是 TreeItem，批准时回读）；描述沿用原提案同路径类别。 */
  private CategoryTree treeFromUi() {
    List<Category> roots = new ArrayList<>();
    for (TreeItem<String> top : tree.getRoot().getChildren()) {
      roots.add(categoryOf(top, ""));
    }
    return new CategoryTree(roots);
  }

  private Category categoryOf(TreeItem<String> node, String parentPath) {
    String path = parentPath.isEmpty() ? node.getValue() : parentPath + "/" + node.getValue();
    String desc = proposal.find(path).map(Category::description).orElse("");
    List<Category> children = new ArrayList<>();
    for (TreeItem<String> c : node.getChildren()) {
      children.add(categoryOf(c, path));
    }
    return new Category(node.getValue(), desc, children);
  }

  /** 入树成功后衔接下一步：弹窗直接提供「立即重排」，不再让用户自己去工具栏找按钮。 */
  private void offerReshuffle() {
    ButtonType reshuffleNow = new ButtonType("立即重排");
    var done =
        new Alert(
            Alert.AlertType.INFORMATION,
            "类别树已生效。是否立即重排仓库，把现有文件按新类别归位？",
            reshuffleNow,
            ButtonType.CLOSE);
    done.setTitle("类别树已生效");
    done.setHeaderText(null);
    done.showAndWait()
        .ifPresent(
            bt -> {
              if (bt == reshuffleNow) {
                onReshuffle();
              }
            });
  }

  @FXML
  private void onDiscardProposal() {
    if (proposal != null) {
      try {
        Files.deleteIfExists(RepoPaths.proposalYaml(state.currentRepo()));
      } catch (Exception e) {
        Fx.error("放弃提案失败", e);
        return;
      }
      reload();
      status.setText("已放弃提案");
    }
  }

  @FXML
  private void onReshuffle() {
    Path repo = state.currentRepo();
    if (repo == null) {
      Fx.alert("类别树", "先打开仓库");
      return;
    }
    // 模式选择：增量快（只处理新/变更文件），全量彻底（含待分类救援与放错位置的文件）
    ButtonType incremental = new ButtonType("增量重排（推荐）");
    ButtonType full = new ButtonType("全量重排");
    var choose =
        new Alert(
            Alert.AlertType.CONFIRMATION,
            "增量：只处理新增与有变化的文件，速度快。\n" + "全量：所有文件（含待分类与已归档）重新分类归位，可纠正放错的文件，LLM 调用多、耗时长。",
            incremental,
            full,
            ButtonType.CANCEL);
    choose.setTitle("选择重排模式");
    choose.setHeaderText(null);
    var chosen = choose.showAndWait().orElse(ButtonType.CANCEL);
    if (chosen == ButtonType.CANCEL) {
      return;
    }
    ReshuffleTask.Mode mode =
        chosen == full ? ReshuffleTask.Mode.FULL : ReshuffleTask.Mode.INCREMENTAL;
    reshuffle(repo, mode);
  }

  private void reshuffle(Path repo, ReshuffleTask.Mode mode) {
    progress.setVisible(true);
    progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
    setBusy(true, true);
    AtomicBoolean cancelledFlag = new AtomicBoolean(false);
    BatchRunner.Progress listener =
        new BatchRunner.Progress() {
          @Override
          public void onProgress(int done, int total, String file) {
            Platform.runLater(
                () -> {
                  progress.setProgress(total == 0 ? 1 : (double) done / total);
                  status.setText(done + "/" + total + " " + file);
                });
          }

          @Override
          public boolean cancelled() {
            return cancelledFlag.get();
          }
        };
    activeCancelFlag = cancelledFlag;
    Fx.runEngine(
        state,
        () ->
            new ReshuffleTask(repo, state.config(), state.llm(), state.extractor())
                .run(listener, mode),
        stats -> {
          setBusy(false, false);
          progress.setVisible(false); // 完成即隐藏，不满格蓝条常驻状态栏
          String label = mode == ReshuffleTask.Mode.FULL ? "全量重排完成" : "重排完成";
          status.setText((cancelledFlag.get() ? "已取消（已完成部分保留）：" : label + "：") + stats.summary());
          state.activity(label + "：" + stats.summary());
          reload();
        },
        t -> {
          setBusy(false, false);
          progress.setVisible(false);
          status.setText("");
          Fx.error("重排失败", t);
        });
  }

  @FXML
  private void onCancelReshuffle() {
    if (activeCancelFlag != null) {
      activeCancelFlag.set(true);
      status.setText("正在取消，已完成的文件保留…");
    }
  }

  private void onAddChild() {
    TreeItem<String> sel = tree.getSelectionModel().getSelectedItem();
    if (sel == null) {
      return;
    }
    if (proposal == null) {
      Fx.alert("类别树", "右键编辑仅对提案生效：先点「生成类别树提案」");
      return;
    }
    var dialog = new TextInputDialog();
    dialog.setHeaderText("新类别名称（2-6 字）");
    dialog
        .showAndWait()
        .ifPresent(
            name -> {
              if (name.isBlank()) {
                return;
              }
              sel.getChildren().add(new TreeItem<>(name));
              sel.setExpanded(true);
            });
  }

  private void onRename() {
    TreeItem<String> sel = tree.getSelectionModel().getSelectedItem();
    if (sel == null || sel.getParent() == null) {
      return;
    }
    if (proposal == null) {
      Fx.alert("类别树", "右键编辑仅对提案生效：先点「生成类别树提案」");
      return;
    }
    var dialog = new TextInputDialog(sel.getValue());
    dialog.setHeaderText("重命名类别");
    dialog
        .showAndWait()
        .ifPresent(
            name -> {
              if (!name.isBlank()) {
                sel.setValue(name);
              }
            });
  }

  private void onRemove() {
    TreeItem<String> sel = tree.getSelectionModel().getSelectedItem();
    Path repo = state.currentRepo();
    if (sel == null || sel.getParent() == null || repo == null) {
      return;
    }
    if ("其他".equals(sel.getValue())) {
      Fx.alert("类别树", "「其他」是兜底类别，不可删除");
      return;
    }
    String path = pathOf(sel);
    var confirm = new Alert(Alert.AlertType.CONFIRMATION, "删除类别「" + path + "」？其下文件将移回待分类并重新分类。");
    confirm.setTitle("删除类别");
    confirm.setHeaderText(null);
    confirm
        .showAndWait()
        .ifPresent(
            bt -> {
              if (bt != ButtonType.OK) {
                return;
              }
              Fx.runEngine(
                  state,
                  () ->
                      new ApprovalService(repo, state.config(), state.llm(), state.extractor())
                          .removeCategory(CategoryTree.load(RepoPaths.categoriesYaml(repo)), path),
                  n -> {
                    reload();
                    state.markDataChanged();
                    state.activity("已删除类别「" + path + "」，" + n + " 个文件移回待分类重分类");
                    Fx.alert("删除类别", "已删除，重分类 " + n + " 个文件");
                  },
                  t -> Fx.error("删除失败", t));
            });
  }

  private static String pathOf(TreeItem<String> item) {
    List<String> parts = new ArrayList<>();
    for (TreeItem<String> n = item; n.getParent() != null; n = n.getParent()) {
      parts.add(0, n.getValue());
    }
    return String.join("/", parts);
  }
}
