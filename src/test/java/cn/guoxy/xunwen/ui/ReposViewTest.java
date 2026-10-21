package cn.guoxy.xunwen.ui;

import java.nio.file.Files;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 仓库页交互：无选中无动作、未冷启动打开提示、移除确认弹窗生效。 */
class ReposViewTest extends ViewTestBase {

  @BeforeEach
  void open() throws Exception {
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              state.currentRepoProperty().set(null);
              return null;
            });
    showView("repos-view.fxml", new ReposController(state));
  }

  private void selectFirst() {
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              ((ListView<?>) byId("#repoList")).getSelectionModel().select(0);
              return null;
            });
  }

  @Test
  void openWithoutSelectionDoesNothing() {
    fireClick(buttonByText("打开"));
    org.junit.jupiter.api.Assertions.assertFalse(dialogVisible());
  }

  @Test
  void openUnbootstrappedRepoSuggestsColdStart() throws Exception {
    var repo = Files.createTempDirectory("xunwen-empty-repo");
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              state.addRepo(repo);
              return null;
            });
    selectFirst();
    fireClick(buttonByText("打开"));
    org.junit.jupiter.api.Assertions.assertTrue(dialogVisible()); // 「先去类别树页冷启动」提示
    closeDialog();
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              state.removeRepo(repo);
              return null;
            });
  }

  @Test
  void removeWithConfirmDialogRemovesRepo() throws Exception {
    var repo = Files.createTempDirectory("xunwen-remove-repo");
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              state.addRepo(repo);
              return null;
            });
    int before = state.reposProperty().size();
    selectFirst();
    fireClick(buttonByText("移除"));
    org.junit.jupiter.api.Assertions.assertTrue(dialogVisible()); // 确认弹窗
    closeDialog(); // 点确认按钮
    Thread.sleep(300); // removeRepo 在事件处理器里同步执行，留一拍
    org.junit.jupiter.api.Assertions.assertEquals(before - 1, state.reposProperty().size());
  }
}
