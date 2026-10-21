package cn.guoxy.xunwen.ui;

import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 待审页交互：无仓库时表格清空，页面可用。 */
class PendingViewTest extends ViewTestBase {

  @BeforeEach
  void open() {
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              state.currentRepoProperty().set(null);
              return null;
            });
    showView("pending-view.fxml", new PendingController(state));
  }

  @Test
  void tableEmptyWithoutRepo() {
    org.junit.jupiter.api.Assertions.assertTrue(
        ((TableView<?>) byId("#table")).getItems().isEmpty());
    org.junit.jupiter.api.Assertions.assertTrue(((TableView<?>) byId("#table")).isVisible());
  }
}
