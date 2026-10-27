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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
      return new BatchStats(); // 未冷启动：无树可归类，文件留在原地等冷启动后的 sync
    }
    CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(repoRoot));
    BatchStats stats = new BatchStats();
    try (RepoIndex index = RepoIndex.openWritable(RepoPaths.indexDir(repoRoot))) {
      for (Path f : newFiles) {
        FileProcessor.Result r = processor.process(f, tree, index);
        stats.add(r);
        if (r.dest() != null) {
          stats.moved(f, r.dest());
        }
      }
    }
    return stats;
  }

  public BatchStats sync() throws IOException {
    return sync(BatchRunner.Progress.noop());
  }

  /** 带进度回调的积压同步：两阶段并发（提取+分类并发，入库串行），每个文件回调一次进度 （total 为全仓库文件数）。 */
  public BatchStats sync(BatchRunner.Progress progress) throws IOException {
    if (!Files.exists(RepoPaths.categoriesYaml(repoRoot))) {
      return new BatchStats(); // 未冷启动：无树可归类，UI 层负责提示先冷启动
    }
    CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(repoRoot));
    BatchStats stats = new BatchStats();
    // 文件系统侧指纹（POSIX 相对路径 → [mtime, size]）；一次遍历建指纹表并留存文件列表
    Map<String, long[]> fs = new HashMap<>();
    List<Path> allFiles = new ArrayList<>();
    for (Path p : RepoPaths.listRepoFiles(repoRoot, true)) {
      FileTime ft = Files.getLastModifiedTime(p);
      fs.put(
          FileProcessor.posix(repoRoot.relativize(p)), new long[] {ft.toMillis(), Files.size(p)});
      allFiles.add(p);
    }
    try (RepoIndex index = RepoIndex.openWritable(RepoPaths.indexDir(repoRoot))) {
      // 1. 索引有、文件系统无 → 清理；指纹不同 → 删旧记录待重处理；存活的路径记入已入库集合
      Set<String> indexed = new HashSet<>();
      int cleaned = 0;
      for (FileInfo info : index.allFiles()) {
        long[] fp = fs.get(info.path());
        if (fp == null) {
          index.deleteByPath(info.path());
          cleaned++;
        } else if (fp[0] != info.mtime() || fp[1] != info.size()) {
          index.deleteByPath(info.path()); // 下面的 process 会重新提取入库
        } else {
          indexed.add(info.path());
        }
      }
      stats.cleaned(cleaned);

      // 2. 文件系统有、索引无 → 两阶段并发入库（已入库集合一次建表，不再逐文件开 reader 查询）
      List<Path> toProcess = new ArrayList<>();
      for (Path p : allFiles) {
        if (!indexed.contains(FileProcessor.posix(repoRoot.relativize(p)))) {
          toProcess.add(p);
        }
      }
      int total = Math.max(1, fs.size());
      processor.warmVectors(tree);
      new BatchRunner(cfg)
          .run(
              toProcess,
              total,
              stats,
              BatchRunner.relativizeTo(repoRoot, progress),
              p -> processor.prepare(p, tree, index),
              (p, prepared) -> processor.commit(prepared, index));
    }
    return stats;
  }
}
