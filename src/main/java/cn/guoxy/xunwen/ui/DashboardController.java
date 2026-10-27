package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.index.FileStatus;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.stage.DirectoryChooser;

/** 总览页（驾驶舱）：仓库任务一览（所有仓库的下一步行动）+ 当前仓库状态统计。 */
public class DashboardController {
  /** 仓库任务行：仓库、生命周期状态、待审+未知数。 */
  record RepoRow(Path repo, String state, long attention) {}

  private final AppState state;

  @FXML private Label repoHint;
  @FXML private TableView<RepoRow> repoTable;
  @FXML private TableColumn<RepoRow, String> repoCol;
  @FXML private TableColumn<RepoRow, String> repoStateCol;
  @FXML private TableColumn<RepoRow, Number> attentionCol;
  @FXML private TableColumn<RepoRow, RepoRow> repoActionCol;
  @FXML private Label statTotal;
  @FXML private Label statIndexed;
  @FXML private Label statPending;
  @FXML private Label statUnknown;
  @FXML private Label statVector;
  @FXML private Button addRepoBtn;

  public DashboardController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    wireRepoTable();
    // 向量服务状态卡：跟随单一降级状态源（AppState.embedDegraded）
    statVector.setText(state.embedDegraded() ? "不可用" : "正常");
    state.embedDegradedProperty().addListener((o, ov, v) -> statVector.setText(v ? "不可用" : "正常"));
    // 待审/未知卡即入口：点击直达待审页（状态即行动）
    if (statPending.getParent() instanceof javafx.scene.layout.VBox pendingCard) {
      pendingCard.getStyleClass().add("stat-clickable");
      pendingCard.setOnMouseClicked(e -> state.requestNavigation("PENDING"));
    }
    if (statUnknown.getParent() instanceof javafx.scene.layout.VBox unknownCard) {
      unknownCard.getStyleClass().add("stat-clickable");
      unknownCard.setOnMouseClicked(e -> state.requestNavigation("PENDING"));
    }
    state.currentRepoProperty().addListener((obs, o, r) -> reload());
    state.dataChangedProperty().addListener((obs, o, v) -> reload());
    reload();
  }

  private void wireRepoTable() {
    // 仓库管理并入总览：行右键移除（仅解除管理，不删文件），添加走右上「添加仓库」
    repoTable.setRowFactory(
        tv -> {
          TableRow<RepoRow> row = new TableRow<>();
          MenuItem remove = new MenuItem("移除仓库");
          remove.disableProperty().bind(row.itemProperty().isNull());
          remove.setOnAction(
              e -> {
                RepoRow item = row.getItem();
                if (item != null) {
                  onRemoveRepo(item.repo());
                }
              });
          row.setContextMenu(new ContextMenu(remove));
          return row;
        });
    repoCol.setCellValueFactory(
        d -> new SimpleStringProperty(d.getValue().repo().getFileName().toString()));
    repoStateCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().state()));
    attentionCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().attention()));
    repoActionCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
    repoActionCol.setCellFactory(
        col ->
            new TableCell<>() {
              private final Button btn = new Button();

              @Override
              protected void updateItem(RepoRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                  setGraphic(null);
                  return;
                }
                boolean unbootstrapped = item.state().startsWith("未冷启动");
                btn.setText(unbootstrapped ? "完成冷启动" : "打开");
                btn.setOnAction(e -> RepoOpener.open(state, item.repo()));
                setGraphic(btn);
              }
            });
  }

  void reload() {
    Path repo = state.currentRepo();
    repoHint.setText(repo == null ? "未打开仓库——在下方「仓库任务」里选一个开始" : "当前仓库：" + repo.getFileName());
    List<Path> repos = List.copyOf(state.reposProperty());
    Fx.runAsync(
        () -> {
          // 所有仓库的生命周期与待审数（驾驶舱数据）；只读不进 engineLock，未初始化仓库得空视图
          Map<Path, Map<FileStatus, Long>> perRepo = new LinkedHashMap<>();
          for (Path r : repos) {
            try (RepoIndex idx = RepoIndex.openReadOnly(RepoPaths.indexDir(r))) {
              perRepo.put(r, idx.countByStatus());
            }
          }
          return perRepo;
        },
        r -> {
          // 仓库任务一览
          List<RepoRow> rows = new ArrayList<>();
          for (Path p : repos) {
            Map<FileStatus, Long> counts = r.getOrDefault(p, Map.of());
            long attention =
                counts.getOrDefault(FileStatus.PENDING, 0L)
                    + counts.getOrDefault(FileStatus.UNKNOWN, 0L);
            String stateText;
            if (p.equals(repo)) {
              stateText = "● 当前打开";
            } else if (Files.exists(RepoPaths.categoriesYaml(p))) {
              stateText = "就绪";
            } else {
              stateText = "未冷启动";
            }
            rows.add(new RepoRow(p, stateText, attention));
          }
          repoTable.setItems(FXCollections.observableArrayList(rows)); // 拷贝为可变列表
          // 当前仓库统计卡
          Map<FileStatus, Long> counts = repo == null ? Map.of() : r.getOrDefault(repo, Map.of());
          statTotal.setText(
              String.valueOf(counts.values().stream().mapToLong(Long::longValue).sum()));
          statIndexed.setText(String.valueOf(counts.getOrDefault(FileStatus.INDEXED, 0L)));
          statPending.setText(String.valueOf(counts.getOrDefault(FileStatus.PENDING, 0L)));
          statUnknown.setText(String.valueOf(counts.getOrDefault(FileStatus.UNKNOWN, 0L)));
        },
        t -> Fx.error("加载总览失败", t));
  }

  /** 添加即打开：选文件夹入库后直接进入引导式初始化或同步（原「仓库」页主流程）。 */
  @FXML
  private void onAddRepo() {
    var chooser = new DirectoryChooser();
    chooser.setTitle("选择资料仓库文件夹");
    java.io.File dir = chooser.showDialog(addRepoBtn.getScene().getWindow());
    if (dir == null) {
      return;
    }
    try {
      state.addRepo(dir.toPath());
      RepoOpener.open(state, dir.toPath());
    } catch (Exception ex) {
      Fx.error("添加仓库失败", ex);
    }
  }

  private void onRemoveRepo(Path repo) {
    var confirm =
        new Alert(Alert.AlertType.CONFIRMATION, "移除仓库「" + repo.getFileName() + "」？仅解除管理，不删除任何文件。");
    confirm.setTitle("移除仓库");
    confirm.setHeaderText(null);
    confirm
        .showAndWait()
        .ifPresent(
            bt -> {
              if (bt == ButtonType.OK) {
                try {
                  state.removeRepo(repo);
                } catch (Exception ex) {
                  Fx.error("移除失败", ex);
                }
              }
            });
  }
}
