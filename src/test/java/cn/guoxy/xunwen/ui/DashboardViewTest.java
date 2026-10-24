package cn.guoxy.xunwen.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.guoxy.xunwen.index.Doc;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.move.OperationLog;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 总览页交互：无仓库时显示引导与零值统计；打开仓库后展示状态统计与批次列表。 */
class DashboardViewTest extends ViewTestBase {

  @BeforeEach
  void open() {
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              state.currentRepoProperty().set(null);
              return null;
            });
    showView("dashboard-view.fxml", new DashboardController(state));
  }

  @Test
  void showsGuidanceAndZerosWithoutRepo() {
    assertEquals("0", ((Label) byId("#statTotal")).getText());
    assertEquals("0", ((Label) byId("#statIndexed")).getText());
    assertTrue(((TableView<?>) byId("#batches")).getItems().isEmpty());
    assertTrue(((TableView<?>) byId("#batches")).isVisible());
  }

  @Test
  void loadsStatsAndBatchesForRepo() throws Exception {
    // 引擎侧直造数据：一个已归档条目 + 一个待审条目 + 一条移动日志
    Path repo = Files.createTempDirectory("xunwen-dash-repo");
    int dim = state.config().vectorDim();
    float[] neutral = new float[dim];
    neutral[0] = 1f; // Lucene COSINE 拒绝全零向量，测试数据用单位向量兜底（与引擎一致）
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(repo));
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(repo))) {
      idx.upsert(
          new Doc(
              "工作文档/a.txt",
              "a.txt",
              "txt",
              "h",
              1L,
              2L,
              "INDEXED",
              "工作文档",
              "c",
              neutral,
              "",
              "",
              0.9));
      idx.upsert(new Doc("待分类/b.txt", "b.txt", "txt", "h", 1L, 2L, "PENDING", "", "c", neutral));
    }
    new OperationLog(RepoPaths.operationsJsonl(repo))
        .append(new OperationLog.Op("reshuffle-1", 1L, "MOVE", "/src/a.txt", "/dst/a.txt"));

    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              state.addRepo(repo); // 驾驶舱统计按注册仓库读取
              state.currentRepoProperty().set(repo);
              return null;
            });
    awaitEngine(); // runEngine 异步回调

    assertEquals("2", ((Label) byId("#statTotal")).getText());
    assertEquals("1", ((Label) byId("#statIndexed")).getText());
    assertEquals("1", ((Label) byId("#statPending")).getText());
    assertEquals(1, ((TableView<?>) byId("#batches")).getItems().size());
    assertEquals(1, ((TableView<?>) byId("#repoTable")).getItems().size()); // 仓库任务一览

    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              state.currentRepoProperty().set(null);
              state.removeRepo(repo);
              return null;
            });
  }

  /** 等待 runEngine 的后台任务与 FX 回调完成（虚拟线程极快，一拍足够）。 */
  private void awaitEngine() throws Exception {
    Thread.sleep(300);
  }
}
