package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.pipeline.RollbackService;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

/** 总览页（驾驶舱）：仓库任务一览（所有仓库的下一步行动）+ 当前仓库状态统计 + 操作批次回滚（FR-4）。 */
public class DashboardController {
  private record Row(RollbackService.Batch batch) {}

  /** 仓库任务行：仓库、生命周期状态、待审+未知数。 */
  record RepoRow(Path repo, String state, long attention) {}

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

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
  @FXML private Button addRepoBtn;
  @FXML private TableView<Row> batches;
  @FXML private TableColumn<Row, String> timeCol;
  @FXML private TableColumn<Row, String> kindCol;
  @FXML private TableColumn<Row, String> batchCol;
  @FXML private TableColumn<Row, Number> movesCol;
  @FXML private TableColumn<Row, Row> actionCol;

  public DashboardController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    wireBatchTable();
    wireRepoTable();
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

  private void wireBatchTable() {
    timeCol.setCellValueFactory(
        d ->
            new SimpleStringProperty(TIME.format(Instant.ofEpochMilli(d.getValue().batch().ts()))));
    kindCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().batch().kind()));
    batchCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().batch().batchId()));
    movesCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().batch().moves()));
    actionCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
    actionCol.setCellFactory(
        col ->
            new TableCell<>() {
              private final Button btn = new Button("回滚此批次");

              {
                // 主题内置的 danger 描边变体：批量回滚入口不再满屏红块，红只落在文字和描边上
                btn.getStyleClass().addAll("danger", "button-outlined");
              }

              @Override
              protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                  setGraphic(null);
                  return;
                }
                btn.setOnAction(e -> onRollback(item.batch()));
                setGraphic(btn);
              }
            });
  }

  void reload() {
    // 占位每次重设（与待审页一致，保证空态可见）
    var placeholder = new Label("暂无操作批次");
    placeholder.getStyleClass().add("placeholder-label");
    batches.setPlaceholder(placeholder);
    Path repo = state.currentRepo();
    repoHint.setText(repo == null ? "未打开仓库——在下方「仓库任务」里选一个开始" : "当前仓库：" + repo.getFileName());
    List<Path> repos = List.copyOf(state.reposProperty());
    Fx.runEngine(
        state,
        () -> {
          // 所有仓库的生命周期与待审数（驾驶舱数据）；未初始化仓库不触碰索引（避免无谓建目录）
          Map<Path, Map<String, Long>> perRepo = new LinkedHashMap<>();
          for (Path r : repos) {
            Path idxDir = RepoPaths.indexDir(r);
            if (Files.exists(idxDir)) {
              try (RepoIndex idx = new RepoIndex(idxDir)) {
                perRepo.put(r, idx.countByStatus());
              }
            } else {
              perRepo.put(r, Map.of());
            }
          }
          List<RollbackService.Batch> list =
              repo == null ? List.of() : new RollbackService(repo).batches();
          return new LoadResult(perRepo, list);
        },
        r -> {
          // 仓库任务一览
          List<RepoRow> rows = new ArrayList<>();
          for (Path p : repos) {
            Map<String, Long> counts = r.perRepo().getOrDefault(p, Map.of());
            long attention =
                counts.getOrDefault("PENDING", 0L) + counts.getOrDefault("UNKNOWN", 0L);
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
          Map<String, Long> counts =
              repo == null ? Map.of() : r.perRepo().getOrDefault(repo, Map.of());
          statTotal.setText(
              String.valueOf(counts.values().stream().mapToLong(Long::longValue).sum()));
          statIndexed.setText(String.valueOf(counts.getOrDefault("INDEXED", 0L)));
          statPending.setText(String.valueOf(counts.getOrDefault("PENDING", 0L)));
          statUnknown.setText(String.valueOf(counts.getOrDefault("UNKNOWN", 0L)));
          // 批次表
          batches.setItems(
              FXCollections.observableArrayList(r.batches().stream().map(Row::new).toList()));
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

  private void onRollback(RollbackService.Batch batch) {
    Path repo = state.currentRepo();
    if (repo == null) {
      return;
    }
    var confirm =
        new Alert(
            Alert.AlertType.CONFIRMATION,
            "回滚批次「"
                + batch.kind()
                + "」（"
                + batch.moves()
                + " 次移动）？\n文件将逆序移回原位置，索引条目同步撤销；"
                + "回滚后的文件回到未处理状态，下次同步/重排时重新分类。");
    confirm.setTitle("回滚批次");
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
                  () -> new RollbackService(repo).rollbackBatch(batch.batchId()),
                  n -> {
                    state.markDataChanged();
                    state.activity("已回滚批次 " + batch.batchId() + "：还原 " + n + " 个文件");
                    Fx.alert("批次回滚", "已回滚 " + n + " 个文件到原位置");
                  },
                  t -> Fx.error("回滚失败", t));
            });
  }

  private record LoadResult(
      Map<Path, Map<String, Long>> perRepo, List<RollbackService.Batch> batches) {}
}
