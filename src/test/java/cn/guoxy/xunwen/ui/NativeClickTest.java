package cn.guoxy.xunwen.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** 原生鼠标事件冒烟：验证系统辅助功能授权下，真实点击能落到按钮（不弹窗路径）。 */
class NativeClickTest extends ViewTestBase {

  @Test
  void nativeClicksReachButtons() {
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              state.currentRepoProperty().set(null);
              return null;
            });
    showView("repos-view.fxml", new ReposController(state));
    nativeClick(buttonByText("打开")); // 无选中 → 无动作不弹窗
    assertFalse(dialogVisible(), "真实点击未生效或产生了意外弹窗");

    showView("search-view.fxml", new SearchController(state));
    setText("#query", "");
    nativeClick(buttonByText("搜索")); // 空查询 → 无动作不弹窗
    assertFalse(dialogVisible(), "真实点击未生效或产生了意外弹窗");
  }
}
