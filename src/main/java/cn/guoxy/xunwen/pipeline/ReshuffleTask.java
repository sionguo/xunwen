package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ReshuffleTask {
  /** 重排模式：增量只处理未入库文件；全量对全部文件（含待分类）重新分类归位。 */
  public enum Mode {
    INCREMENTAL,
    FULL
  }

  private final Path repoRoot;
  private final RepoSession session;

  public ReshuffleTask(RepoSession s) {
    this.repoRoot = s.repo().toAbsolutePath().normalize();
    this.session = s;
  }

  /** 增量重排（默认）：处理所有未入库文件。 */
  public BatchStats run(BatchRunner.Progress progress) throws IOException {
    return run(progress, Mode.INCREMENTAL);
  }

  /** 按模式重排一批文件，返回处置统计。两阶段并发管道委托 BatchRunner（close 即批次提交边界）。 */
  public BatchStats run(BatchRunner.Progress progress, Mode mode) throws IOException {
    Path treeYaml = RepoPaths.categoriesYaml(repoRoot);
    if (!Files.exists(treeYaml)) {
      throw new IllegalStateException("先完成冷启动：缺少 " + treeYaml);
    }
    CategoryTree tree = CategoryTree.load(treeYaml);
    FileProcessor processor = new FileProcessor(session);
    BatchStats stats = new BatchStats();
    List<Path> files = RepoPaths.listRepoFiles(repoRoot, mode == Mode.FULL);
    try (RepoIndex index = RepoIndex.openWritable(RepoPaths.indexDir(repoRoot))) {
      processor.warmVectors(tree);
      new BatchRunner(session.config())
          .run(
              files,
              files.size(),
              stats,
              BatchRunner.relativizeTo(repoRoot, progress),
              file -> processor.prepare(file, tree, index, mode == Mode.FULL),
              (file, p) -> processor.commit(p, index));
    }
    return stats;
  }
}
