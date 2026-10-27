package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 类别树页交互：无仓库/无提案时按钮给出中文提示弹窗。 */
class TreeViewTest extends ViewTestBase {

  @BeforeEach
  void open() {
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              state.currentRepoProperty().set(null); // 无仓库前提
              return null;
            });
    showView("tree-view.fxml", new TreeController(state));
  }

  @Test
  void generateProposalWithoutRepoAlerts() {
    fireClick(buttonByText("生成类别树提案"));
    org.junit.jupiter.api.Assertions.assertTrue(dialogVisible());
    closeDialog();
  }

  @Test
  void approveProposalWithoutProposalAlerts() {
    fireClick(buttonByText("批准入树"));
    org.junit.jupiter.api.Assertions.assertTrue(dialogVisible());
    closeDialog();
  }

  @Test
  void reshuffleWithoutRepoAlerts() {
    fireClick(buttonByText("重排仓库"));
    org.junit.jupiter.api.Assertions.assertTrue(dialogVisible());
    closeDialog();
  }

  @Test
  void loadsTreeWhileWritableIndexHeld() throws Exception {
    // 类别树竞态回归：批量任务持写锁期间切换仓库，只读加载不得弹「加载类别树失败」
    Path repo = Files.createTempDirectory("xunwen-tree-repo");
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(repo));
    try (RepoIndex held = RepoIndex.openWritable(RepoPaths.indexDir(repo))) {
      interact(
          (java.util.concurrent.Callable<Void>)
              () -> {
                state.currentRepoProperty().set(repo); // 监听器触发 reload（runAsync + openReadOnly）
                return null;
              });
      Thread.sleep(300); // runAsync 后台读 + FX 回调
      Assertions.assertFalse(dialogVisible());
      interact(
          (java.util.concurrent.Callable<Void>)
              () -> {
                state.currentRepoProperty().set(null);
                return null;
              });
    }
  }
}
