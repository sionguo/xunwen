package cn.guoxy.xunwen.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/** TestFX 基座：整测生命周期内 launch 一次 TestApp，各测试用 showView 换入被测页面。 */
public abstract class ViewTestBase extends ApplicationTest {
  @TempDir static Path dir;

  static AppState state;
  private static boolean launched;

  @BeforeAll
  static void boot() throws Exception {
    state = new AppState(dir.resolve("config.json"), dir.resolve("repos.json"));
    if (!launched) {
      ApplicationTest.launch(TestApp.class);
      launched = true;
    }
  }

  protected void showView(String fxml, Object controller) {
    interact(
        () -> {
          try {
            FXMLLoader loader = new FXMLLoader(ViewTestBase.class.getResource(fxml));
            loader.setController(controller);
            Parent root = loader.load();
            TestApp.stage.getScene().setRoot(root);
            rootNode(root); // robot 查询域刷新到新场景（默认缓存 launch 时的旧节点）
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
    targetWindow(TestApp.stage); // 原生键盘事件(write/eraseText)需要明确目标窗口
  }

  protected boolean dialogVisible() {
    boolean[] hit = {false};
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              hit[0] =
                  javafx.stage.Window.getWindows().stream()
                      .anyMatch(
                          w ->
                              w.getScene() != null
                                  && w.getScene().getRoot()
                                      instanceof javafx.scene.control.DialogPane);
              return null;
            });
    return hit[0];
  }

  /** 走弹窗的确定按钮正常关闭（hide 强杀 showAndWait 在 JavaFX 27 下会炸事件循环）。 */
  protected void closeDialog() {
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              for (javafx.stage.Window w :
                  java.util.List.copyOf(javafx.stage.Window.getWindows())) { // 关窗会改列表,先快照
                if (w.getScene() != null
                    && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane pane
                    && pane.getButtonTypes().contains(javafx.scene.control.ButtonType.OK)
                    && pane.lookupButton(javafx.scene.control.ButtonType.OK)
                        instanceof javafx.scene.control.Button ok) {
                  ok.fire(); // 等价用户点「确定」：确认弹窗的取消按钮排序在前，不能盲点第一个
                }
              }
              return null;
            });
  }

  /** 原生鼠标点击节点（按其屏幕坐标），需要 macOS 辅助功能授权；弹 showAndWait 的按钮不可用（死锁）。 */
  protected void nativeClick(javafx.scene.Node node) {
    clickOn(point(node));
  }

  /** 事件级点击：不依赖 macOS 辅助功能权限。只投递不等待——handler 若弹 showAndWait 对话框会阻塞 FX 线程，同步等待会死锁。 */
  protected void fireClick(javafx.scene.Node node) {
    javafx.application.Platform.runLater(
        () -> node.fireEvent(new javafx.event.ActionEvent(node, node)));
  }

  private static void dumpButtons(javafx.scene.Node n) {
    System.out.println("[debug-dump] " + n.getClass().getSimpleName() + " id=" + n.getId());
    if (n instanceof javafx.scene.Parent p) {
      for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
        dumpButtons(c);
      }
    }
  }

  /** 按 fx:id 在场景中找节点（自遍历，不依赖 robot 查询缓存）。 */
  protected javafx.scene.Node byId(String fxId) {
    String id = fxId.startsWith("#") ? fxId.substring(1) : fxId;
    var out = new javafx.scene.Node[1];
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              out[0] = findById(TestApp.stage.getScene().getRoot(), id);
              return null;
            });
    return out[0];
  }

  private static javafx.scene.Node findById(javafx.scene.Node n, String id) {
    if (id.equals(n.getId())) {
      return n;
    }
    if (n instanceof javafx.scene.Parent p) {
      for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
        javafx.scene.Node r = findById(c, id);
        if (r != null) {
          return r;
        }
      }
    }
    return null;
  }

  /** 读文本框内容（FX 线程）。 */
  protected String textOf(String fxId) {
    var out = new String[1];
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              out[0] = ((javafx.scene.control.TextInputControl) byId(fxId)).getText();
              return null;
            });
    return out[0];
  }

  /** 设置文本框内容（FX 线程）。 */
  protected void setText(String fxId, String value) {
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              ((javafx.scene.control.TextInputControl) byId(fxId)).setText(value);
              return null;
            });
  }

  /** 场景内按文字找按钮（FX 线程遍历）。 */
  protected javafx.scene.control.Button buttonByText(String text) {
    var found = new javafx.scene.control.Button[1];
    interact(
        (java.util.concurrent.Callable<Void>)
            () -> {
              collectButtons(TestApp.stage.getScene().getRoot(), text, found);
              return null;
            });
    return found[0];
  }

  private static void collectButtons(
      javafx.scene.Node n, String text, javafx.scene.control.Button[] out) {
    if (n instanceof javafx.scene.control.Button b && text.equals(b.getText())) {
      out[0] = b;
    }
    if (n instanceof javafx.scene.control.ToolBar tb) {
      for (javafx.scene.Node c : tb.getItems()) { // ToolBar 子项在 items 而非 children
        collectButtons(c, text, out);
      }
    }
    if (n instanceof javafx.scene.Parent p) {
      for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
        collectButtons(c, text, out);
      }
    }
  }
}
