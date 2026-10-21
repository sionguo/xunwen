package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.pipeline.IngestTask;
import java.nio.file.Path;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ListView;
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
    repoList.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) {
            onOpen();
          }
        });
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
    Fx.runEngine(
        state,
        () -> new IngestTask(selected, state.config(), state.llm(), state.extractor()).sync(),
        n -> Fx.alert("已打开仓库「" + selected.getFileName() + "」，同步处理 " + n + " 个文件"),
        t -> Fx.error("同步失败", t));
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
