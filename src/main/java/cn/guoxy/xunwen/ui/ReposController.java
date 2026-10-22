package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.pipeline.IngestTask;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

/** 仓库管理页:添加/移除/打开仓库,打开时后台 sync 修正积压（FR-12）。 */
public class ReposController {
  private final AppState state;

  @FXML private ListView<Path> repoList;

  public ReposController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    repoList.setItems(state.reposProperty());
    var repoPlaceholder = new Label("还没有仓库，点「添加仓库」选择文件夹开始");
    repoPlaceholder.getStyleClass().add("placeholder-label");
    repoList.setPlaceholder(repoPlaceholder);
    repoList.setCellFactory(
        lv ->
            new ListCell<>() {
              @Override
              protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                  setText(null);
                  setGraphic(null);
                  return;
                }
                setText(null);
                setGraphic(buildCell(item));
              }
            });
    // 当前仓库变化时刷新列表以更新「当前打开」标记
    state.currentRepoProperty().addListener((obs, ov, nv) -> repoList.refresh());
    repoList.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) {
            onOpen();
          }
        });
  }

  private Node buildCell(Path repo) {
    boolean current = repo.equals(state.currentRepo());
    var box = new VBox(2);
    var name = new Label(repo.getFileName().toString());
    name.getStyleClass().add("repo-name");
    var path = new Label(repo.toString());
    path.getStyleClass().add("repo-path");
    box.getChildren().addAll(name, path);
    if (current) {
      var badge = new Label("● 当前打开");
      badge.getStyleClass().add("repo-badge");
      box.getChildren().add(badge);
    }
    return box;
  }

  @FXML
  private void onAdd() {
    var chooser = new DirectoryChooser();
    chooser.setTitle("选择资料仓库文件夹");
    java.io.File dir = chooser.showDialog(repoList.getScene().getWindow());
    if (dir == null) {
      return;
    }
    try {
      state.addRepo(dir.toPath());
    } catch (Exception ex) {
      Fx.error("添加仓库失败", ex);
    }
  }

  @FXML
  private void onOpen() {
    Path selected = repoList.getSelectionModel().getSelectedItem();
    if (selected == null) {
      return;
    }
    state.currentRepoProperty().set(selected);
    if (!Files.exists(RepoPaths.categoriesYaml(selected))) {
      Fx.alert("已打开仓库「" + selected.getFileName() + "」。该仓库还没有类别树，请先到「类别树」页点「生成类别树提案」完成冷启动。");
      return;
    }
    Fx.ProgressBox progress = Fx.progressBox("正在同步仓库「" + selected.getFileName() + "」…");
    Fx.runEngine(
        state,
        () ->
            new IngestTask(selected, state.config(), state.llm(), state.extractor())
                .sync((done, total, file) -> progress.update(done, total, file)),
        n -> {
          progress.close();
          Fx.alert("已打开仓库「" + selected.getFileName() + "」，同步处理 " + n + " 个文件");
        },
        t -> {
          progress.close();
          Fx.error("同步失败", t);
        });
  }

  @FXML
  private void onRemove() {
    Path selected = repoList.getSelectionModel().getSelectedItem();
    if (selected == null) {
      return;
    }
    var confirm =
        new Alert(
            Alert.AlertType.CONFIRMATION, "移除仓库「" + selected.getFileName() + "」？仅解除管理，不删除任何文件。");
    confirm
        .showAndWait()
        .ifPresent(
            bt -> {
              if (bt == ButtonType.OK) {
                try {
                  state.removeRepo(selected);
                } catch (Exception ex) {
                  Fx.error("移除失败", ex);
                }
              }
            });
  }
}
