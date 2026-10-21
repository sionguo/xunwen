package cn.guoxy.xunwen.ui;

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
}
