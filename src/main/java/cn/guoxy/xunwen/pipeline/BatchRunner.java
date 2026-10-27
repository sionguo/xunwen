package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.config.GlobalConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * 两阶段批量编排（唯一实现）：prepare 并发（虚拟线程 + 信号量限流），主循环按提交顺序逐个 commit（单写者），进度与取消统一处理。ReshuffleTask 与
 * IngestTask.sync 共用，消除双份编排。
 */
public final class BatchRunner {

  /** 进度回调：每文件一次；cancelled() 返回 true 时中止剩余文件（已完成的保留）。 */
  public interface Progress {
    void onProgress(int done, int total, String currentFile);

    default boolean cancelled() {
      return false;
    }

    static Progress noop() {
      return (_, _, _) -> {};
    }
  }

  /** 阶段一：提取 + 分类（只读，可并发）；返回 null 表示跳过（如已入库）。 */
  @FunctionalInterface
  public interface PrepareTask {
    FileProcessor.Prepared run(Path file) throws Exception;
  }

  /** 阶段二：移动 + 入库（独占写者，串行）。 */
  @FunctionalInterface
  public interface CommitTask {
    FileProcessor.Result run(Path file, FileProcessor.Prepared p) throws IOException;
  }

  private final GlobalConfig cfg;

  public BatchRunner(GlobalConfig cfg) {
    this.cfg = cfg;
  }

  /** 进度路径名换算：绝对路径 → 相对 root 的 POSIX 路径（UI 展示口径）。 */
  public static Progress relativizeTo(Path root, Progress inner) {
    return (done, total, file) ->
        inner.onProgress(done, total, FileProcessor.posix(root.relativize(Path.of(file))));
  }

  /**
   * 执行一批：prepare 并发跑，按提交顺序串行 commit；统计与移动记录由本类写入 stats。 prepare 抛异常按 FAILED 计数不中断整批；total 为进度分母（可与
   * files.size() 不同， 如 sync 以全仓库文件数为口径）。
   */
  public void run(
      List<Path> files,
      int total,
      BatchStats stats,
      Progress progress,
      PrepareTask prepare,
      CommitTask commit)
      throws IOException {
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    Semaphore gate = new Semaphore(Math.max(1, cfg.concurrency()));
    try {
      List<Future<FileProcessor.Prepared>> futures = new ArrayList<>(files.size());
      for (Path file : files) {
        futures.add(
            pool.submit(
                () -> {
                  gate.acquireUninterruptibly();
                  try {
                    return prepare.run(file);
                  } finally {
                    gate.release();
                  }
                }));
      }
      int done = 0;
      for (int i = 0; i < futures.size(); i++) {
        if (progress.cancelled()) {
          break; // 已完成的文件保留；未完成的 prepare 由 shutdownNow 中断
        }
        FileProcessor.Prepared p;
        try {
          p = futures.get(i).get();
        } catch (ExecutionException e) {
          // prepare 阶段异常（如文件读取失败）：按失败计数，不中断整批
          stats.add(new FileProcessor.Result(null, "FAILED"));
          progress.onProgress(++done, total, files.get(i).toString());
          continue;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        FileProcessor.Result r = commit.run(files.get(i), p);
        stats.add(r);
        if (r.dest() != null) {
          stats.moved(files.get(i), r.dest());
        }
        progress.onProgress(++done, total, files.get(i).toString());
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
