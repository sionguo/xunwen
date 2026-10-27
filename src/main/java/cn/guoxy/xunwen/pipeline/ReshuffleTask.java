package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

public final class ReshuffleTask {
  /** 重排模式：增量只处理未入库文件；全量对全部文件（含待分类）重新分类归位。 */
  public enum Mode {
    INCREMENTAL,
    FULL
  }

  public interface Progress {
    void onProgress(int done, int total, String currentFile);

    default boolean cancelled() {
      return false;
    }

    static Progress noop() {
      return (_, _, _) -> {};
    }
  }

  private final Path repoRoot;
  private final GlobalConfig cfg;
  private final LlmClient llm;
  private final ContentExtractor extractor;

  public ReshuffleTask(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
    this.cfg = cfg;
    this.llm = llm;
    this.extractor = extractor;
  }

  /** 增量重排（默认）：处理所有未入库文件。 */
  public BatchStats run(Progress progress) throws IOException {
    return run(progress, Mode.INCREMENTAL);
  }

  /** 按模式重排一批文件，返回处置统计。 两阶段并发管道：提取+分类按配置并发（虚拟线程 + 信号量限流）， 移动+入库在主循环串行执行（单写者）。 */
  public BatchStats run(Progress progress, Mode mode) throws IOException {
    Path treeYaml = RepoPaths.categoriesYaml(repoRoot);
    if (!Files.exists(treeYaml)) {
      throw new IllegalStateException("先完成冷启动：缺少 " + treeYaml);
    }
    CategoryTree tree = CategoryTree.load(treeYaml);
    FileProcessor processor = new FileProcessor(repoRoot, cfg, llm, extractor);
    BatchStats stats = new BatchStats();

    List<Path> files = listFiles(mode == Mode.FULL);
    int total = files.size();
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    Semaphore gate = new Semaphore(Math.max(1, cfg.concurrency()));
    try {
      try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
        processor.warmVectors(tree);
        List<Future<FileProcessor.Prepared>> futures = new ArrayList<>(total);
        for (Path file : files) {
          futures.add(
              pool.submit(
                  () -> {
                    gate.acquireUninterruptibly();
                    try {
                      return processor.prepare(file, tree, index, mode == Mode.FULL);
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
            // 提取/分类阶段异常（如文件读取失败）：按失败计数，不中断整批
            stats.add(new FileProcessor.Result(null, "FAILED"));
            progress.onProgress(
                ++done, total, FileProcessor.posix(repoRoot.relativize(files.get(i))));
            continue;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
          FileProcessor.Result r = processor.commit(p, index);
          stats.add(r);
          if (r.dest() != null) {
            stats.moved(files.get(i), r.dest());
          }
          progress.onProgress(
              ++done, total, FileProcessor.posix(repoRoot.relativize(files.get(i))));
        }
      }
    } finally {
      pool.shutdownNow();
    }
    return stats;
  }

  /** full 时包含待分类目录：卡在待分类的文件在全量重排中重新分类救援。 */
  private List<Path> listFiles(boolean full) throws IOException {
    Path xunwen = RepoPaths.xunwenDir(repoRoot);
    Path pending = RepoPaths.pendingDir(repoRoot);
    try (Stream<Path> walk = Files.walk(repoRoot)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> !p.startsWith(xunwen) && (full || !p.startsWith(pending)))
          .filter(
              p -> {
                String n = p.getFileName().toString();
                return !n.startsWith(".") && !n.startsWith("~$");
              })
          .toList();
    }
  }
}
