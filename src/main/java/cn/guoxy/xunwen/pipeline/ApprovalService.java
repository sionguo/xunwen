package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.Doc;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.index.StoredDoc;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.move.FileMover;
import cn.guoxy.xunwen.move.OperationLog;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 人工审批与类别树变更后的归位（FR-2/FR-6）。 */
public final class ApprovalService {
  private final Path repoRoot;
  private final GlobalConfig cfg;
  private final LlmClient llm;
  private final ContentExtractor extractor;

  public ApprovalService(
      Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
    this.cfg = cfg;
    this.llm = llm;
    this.extractor = extractor;
  }

  /** 待审文件人工归位：移动 → 记日志 → 索引更新为 INDEXED。返回批次号。 */
  public String approve(Path pendingFile, String categoryPath) throws IOException {
    String batchId = "approve-" + System.currentTimeMillis();
    FileMover mover = new FileMover(repoRoot);
    OperationLog log = new OperationLog(RepoPaths.operationsJsonl(repoRoot));
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      String oldRel =
          FileProcessor.posix(repoRoot.relativize(pendingFile.toAbsolutePath().normalize()));
      StoredDoc old = index.findByPath(oldRel).orElse(null);
      FileMover.MoveOutcome moved = mover.move(pendingFile, repoRoot.resolve(categoryPath));
      log.append(
          new OperationLog.Op(
              batchId,
              System.currentTimeMillis(),
              "MOVE",
              pendingFile.toString(),
              moved.dest().toString()));
      index.deleteByPath(oldRel);
      index.upsert(
          new Doc(
              FileProcessor.posix(repoRoot.relativize(moved.dest())),
              moved.dest().getFileName().toString(),
              FileProcessor.ext(moved.dest().getFileName().toString()),
              moved.hash(),
              Files.getLastModifiedTime(moved.dest()).toMillis(),
              Files.size(moved.dest()),
              "INDEXED",
              categoryPath,
              old == null ? "" : old.content(),
              neutralVector()));
    }
    return batchId;
  }

  /** 新类别入树（顶层或挂 parentName 下）→ save → 全部 UNKNOWN 重分类归位。返回更新后的树。 */
  public CategoryTree approveProposal(
      CategoryTree tree, String name, String description, boolean asTopLevel, String parentName)
      throws IOException {
    Category node = new Category(name, description, List.of());
    List<Category> roots = new ArrayList<>(tree.roots());
    if (asTopLevel || parentName == null) {
      roots.add(node);
    } else {
      List<Category> rebuilt = new ArrayList<>();
      for (Category r : roots) {
        rebuilt.add(
            r.name().equals(parentName)
                ? new Category(r.name(), r.description(), append(r.children(), node))
                : r);
      }
      roots = rebuilt;
    }
    CategoryTree updated = new CategoryTree(roots);
    updated.save(RepoPaths.categoriesYaml(repoRoot));
    reclassifyAllUnknowns(updated);
    return updated;
  }

  /** 删除类别（含子树）→ save → 其下文件物理移回待分类并重分类。返回处理文件数。 */
  public int removeCategory(CategoryTree tree, String categoryPath) throws IOException {
    List<Category> roots = new ArrayList<>();
    String top = categoryPath.split("/")[0];
    for (Category r : tree.roots()) {
      if (!r.name().equals(top)) {
        roots.add(r);
      }
    }
    CategoryTree updated = new CategoryTree(roots);
    updated.save(RepoPaths.categoriesYaml(repoRoot));

    String batchId = "recat-" + System.currentTimeMillis();
    FileMover mover = new FileMover(repoRoot);
    OperationLog log = new OperationLog(RepoPaths.operationsJsonl(repoRoot));
    int count = 0;
    List<StoredDoc> affected = new ArrayList<>();
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      for (StoredDoc d : index.all()) {
        if (d.categoryPath().equals(categoryPath)
            || d.categoryPath().startsWith(categoryPath + "/")) {
          affected.add(d);
        }
      }
      for (StoredDoc d : affected) {
        Path file = repoRoot.resolve(d.path());
        if (Files.exists(file)) {
          var moved = mover.move(file, RepoPaths.pendingDir(repoRoot));
          log.append(
              new OperationLog.Op(
                  batchId,
                  System.currentTimeMillis(),
                  "MOVE",
                  file.toString(),
                  moved.dest().toString()));
        }
        index.deleteByPath(d.path());
        Path pendingFile = RepoPaths.pendingDir(repoRoot).resolve(Path.of(d.path()).getFileName());
        if (Files.exists(pendingFile)) {
          index.deleteByPath(FileProcessor.posix(repoRoot.relativize(pendingFile)));
          new FileProcessor(repoRoot, cfg, llm, extractor)
              .process(pendingFile, updated, batchId, index);
          count++;
        }
      }
    }
    return count;
  }

  private void reclassifyAllUnknowns(CategoryTree tree) throws IOException {
    String batchId = "reclassify-" + System.currentTimeMillis();
    FileProcessor processor = new FileProcessor(repoRoot, cfg, llm, extractor);
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      for (StoredDoc d : index.byStatus("UNKNOWN", 10_000)) {
        index.deleteByPath(d.path()); // 先删索引，让 process 重走完整流程
        Path file = repoRoot.resolve(d.path());
        if (Files.exists(file)) {
          processor.process(file, tree, batchId, index);
        }
      }
    }
  }

  /** 回滚批次：移回文件 → 删落点索引条目 → 原路径记入抑制清单（sync 不再自动移走，重排时重新确认）。返回还原文件数。 */
  public int rollbackBatch(String batchId) throws IOException {
    OperationLog log = new OperationLog(RepoPaths.operationsJsonl(repoRoot));
    List<OperationLog.Op> reverted = log.rollback(batchId);
    if (reverted.isEmpty()) {
      return 0;
    }
    List<String> restored = new ArrayList<>();
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      for (OperationLog.Op op : reverted) {
        index.deleteByPath(FileProcessor.posix(repoRoot.relativize(Path.of(op.dest()))));
        restored.add(FileProcessor.posix(repoRoot.relativize(Path.of(op.src()))));
      }
    }
    RollbackSuppress.addAll(repoRoot, restored);
    return restored.size();
  }

  private static List<Category> append(List<Category> children, Category node) {
    List<Category> out = new ArrayList<>(children);
    out.add(node);
    return out;
  }

  /** Lucene COSINE 拒绝全零向量：单位向量作中性兜底（与 FileProcessor 一致）。 */
  private float[] neutralVector() {
    float[] v = new float[cfg.vectorDim()];
    v[0] = 1f;
    return v;
  }
}
