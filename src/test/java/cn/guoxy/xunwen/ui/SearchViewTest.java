package cn.guoxy.xunwen.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 搜索页交互：空查询无动作，无仓库时给出提示弹窗。 */
class SearchViewTest extends ViewTestBase {

  @BeforeEach
  void open() {
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              state.currentRepoProperty().set(null);
              state.reposProperty().clear(); // 清空仓库列表
              return null;
            });
    showView("search-view.fxml", new SearchController(state));
  }

  @Test
  void emptyQueryDoesNothing() {
    setText("#query", "");
    fireClick(buttonByText("搜索"));
    org.junit.jupiter.api.Assertions.assertFalse(dialogVisible());
  }

  @Test
  void searchWithoutReposAlerts() {
    setText("#query", "关键词");
    fireClick(buttonByText("搜索"));
    org.junit.jupiter.api.Assertions.assertTrue(dialogVisible());
    closeDialog();
  }
}
