package cn.guoxy.xunwen.ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.Alert;

/** FX 线程助手：虚拟线程池跑引擎 I/O，Platform.runLater 回调；弹窗工具。 */
public final class Fx {
  private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

  private Fx() {}

  public static <T> void runAsync(
      Supplier<T> job, Consumer<T> onDone, Consumer<Throwable> onError) {
    POOL.execute(
        () -> {
          try {
            T result = job.get();
            Platform.runLater(() -> onDone.accept(result));
          } catch (Throwable t) {
            Platform.runLater(() -> onError.accept(t));
          }
        });
  }

  /** 引擎任务专用：全局锁串行化（防 Lucene 写锁冲突），其余同 runAsync。 */
  public static <T> void runEngine(
      AppState state, Supplier<T> job, Consumer<T> onDone, Consumer<Throwable> onError) {
    runAsync(
        () -> {
          synchronized (state.engineLock()) {
            return job.get();
          }
        },
        onDone,
        onError);
  }

  public static void alert(String msg) {
    new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
  }

  public static void error(String msg, Throwable t) {
    Alert a = new Alert(Alert.AlertType.ERROR, msg + "\n" + String.valueOf(t));
    a.showAndWait();
  }
}
