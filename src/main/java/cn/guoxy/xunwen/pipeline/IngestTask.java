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
import java.util.Set;

/** 增量入库与积压同步（FR-12：以文件系统为准修正索引）。 */
public final class IngestTask {
  private final Path repoRoot;
  private final FileProcessor processor;

  public IngestTask(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
    this.processor = new FileProcessor(this.repoRoot, cfg, llm, extractor);
  }

  public List<MoveRecord> ingest(List<Path> newFiles) throws IOException {
    if (!Files.exists(RepoPaths.categoriesYaml(repoRoot))) {
      return List.of(); // 未冷启动：无树可归类，文件留在原地等冷启动后的 sync
    }
    CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(repoRoot));
    String batchId = "ingest-" + System.currentTimeMillis();
    Set<String> suppressed = RollbackSuppress.load(repoRoot);
    List<MoveRecord> done = new ArrayList<>();
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      for (Path f : newFiles) {
        String rel = FileProcessor.posix(repoRoot.relativize(f.toAbsolutePath().normalize()));
        if (suppressed.contains(rel)) {
          continue; // 回滚还原的文件：不自动重新入库（FR-4），重排时重新确认
        }
        Path dest = processor.process(f, tree, batchId, index);
        if (dest != null) {
          done.add(new MoveRecord(batchId, f, dest));
        }
      }
    }
    return done;
  }

  public int sync() throws IOException {
    if (!Files.exists(RepoPaths.categoriesYaml(repoRoot))) {
      return 0; // 未冷启动：无树可归类，UI 层负责提示先冷启动
    }
    CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(repoRoot));
    String batchId = "sync-" + System.currentTimeMillis();
    // 文件系统侧指纹（POSIX 相对路径 → [mtime, size]）
    Map<String, long[]> fs = new HashMap<>();
    for (Path p : walkFiles()) {
      FileTime ft = Files.getLastModifiedTime(p);
      fs.put(
          FileProcessor.posix(repoRoot.relativize(p)), new long[] {ft.toMillis(), Files.size(p)});
    }
    int processed = 0;
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      // 1. 索引有、文件系统无 → 清理；指纹不同 → 删旧记录待重处理
      for (FileInfo info : index.allFiles()) {
        long[] fp = fs.get(info.path());
        if (fp == null) {
          index.deleteByPath(info.path());
          processed++;
        } else if (fp[0] != info.mtime() || fp[1] != info.size()) {
          index.deleteByPath(info.path()); // 下面的 process 会重新提取入库
        }
      }
      // 2. 文件系统有、索引无 → 入库（回滚抑制清单内的跳过）
      Set<String> suppressed = RollbackSuppress.load(repoRoot);
      for (Path p : walkFiles()) {
        String rel = FileProcessor.posix(repoRoot.relativize(p));
        if (suppressed.contains(rel)) {
          continue; // 回滚还原的文件不自动入库
        }
        if (index.findByPath(rel).isEmpty()) {
          processor.process(p, tree, batchId, index);
          processed++;
        }
      }
    }
    return processed;
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
