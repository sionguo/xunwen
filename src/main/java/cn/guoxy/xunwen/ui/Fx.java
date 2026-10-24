package cn.guoxy.xunwen.ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** FX 线程助手：虚拟线程池跑引擎 I/O，Platform.runLater 回调；弹窗工具。 */
public final class Fx {
  private static final Logger LOG = LoggerFactory.getLogger(Fx.class);
  private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

  private Fx() {}

  public static <T> void runAsync(
      EngineTask<T> job, Consumer<T> onDone, Consumer<Throwable> onError) {
    POOL.execute(
        () -> {
          try {
            T result = job.run();
            Platform.runLater(() -> onDone.accept(result));
          } catch (Throwable t) {
            Platform.runLater(() -> onError.accept(t));
          }
        });
  }

  /** 允许抛受检异常的引擎任务（引擎方法普遍 throws IOException，Supplier 装不下）。 */
  @FunctionalInterface
  public interface EngineTask<T> {
    T run() throws Exception;
  }

  /** 引擎任务专用：全局锁串行化（防 Lucene 写锁冲突），后台虚拟线程执行，FX 线程回调。 */
  public static <T> void runEngine(
      AppState state, EngineTask<T> job, Consumer<T> onDone, Consumer<Throwable> onError) {
    POOL.execute(
        () -> {
          try {
            T result;
            synchronized (state.engineLock()) {
              result = job.run();
            }
            Platform.runLater(() -> onDone.accept(result));
          } catch (Throwable t) {
            Platform.runLater(() -> onError.accept(t));
          }
        });
  }

  public static void alert(String msg) {
    alert("寻文", msg);
  }

  /** 带具体标题的信息弹窗（无重复 header，标题可区分来源流程）。 */
  public static void alert(String title, String msg) {
    Alert a = new Alert(Alert.AlertType.INFORMATION, msg);
    a.setTitle(title);
    a.setHeaderText(null);
    a.showAndWait();
  }

  public static void error(String msg, Throwable t) {
    LOG.error(msg, t); // 弹窗之外留档：完整堆栈在 ~/.xunwen/logs/xunwen.log
    Alert a = new Alert(Alert.AlertType.ERROR, String.valueOf(t));
    a.setTitle(msg);
    a.setHeaderText(msg);
    a.showAndWait();
  }

  /** 非模态进度框：后台任务用 update 刷新，close 收尾。 */
  public static ProgressBox progressBox(String title) {
    var dialog = new javafx.scene.control.Dialog<Void>();
    dialog.setTitle(title);
    dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CANCEL);
    ((javafx.scene.control.Button)
            dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CANCEL))
        .setDisable(true); // v1 任务不可中断，按钮仅占位
    var bar = new javafx.scene.control.ProgressBar();
    bar.setPrefWidth(360);
    var label = new javafx.scene.control.Label("准备中…");
    var box = new javafx.scene.layout.VBox(10, bar, label);
    box.setPadding(new javafx.geometry.Insets(16));
    dialog.getDialogPane().setContent(box);
    dialog.show();
    return new ProgressBox(bar, label, dialog);
  }

  /** 进度框句柄：update/close 可在任意线程调用（内部切 FX 线程）。 */
  public record ProgressBox(
      javafx.scene.control.ProgressBar bar,
      javafx.scene.control.Label label,
      javafx.scene.control.Dialog<Void> dialog) {
    public void update(int done, int total, String current) {
      javafx.application.Platform.runLater(
          () -> {
            bar.setProgress(total <= 0 ? -1 : (double) done / total);
            label.setText(done + " / " + total + "　" + current);
          });
    }

    public void close() {
      javafx.application.Platform.runLater(dialog::close);
    }
  }
}
