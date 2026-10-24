package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.FileInfo;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/** 增量入库与积压同步（FR-12：以文件系统为准修正索引）。 */
public final class IngestTask {
  private final Path repoRoot;
  private final FileProcessor processor;
  private final GlobalConfig cfg;
  private final LlmClient llm;
  private final ContentExtractor extractor;

  public IngestTask(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
    this.cfg = cfg;
    this.llm = llm;
    this.extractor = extractor;
    this.processor = new FileProcessor(this.repoRoot, cfg, llm, extractor);
  }

  /** 增量入库（监视新到文件，量少走串行）；返回含移动明细的处置统计。 */
  public BatchStats ingest(List<Path> newFiles) throws IOException {
    if (!Files.exists(RepoPaths.categoriesYaml(repoRoot))) {
      return new BatchStats("ingest-skipped"); // 未冷启动：无树可归类，文件留在原地等冷启动后的 sync
    }
    CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(repoRoot));
    String batchId = "ingest-" + System.currentTimeMillis();
    BatchStats stats = new BatchStats(batchId);
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      for (Path f : newFiles) {
        FileProcessor.Result r = processor.process(f, tree, batchId, index);
        stats.add(r);
        if (r.dest() != null) {
          stats.moved(batchId, f, r.dest());
        }
      }
    }
    return stats;
  }

  public BatchStats sync() throws IOException {
    return sync(ReshuffleTask.Progress.noop());
  }

  /** 带进度回调的积压同步：两阶段并发（提取+分类并发，入库串行），每个文件回调一次进度 （total 为全仓库文件数）。 */
  public BatchStats sync(ReshuffleTask.Progress progress) throws IOException {
    if (!Files.exists(RepoPaths.categoriesYaml(repoRoot))) {
      return new BatchStats("sync-skipped"); // 未冷启动：无树可归类，UI 层负责提示先冷启动
    }
    CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(repoRoot));
    String batchId = "sync-" + System.currentTimeMillis();
    BatchStats stats = new BatchStats(batchId);
    // 文件系统侧指纹（POSIX 相对路径 → [mtime, size]）；一次遍历建指纹表并留存文件列表
    Map<String, long[]> fs = new HashMap<>();
    List<Path> allFiles = new ArrayList<>();
    for (Path p : walkFiles()) {
      FileTime ft = Files.getLastModifiedTime(p);
      fs.put(
          FileProcessor.posix(repoRoot.relativize(p)), new long[] {ft.toMillis(), Files.size(p)});
      allFiles.add(p);
    }
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      // 1. 索引有、文件系统无 → 清理；指纹不同 → 删旧记录待重处理
      int cleaned = 0;
      for (FileInfo info : index.allFiles()) {
        long[] fp = fs.get(info.path());
        if (fp == null) {
          index.deleteByPath(info.path());
          cleaned++;
        } else if (fp[0] != info.mtime() || fp[1] != info.size()) {
          index.deleteByPath(info.path()); // 下面的 process 会重新提取入库
        }
      }
      stats.cleaned(cleaned);

      // 2. 文件系统有、索引无 → 两阶段并发入库
      List<Path> toProcess = new ArrayList<>();
      for (Path p : allFiles) {
        if (index.findByPath(FileProcessor.posix(repoRoot.relativize(p))).isEmpty()) {
          toProcess.add(p);
        }
      }
      int total = Math.max(1, fs.size());
      ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
      Semaphore gate = new Semaphore(Math.max(1, cfg.concurrency()));
      try {
        processor.warmVectors(tree);
        List<Future<FileProcessor.Prepared>> futures = new ArrayList<>(toProcess.size());
        for (Path p : toProcess) {
          futures.add(
              pool.submit(
                  () -> {
                    gate.acquireUninterruptibly();
                    try {
                      return processor.prepare(p, tree, index);
                    } finally {
                      gate.release();
                    }
                  }));
        }
        int processed = 0;
        for (int i = 0; i < futures.size(); i++) {
          FileProcessor.Prepared p;
          try {
            p = futures.get(i).get();
          } catch (ExecutionException e) {
            stats.add(new FileProcessor.Result(null, "FAILED"));
            progress.onProgress(
                ++processed, total, FileProcessor.posix(repoRoot.relativize(toProcess.get(i))));
            continue;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
          FileProcessor.Result r = processor.commit(p, batchId, index);
          stats.add(r);
          if (r.dest() != null) {
            stats.moved(batchId, toProcess.get(i), r.dest());
          }
          progress.onProgress(
              ++processed, total, FileProcessor.posix(repoRoot.relativize(toProcess.get(i))));
        }
      } finally {
        pool.shutdownNow();
      }
    }
    return stats;
  }

  private List<Path> walkFiles() throws IOException {
    Path xunwen = RepoPaths.xunwenDir(repoRoot);
    // 待分类/ 不可排除：其中的 PENDING/UNKNOWN 文件已被索引，排除会让 sync 把它们
    // 当作"文件系统无"而清掉索引条目（已索引的会被 findByPath 命中跳过，不会重复处理）
    try (var walk = Files.walk(repoRoot)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> !p.startsWith(xunwen))
          .filter(
              p -> {
                String n = p.getFileName().toString();
                return !n.startsWith(".") && !n.startsWith("~$");
              })
          .toList();
    }
  }
}
