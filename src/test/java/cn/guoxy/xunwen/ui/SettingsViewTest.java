package cn.guoxy.xunwen.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 设置页交互：表单回填、非法输入弹错且不落盘、合法保存生效。 */
class SettingsViewTest extends ViewTestBase {

  @BeforeEach
  void open() {
    showView("settings-view.fxml", new SettingsController(state));
  }

  @Test
  void formShowsCurrentConfig() {
    assertEquals(state.config().baseUrl(), textOf("#baseUrl"));
    assertEquals(state.config().chatModel(), textOf("#chatModel"));
    assertEquals(state.config().embedModel(), textOf("#embedModel"));
  }

  @Test
  void saveWithBadNumberShowsErrorAndKeepsConfig() {
    String before = state.config().baseUrl();
    setText("#vectorDim", "abc");
    fireClick(buttonByText("保存"));
    org.junit.jupiter.api.Assertions.assertTrue(dialogVisible());
    closeDialog();
    assertEquals(before, state.config().baseUrl()); // 非法输入未落盘
  }

  @Test
  void savePersistsValidConfig() {
    setText("#chatModel", "test-model");
    fireClick(buttonByText("保存"));
    org.junit.jupiter.api.Assertions.assertTrue(dialogVisible());
    closeDialog();
    assertEquals("test-model", state.config().chatModel());
    assertEquals("test-model", ((TextField) byId("#chatModel")).getText());
  }
}
