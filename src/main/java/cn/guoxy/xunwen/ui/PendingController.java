package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.classify.CategoryProposer;
import cn.guoxy.xunwen.classify.Classifier;
import cn.guoxy.xunwen.classify.Classifier.Outcome;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.index.StoredDoc;
import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.pipeline.ApprovalService;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/** 待审队列页:低置信/未知文件的人工归位与提议批准（FR-2/FR-6）。 */
public class PendingController {
  public record Row(StoredDoc doc, ComboBox<String> categoryBox) {}

  private final AppState state;
  private List<String> categoryOptions = List.of();

  @FXML private TableView<Row> table;
  @FXML private TableColumn<Row, String> nameCol;
  @FXML private TableColumn<Row, String> statusCol;
  @FXML private TableColumn<Row, Object> categoryCol;
  @FXML private TableColumn<Row, Object> actionCol;
  @FXML private ListView<CategoryProposer.Proposal> proposals;

  public PendingController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    nameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().doc().name()));
    statusCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().doc().status()));
    categoryCol.setCellFactory(
        col ->
            new TableCell<>() {
              @Override
              protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                var row = getTableRow() == null ? null : (Row) getTableRow().itemProperty().get();
                if (empty || row == null) {
                  setGraphic(null);
                  return;
                }
                setGraphic(row.categoryBox());
              }
            });
    actionCol.setCellFactory(
        col ->
            new TableCell<>() {
              private final Button btn = new Button("归位");

              @Override
              protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                var row = getTableRow() == null ? null : (Row) getTableRow().itemProperty().get();
                if (empty || row == null) {
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
                setText(
                    empty || p == null
                        ? null
                        : p.name() + "（" + p.fileCount() + " 个文件）：" + p.reason());
              }
            });
    state.currentRepoProperty().addListener((obs, o, r) -> reload());
    reload();
  }

  private void reload() {
    Path repo = state.currentRepo();
    if (repo == null) {
      table.getItems().clear();
      proposals.getItems().clear();
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
          CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(repo));
          // UNKNOWN 重新分类以产出提议（数量少，重分类成本可接受——约束 2 的裁定方案）
          var classifier = new Classifier(state.llm(), state.config());
          List<Outcome> outcomes = new ArrayList<>();
          for (StoredDoc d : unknown) {
            var ex = state.extractor().extract(repo.resolve(d.path()));
            outcomes.add(classifier.classify(d.name(), ex.text(), ex.hasTextLayer(), tree));
          }
          List<CategoryProposer.Proposal> ps =
              new CategoryProposer(safeEmbedder()).aggregate(outcomes, tree);
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

  private Row newRow(StoredDoc d) {
    return new Row(d, new ComboBox<>(FXCollections.observableList(categoryOptions)));
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
      Fx.alert("先为该文件选择类别");
      return;
    }
    Fx.runEngine(
        state,
        () ->
            new ApprovalService(repo, state.config(), state.llm(), state.extractor())
                .approve(repo.resolve(row.doc().path()), category),
        batch -> reload(),
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
          for (Row r : ready) {
            service.approve(repo.resolve(r.doc().path()), r.categoryBox().getValue());
          }
          return ready.size();
        },
        n -> {
          reload();
          Fx.alert("已批量归位 " + n + " 个文件");
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
          reload();
          Fx.alert("新类别「" + p.name() + "」已入树，相关文件正在重新归位");
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
      Fx.alert("先在「仓库」页打开仓库");
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
