package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.Doc;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.index.StoredDoc;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.move.FileMover;
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

  /** 批量人工归位项：待审文件 → 目标类别。 */
  public record PendingApproval(Path file, String categoryPath) {}

  /** 待审文件人工归位：移动 → 索引更新为 INDEXED。 */
  public void approve(Path pendingFile, String categoryPath) throws IOException {
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      approveInto(index, new FileMover(repoRoot), new PendingApproval(pendingFile, categoryPath));
    }
  }

  /** 批量人工归位：单个索引会话内完成全部移动（N 文件不再开关 N 次索引）。 返回成功归位数。 */
  public int approveBatch(List<PendingApproval> items) throws IOException {
    FileMover mover = new FileMover(repoRoot);
    int count = 0;
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      for (PendingApproval item : items) {
        if (approveInto(index, mover, item)) {
          count++;
        }
      }
    }
    return count;
  }

  private boolean approveInto(RepoIndex index, FileMover mover, PendingApproval item)
      throws IOException {
    Path pendingFile = item.file();
    String categoryPath = item.categoryPath();
    String oldRel =
        FileProcessor.posix(repoRoot.relativize(pendingFile.toAbsolutePath().normalize()));
    StoredDoc old = index.findByPath(oldRel).orElse(null);
    FileMover.MoveOutcome moved = mover.move(pendingFile, repoRoot.resolve(categoryPath));
    index.deleteByPath(oldRel);
    index.upsert(
        new Doc(
            FileProcessor.posix(repoRoot.relativize(moved.dest())),
            moved.dest().getFileName().toString(),
            ContentExtractor.ext(moved.dest().getFileName().toString()),
            moved.hash(),
            Files.getLastModifiedTime(moved.dest()).toMillis(),
            Files.size(moved.dest()),
            "INDEXED",
            categoryPath,
            old == null ? "" : old.content(),
            FileProcessor.neutralVector(cfg),
            old == null ? "" : old.proposed(),
            old == null ? "" : old.reason(),
            old == null ? 0.0 : old.confidence(),
            categoryPath)); // 候选即最终归类
    return true;
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

  /** 删除类别 → save → 其下文件物理移回待分类并重分类。返回处理文件数。 路径可以是「一级」（删整个子树）或「一级/二级」（只删该二级，保留一级与其他子类）。 */
  public int removeCategory(CategoryTree tree, String categoryPath) throws IOException {
    String[] parts = categoryPath.split("/");
    String top = parts[0];
    List<Category> roots = new ArrayList<>();
    boolean removed = false;
    for (Category r : tree.roots()) {
      if (!r.name().equals(top)) {
        roots.add(r);
        continue;
      }
      if (parts.length == 1) {
        removed = true; // 删一级：整个子树出树
        continue;
      }
      // 删二级：仅从该一级的 children 中摘除目标
      String child = parts[1];
      List<Category> kids = new ArrayList<>();
      for (Category k : r.children()) {
        if (!k.name().equals(child)) {
          kids.add(k);
        } else {
          removed = true;
        }
      }
      roots.add(new Category(r.name(), r.description(), kids));
    }
    if (!removed) {
      throw new IllegalArgumentException("类别不存在：" + categoryPath);
    }
    CategoryTree updated = new CategoryTree(roots);
    updated.save(RepoPaths.categoriesYaml(repoRoot));

    FileMover mover = new FileMover(repoRoot);
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
          mover.move(file, RepoPaths.pendingDir(repoRoot));
        }
        index.deleteByPath(d.path());
        Path pendingFile = RepoPaths.pendingDir(repoRoot).resolve(Path.of(d.path()).getFileName());
        if (Files.exists(pendingFile)) {
          index.deleteByPath(FileProcessor.posix(repoRoot.relativize(pendingFile)));
          new FileProcessor(repoRoot, cfg, llm, extractor).process(pendingFile, updated, index);
          count++;
        }
      }
    }
    return count;
  }

  private void reclassifyAllUnknowns(CategoryTree tree) throws IOException {
    FileProcessor processor = new FileProcessor(repoRoot, cfg, llm, extractor);
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      for (StoredDoc d : index.byStatus("UNKNOWN", 10_000)) {
        index.deleteByPath(d.path()); // 先删索引，让 process 重走完整流程
        Path file = repoRoot.resolve(d.path());
        if (Files.exists(file)) {
          processor.process(file, tree, index);
        }
      }
    }
  }

  private static List<Category> append(List<Category> children, Category node) {
    List<Category> out = new ArrayList<>(children);
    out.add(node);
    return out;
  }
}
