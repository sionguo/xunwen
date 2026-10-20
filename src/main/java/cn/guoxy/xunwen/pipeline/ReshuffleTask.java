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
import java.util.List;
import java.util.stream.Stream;

public final class ReshuffleTask {
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

  public String run(Progress progress) throws IOException {
    Path treeYaml = RepoPaths.categoriesYaml(repoRoot);
    if (!Files.exists(treeYaml)) {
      throw new IllegalStateException("先完成冷启动：缺少 " + treeYaml);
    }
    CategoryTree tree = CategoryTree.load(treeYaml);
    String batchId = "reshuffle-" + System.currentTimeMillis();
    FileProcessor processor = new FileProcessor(repoRoot, cfg, llm, extractor);

    List<Path> files = listFiles();
    int done = 0;
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      for (Path file : files) {
        if (progress.cancelled()) {
          break; // 已完成的保留，批次仍可回滚（FR-3/FR-4）
        }
        progress.onProgress(done++, files.size(), FileProcessor.posix(repoRoot.relativize(file)));
        processor.process(file, tree, batchId, index);
      }
    }
    return batchId;
  }

  private List<Path> listFiles() throws IOException {
    Path xunwen = RepoPaths.xunwenDir(repoRoot);
    Path pending = RepoPaths.pendingDir(repoRoot);
    try (Stream<Path> walk = Files.walk(repoRoot)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> !p.startsWith(xunwen) && !p.startsWith(pending))
          .filter(
              p -> {
                String n = p.getFileName().toString();
                return !n.startsWith(".") && !n.startsWith("~$");
              })
          .toList();
    }
  }
}
