package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.classify.CategoryProposer;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.index.StoredDoc;
import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.pipeline.ApprovalService;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/** 待审队列页:低置信/未知文件的人工归位与提议批准（FR-2/FR-6）。 全部数据直接读索引（含入库时写入的提议/置信度），打开页面不调 LLM。 */
public class PendingController {
  public record Row(StoredDoc doc, ComboBox<String> categoryBox) {}

  private final AppState state;
  private List<String> categoryOptions = List.of();

  @FXML private TableView<Row> table;
  @FXML private TableColumn<Row, String> nameCol;
  @FXML private TableColumn<Row, String> statusCol;
  @FXML private TableColumn<Row, String> confidenceCol;
  @FXML private TableColumn<Row, String> proposedCol;
  @FXML private TableColumn<Row, Object> categoryCol;
  @FXML private TableColumn<Row, Object> actionCol;
  @FXML private ListView<CategoryProposer.Proposal> proposals;

  public PendingController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    nameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().doc().name()));
    statusCol.setCellValueFactory(
        d -> new SimpleStringProperty(StatusLabels.of(d.getValue().doc().status())));
    // 状态渲染成徽标（黄=待审、红=未知），与总览统计卡同色语义
    statusCol.setCellFactory(
        col ->
            new TableCell<>() {
              private final Label badge = new Label();

              {
                badge.getStyleClass().add("status-badge");
              }

              @Override
              protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                  setGraphic(null);
                  return;
                }
                badge.setText(item);
                badge.getStyleClass().removeAll("status-pending", "status-unknown");
                badge.getStyleClass().add("未知".equals(item) ? "status-unknown" : "status-pending");
                setGraphic(badge);
              }
            });
    confidenceCol.setCellValueFactory(
        d -> new SimpleStringProperty(fmtConfidence(d.getValue().doc().confidence())));
    proposedCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().doc().proposed()));
    categoryCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().categoryBox()));
    categoryCol.setCellFactory(
        col ->
            new TableCell<>() {
              @Override
              protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || !(item instanceof ComboBox<?> box)) {
                  setGraphic(null);
                  return;
                }
                setGraphic(box);
              }
            });
    actionCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
    actionCol.setCellFactory(
        col ->
            new TableCell<>() {
              private final Button btn = new Button("归位");

              @Override
              protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || !(item instanceof Row row)) {
                  setGraphic(null);
                  return;
                }
                btn.setOnAction(e -> onApprove(row));
                setGraphic(btn);
              }
            });
    proposals.setCellFactory(
        lv ->
            new ListCell<>() {
              @Override
              protected void updateItem(CategoryProposer.Proposal p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : proposalText(p));
              }
            });
    state.currentRepoProperty().addListener((obs, o, r) -> reload());
    state.dataChangedProperty().addListener((obs, o, v) -> reload());
    reload();
  }

  private static String fmtConfidence(double c) {
    return c <= 0.0 ? "—" : String.format("%.0f%%", c * 100);
  }

  private static String proposalText(CategoryProposer.Proposal p) {
    String files = String.join("、", p.files().stream().limit(3).toList());
    String more = p.files().size() > 3 ? " 等" + p.files().size() + " 个" : "";
    return p.name() + "（" + p.fileCount() + " 个文件：" + files + more + "）：" + p.reason();
  }

  void reload() {
    Path repo = state.currentRepo();
    table.setPlaceholder(placeholder("暂无待审文件"));
    proposals.setPlaceholder(placeholder("暂无类别提议"));
    if (repo == null) {
      table.getItems().clear();
      proposals.getItems().clear();
      table.setPlaceholder(placeholder("先在「总览」页打开仓库"));
      return;
    }
    Fx.runEngine(
        state,
        () -> {
          List<StoredDoc> pending;
          List<StoredDoc> unknown;
          try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(repo))) {
            pending = idx.byStatus("PENDING", 10_000);
            unknown = idx.byStatus("UNKNOWN", 10_000);
          }
          CategoryTree tree =
              Files.exists(RepoPaths.categoriesYaml(repo))
                  ? CategoryTree.load(RepoPaths.categoriesYaml(repo))
                  : new CategoryTree(List.of());
          // 提议在入库时已写入索引：这里只做聚合（少量 embed 调用，embedding 不可用退化为同名合并）
          List<CategoryProposer.Proposal> ps =
              new CategoryProposer(safeEmbedder()).aggregate(unknown, tree);
          return new LoadResult(pending, unknown, tree.flattenPaths(), ps);
        },
        r -> {
          categoryOptions = r.options();
          List<Row> rows = new ArrayList<>();
          for (StoredDoc d : r.pending()) {
            rows.add(newRow(d));
          }
          for (StoredDoc d : r.unknown()) {
            rows.add(newRow(d));
          }
          table.setItems(FXCollections.observableList(rows));
          proposals.getItems().setAll(r.proposals());
        },
        t -> Fx.error("加载待审队列失败", t));
  }

  private static Label placeholder(String text) {
    var l = new Label(text);
    l.getStyleClass().add("placeholder-label");
    return l;
  }

  /** 构建行：候选类别（入库时 LLM 给出的倾向）预选进下拉框，人工归位从「盲选」变「确认」。 */
  private Row newRow(StoredDoc d) {
    ComboBox<String> box = new ComboBox<>(FXCollections.observableList(categoryOptions));
    box.setPromptText("选择类别…");
    box.setMaxWidth(Double.MAX_VALUE);
    String candidate = d.candidatePath();
    if (!candidate.isBlank() && categoryOptions.contains(candidate)) {
      box.getSelectionModel().select(candidate);
    }
    return new Row(d, box);
  }

  private record LoadResult(
      List<StoredDoc> pending,
      List<StoredDoc> unknown,
      List<String> options,
      List<CategoryProposer.Proposal> proposals) {}

  private void onApprove(Row row) {
    Path repo = requireRepo();
    if (repo == null) {
      return;
    }
    String category = row.categoryBox().getValue();
    if (category == null || category.isBlank()) {
      Fx.alert("人工归位", "先为该文件选择类别");
      return;
    }
    Fx.runEngine(
        state,
        () -> {
          new ApprovalService(repo, state.config(), state.llm(), state.extractor())
              .approve(repo.resolve(row.doc().path()), category);
          return null;
        },
        v -> {
          state.activity("人工归位：" + row.doc().name() + " → " + category);
          reload();
        },
        t -> Fx.error("归位失败", t));
  }

  @FXML
  private void onApproveAll() {
    Path repo = requireRepo();
    if (repo == null) {
      return;
    }
    List<Row> ready =
        table.getItems().stream()
            .filter(
                r -> r.categoryBox().getValue() != null && !r.categoryBox().getValue().isBlank())
            .toList();
    if (ready.isEmpty()) {
      Fx.alert("没有已选择类别的待审文件");
      return;
    }
    Fx.runEngine(
        state,
        () -> {
          var service = new ApprovalService(repo, state.config(), state.llm(), state.extractor());
          List<ApprovalService.PendingApproval> items = new ArrayList<>();
          for (Row r : ready) {
            items.add(
                new ApprovalService.PendingApproval(
                    repo.resolve(r.doc().path()), r.categoryBox().getValue()));
          }
          return service.approveBatch(items); // 单索引会话，N 文件不再开关 N 次索引
        },
        n -> {
          state.activity("批量归位 " + n + " 个文件");
          reload();
          Fx.alert("批量归位", "已批量归位 " + n + " 个文件");
        },
        t -> Fx.error("批量归位失败", t));
  }

  @FXML
  private void onApproveProposal() {
    Path repo = requireRepo();
    CategoryProposer.Proposal p = proposals.getSelectionModel().getSelectedItem();
    if (repo == null || p == null) {
      return;
    }
    Fx.runEngine(
        state,
        () ->
            new ApprovalService(repo, state.config(), state.llm(), state.extractor())
                .approveProposal(
                    CategoryTree.load(RepoPaths.categoriesYaml(repo)),
                    p.name(),
                    p.reason(),
                    true,
                    null),
        tree -> {
          state.activity("新类别「" + p.name() + "」已入树，" + p.fileCount() + " 个相关文件重新归位中");
          reload();
          Fx.alert("新类别「" + p.name() + "」已入树，" + p.fileCount() + " 个相关文件正在重新归位");
        },
        t -> Fx.error("批准失败", t));
  }

  @FXML
  private void onIgnoreProposal() {
    proposals.getItems().remove(proposals.getSelectionModel().getSelectedItem());
  }

  private Path requireRepo() {
    Path repo = state.currentRepo();
    if (repo == null) {
      Fx.alert("待审", "先在「总览」页打开仓库");
    }
    return repo;
  }

  /** 向量服务不可用时退化为零向量（聚合只做同名合并），不阻塞待审页加载。 */
  private EmbeddingClient safeEmbedder() {
    return new EmbeddingClient() {
      @Override
      public float[] embed(String text) {
        try {
          return state.llm().embed(text);
        } catch (RuntimeException e) {
          return new float[state.config().vectorDim()];
        }
      }

      @Override
      public List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
      }
    };
  }
}
