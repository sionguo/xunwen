package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.pipeline.IngestTask;
import cn.guoxy.xunwen.pipeline.MoveRecord;
import cn.guoxy.xunwen.watch.RepoWatcher;
import java.nio.file.Path;
import java.util.List;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

/** UI 驱动的仓库监视：Timeline 周期 tick，处理新文件并防移动回环（FR-11）。 */
public final class WatchServiceBinder implements AutoCloseable {
  private final AppState state;
  private RepoWatcher watcher;
  private Timeline timeline;

  public WatchServiceBinder(AppState state) {
    this.state = state;
    state
        .currentRepoProperty()
        .addListener(
            (obs, old, repo) -> {
              unbind();
              if (repo != null) {
                bind(repo);
              }
            });
  }

  private void bind(Path repo) {
    try {
      watcher = new RepoWatcher(repo, java.time.Duration.ofSeconds(2));
      watcher.start();
      timeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> tick(repo)));
      timeline.setCycleCount(Timeline.INDEFINITE);
      timeline.play();
    } catch (Exception ex) {
      Fx.error("启动监视失败", ex);
    }
  }

  private void tick(Path repo) {
    try {
      watcher.pollEvents();
      List<Path> stable = watcher.drainStable();
      if (stable.isEmpty()) {
        return;
      }
      Fx.runEngine(
          state,
          () -> new IngestTask(repo, state.config(), state.llm(), state.extractor()).ingest(stable),
          records -> {
            for (MoveRecord r : records) {
              watcher.ignorePrefix(r.to()); // 防回环：自己移动产生的 CREATE 不再处理
            }
            // 不弹窗打扰：结果去类别树/待审页查看
          },
          t -> Fx.error("新文件处理失败", t));
    } catch (Exception e) {
      // tick 内异常不中断 timeline
    }
  }

  @Override
  public void close() {
    unbind();
  }

  private void unbind() {
    if (timeline != null) {
      timeline.stop();
      timeline = null;
    }
    if (watcher != null) {
      try {
        watcher.close();
      } catch (Exception ignored) {
        // 关闭失败无需处理
      }
      watcher = null;
    }
  }
}
