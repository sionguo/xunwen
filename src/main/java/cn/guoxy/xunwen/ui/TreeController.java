package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.classify.ColdStartSampler;
import cn.guoxy.xunwen.classify.TreeProposer;
import cn.guoxy.xunwen.pipeline.ApprovalService;
import cn.guoxy.xunwen.pipeline.ReshuffleTask;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

/** 类别树页：冷启动提案、批准入树、重排（可取消）、批次回滚、右键编辑提案（FR-6/FR-3/FR-4）。 */
public class TreeController {
  private final AppState state;
  private CategoryTree proposal; // 非空 = 正在编辑提案
  private AtomicBoolean activeCancelFlag;

  @FXML private TreeView<String> tree;
  @FXML private Label treeHint;
  @FXML private ProgressBar progress;
  @FXML private Label status;

  public TreeController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    state.currentRepoProperty().addListener((obs, o, repo) -> reload());
    reload();
    tree.setContextMenu(buildMenu());
  }

  private void showHint(String text) {
    treeHint.setText(text);
    treeHint.setVisible(text != null);
  }

  private void reload() {
    proposal = null;
    Path repo = state.currentRepo();
    if (repo == null) {
      tree.setRoot(null);
      showHint("先在「仓库」页添加并打开仓库");
      return;
    }
    if (!Files.exists(RepoPaths.categoriesYaml(repo))) {
      tree.setRoot(null);
      showHint("该仓库还没有类别树，点上方「生成类别树提案」完成冷启动");
      return;
    }
    showHint(null);
    Fx.runAsync(
        () -> CategoryTree.load(RepoPaths.categoriesYaml(repo)),
        t -> tree.setRoot(buildRoot(repo.getFileName().toString(), t)),
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

  @FXML
  private void onGenerateProposal() {
    Path repo = state.currentRepo();
    if (repo == null) {
      Fx.alert("先在「仓库」页添加并打开仓库");
      return;
    }
    status.setText("正在采样并生成类别树提案…");
    Fx.runAsync(
        () -> {
          var samples = ColdStartSampler.sample(repo, state.extractor(), 20);
          javafx.application.Platform.runLater(
              () -> status.setText("采样完成（" + samples.size() + " 个），正在调用 LLM 归纳类别树…"));
          return new TreeProposer(state.llm()).propose(samples);
        },
        t -> {
          proposal = t;
          tree.setRoot(buildRoot("提案（未生效）", t));
          status.setText("提案已生成：右键可增删改，确认后「批准入树」");
        },
        t -> {
          status.setText("");
          Fx.error("生成失败（检查 LLM 配置）", t);
        });
  }

  @FXML
  private void onApproveProposal() {
    Path repo = state.currentRepo();
    if (repo == null || proposal == null) {
      Fx.alert("没有待批准的提案");
      return;
    }
    try {
      proposal.save(RepoPaths.categoriesYaml(repo));
      proposal = null;
      reload();
      Fx.alert("类别树已生效，可执行「重排仓库」");
    } catch (Exception e) {
      Fx.error("保存失败", e);
    }
  }

  @FXML
  private void onDiscardProposal() {
    if (proposal != null) {
      reload();
      status.setText("已放弃提案");
    }
  }

  @FXML
  private void onReshuffle() {
    Path repo = state.currentRepo();
    if (repo == null) {
      Fx.alert("先打开仓库");
      return;
    }
    progress.setVisible(true);
    progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
    AtomicBoolean cancelledFlag = new AtomicBoolean(false);
    ReshuffleTask.Progress listener =
        new ReshuffleTask.Progress() {
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
        () -> new ReshuffleTask(repo, state.config(), state.llm(), state.extractor()).run(listener),
        batch -> {
          progress.setProgress(1);
          status.setText("重排完成，批次：" + batch + "（可回滚）");
          reload();
        },
        t -> {
          progress.setVisible(false);
          status.setText("");
          Fx.error("重排失败", t);
        });
  }

  @FXML
  private void onCancelReshuffle() {
    if (activeCancelFlag != null) {
      activeCancelFlag.set(true);
      status.setText("正在取消，已完成的文件保留（批次仍可回滚）…");
    }
  }

  private void onAddChild() {
    TreeItem<String> sel = tree.getSelectionModel().getSelectedItem();
    if (sel == null) {
      return;
    }
    if (proposal == null) {
      Fx.alert("右键编辑仅对提案生效：先点「生成类别树提案」");
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
      Fx.alert("右键编辑仅对提案生效：先点「生成类别树提案」");
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
      Fx.alert("「其他」是兜底类别，不可删除");
      return;
    }
    String path = pathOf(sel);
    var confirm = new Alert(Alert.AlertType.CONFIRMATION, "删除类别「" + path + "」？其下文件将移回待分类并重新分类。");
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
                    Fx.alert("已删除，重分类 " + n + " 个文件");
                  },
                  t -> Fx.error("删除失败", t));
            });
  }

  @FXML
  private void onRollback() {
    Path repo = state.currentRepo();
    if (repo == null) {
      return;
    }
    var dialog = new TextInputDialog();
    dialog.setHeaderText("输入要回滚的批次号");
    dialog
        .showAndWait()
        .ifPresent(
            batch ->
                Fx.runEngine(
                    state,
                    () ->
                        new ApprovalService(repo, state.config(), state.llm(), state.extractor())
                            .rollbackBatch(batch.strip()),
                    n -> {
                      reload();
                      Fx.alert("已回滚批次 " + batch + "，还原 " + n + " 个文件");
                    },
                    t -> Fx.error("回滚失败", t)));
  }

  private static String pathOf(TreeItem<String> item) {
    List<String> parts = new ArrayList<>();
    for (TreeItem<String> n = item; n.getParent() != null; n = n.getParent()) {
      parts.add(0, n.getValue());
    }
    return String.join("/", parts);
  }
}
