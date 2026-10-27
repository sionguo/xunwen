package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.classify.Action;
import cn.guoxy.xunwen.classify.CategoryVectors;
import cn.guoxy.xunwen.classify.Classifier;
import cn.guoxy.xunwen.classify.KnnVote;
import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.Doc;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.move.FileMover;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单文件处理，两阶段管道（FR-1/2/3）：
 *
 * <ul>
 *   <li>{@link #prepare}：提取 + 分类 + 向量（耗时在解析与 LLM 调用，无写操作，可并发）；
 *   <li>{@link #commit}：移动 + 入库（独占 RepoIndex 单写者，串行）。
 * </ul>
 *
 * 串行 {@link #process} = prepare + commit，语义不变。
 */
public final class FileProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(FileProcessor.class);
  private static final int EMBED_CHARS = 4000;

  /** 阶段一产物：分类完成、待落库。 */
  public record Prepared(Path src, String rawText, float[] fileVec, Classifier.Outcome outcome) {}

  /**
   * 单文件处置结果。dest 非空 = 成功分流（status 为索引状态 INDEXED/PENDING/UNKNOWN）； dest 为空时 status 说明原因：
   * ALREADY_INDEXED（中断续跑）/ DUPLICATE（重复副本留在原处）/ FAILED（提取或移动失败，下轮重试）。
   */
  public record Result(Path dest, String status) {}

  private final Path repoRoot;
  private final GlobalConfig cfg;
  private final Classifier classifier;
  private final EmbeddingClient embedder;
  private final ContentExtractor extractor;
  private final FileMover mover;
  // 类目向量表按 tree 实例缓存：树变更（批准/删除类别）产生新实例即自动重建；
  // warmVectors 预构建后并发 prepare 只读，无竞态
  private CategoryTree vectorTree;
  private CategoryVectors vectors;

  public FileProcessor(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
    this.cfg = cfg;
    this.classifier = new Classifier(llm, cfg);
    this.embedder =
        llm instanceof EmbeddingClient ec
            ? ec
            : new EmbeddingClient() {
              @Override
              public float[] embed(String text) {
                return neutralVector(cfg);
              }

              @Override
              public List<float[]> embedAll(List<String> texts) {
                return texts.stream().map(_ -> neutralVector(cfg)).toList();
              }
            };
    this.extractor = extractor;
    this.mover = new FileMover(this.repoRoot);
  }

  /** 预构建类目向量表：并发 prepare 前调用一次，消除惰性初始化竞态。 */
  public void warmVectors(CategoryTree tree) {
    vectorsFor(tree);
  }

  /** 阶段一：提取 + 分类；已入库（中断续跑）返回 null。只读索引，可并发。 */
  public Prepared prepare(Path file, CategoryTree tree, RepoIndex index) throws IOException {
    return prepare(file, tree, index, false);
  }

  /** 阶段一：提取 + 分类。full=true 为全量重排：跳过「已入库跳过」检查，已归档/待分类文件也重新分类 （结果不变则原地刷新索引，变了则移动）。 */
  public Prepared prepare(Path file, CategoryTree tree, RepoIndex index, boolean full)
      throws IOException {
    Path src = file.toAbsolutePath().normalize();
    if (!full && index.findByPath(posix(repoRoot.relativize(src))).isPresent()) {
      return null;
    }
    ContentExtractor.Extracted ex = extractor.extract(file);
    String rawText = ex.text();
    String embedText = rawText.length() > EMBED_CHARS ? rawText.substring(0, EMBED_CHARS) : rawText;
    float[] fileVec = embedOrNull(embedText);
    List<RepoIndex.Neighbor> neighbors =
        fileVec == null ? List.of() : index.knnNeighbors(fileVec, KNN_K);
    Classifier.Outcome outcome = classifyWithFallback(src, ex, tree, fileVec, neighbors);
    return new Prepared(src, rawText, fileVec, outcome);
  }

  /** 阶段二：移动 + 入库；返回落点，重复/失败时 dest 为 null 且 status 说明原因。 */
  public Result commit(Prepared p, RepoIndex index) {
    if (p == null) {
      return new Result(null, "ALREADY_INDEXED");
    }
    String name = p.src().getFileName().toString();
    try {
      Classifier.Outcome outcome = p.outcome();
      Path destDir =
          outcome.action() == Action.AUTO_MOVE
              ? repoRoot.resolve(outcome.categoryPath())
              : RepoPaths.pendingDir(repoRoot);
      Path actual;
      String hash;
      if (destDir.equals(p.src().getParent())) {
        // 已在目标目录（如待分类内未入库文件）：跳过移动直接入库，消除 dest==src 自重复循环
        actual = p.src();
        hash = "";
      } else {
        FileMover.MoveOutcome moved = mover.move(p.src(), destDir);
        if (moved.result() == FileMover.MoveResult.DUPLICATE_SKIPPED) {
          return new Result(null, "DUPLICATE"); // 重复副本留在原处（FR-5），不入索引
        }
        actual = moved.dest();
        hash = moved.hash();
      }

      String status =
          switch (outcome.action()) {
            case AUTO_MOVE -> "INDEXED";
            case PENDING -> "PENDING";
            case UNKNOWN -> "UNKNOWN";
          };
      // 已入库文件的重新归位（全量重排）：移动后旧位置条目需删除，否则成为孤儿
      // （增量流程文件未入库，delete 为无害 no-op）
      String oldRel = posix(repoRoot.relativize(p.src()));
      String newRel = posix(repoRoot.relativize(actual));
      if (!oldRel.equals(newRel)) {
        index.deleteByPath(oldRel);
      }
      index.upsert(
          new Doc(
              posix(repoRoot.relativize(actual)),
              actual.getFileName().toString(),
              ContentExtractor.ext(name),
              hash,
              Files.getLastModifiedTime(actual).toMillis(),
              Files.size(actual),
              status,
              outcome.action() == Action.AUTO_MOVE ? outcome.categoryPath() : "",
              p.rawText(),
              p.fileVec() == null ? neutralVector(cfg) : p.fileVec(),
              outcome.proposedCategory(),
              outcome.proposalReason(),
              outcome.confidence(),
              outcome.categoryPath())); // 候选类别：待审页预选用（无论是否自动归档）
      return new Result(actual, status);
    } catch (IOException | IllegalStateException e) {
      // 移动失败（Windows 占用等）或 LLM 传输失败：本批跳过，下轮再处理，不让整批任务崩
      LOG.warn("跳过无法处理的文件: {}", p.src(), e);
    }
    return new Result(null, "FAILED");
  }

  /** 串行完整处理：prepare + commit。 */
  public Result process(Path file, CategoryTree tree, RepoIndex index) throws IOException {
    return commit(prepare(file, tree, index), index);
  }

  /** 文件向量；embed 失败返回 null（向量路旁路），索引入库时再以中性向量兜底。 */
  private boolean embedWarned; // 每个任务实例只告警一次，批量不刷屏

  private float[] embedOrNull(String text) {
    try {
      return embedder.embed(text);
    } catch (RuntimeException e) { // Jackson 3 unchecked / HTTP 服务不可达
      if (!embedWarned) {
        embedWarned = true;
        LOG.warn("embedding 调用失败，本任务后续文件将以无向量方式入库: {}", String.valueOf(e.getMessage()));
      }
      return null;
    }
  }

  /** 单文件 kNN 邻居查询数；超采样与过滤在 RepoIndex 侧。 */
  static final int KNN_K = 8;

  /**
   * 分层分类（ADR 0003）：kNN 强票（邻居多且一致、相似度过线）直通归档，跳过 LLM——全量入库的 LLM 调用量从每文件一次降为「难例才调」；否则走 LLM，传输失败（LLM
   * 不可达）时以 kNN 票据兜底 （弱票走待审/待分类，人工后续救援），无票则维持原语义（本批失败计数）。
   */
  private Classifier.Outcome classifyWithFallback(
      Path src,
      ContentExtractor.Extracted ex,
      CategoryTree tree,
      float[] fileVec,
      List<RepoIndex.Neighbor> neighbors) {
    Classifier.Outcome knn = KnnVote.vote(neighbors, cfg.autoThreshold(), cfg.pendingThreshold());
    if (knn != null && knn.action() == Action.AUTO_MOVE) {
      LOG.debug("kNN 直通: {} → {}", src, knn.categoryPath());
      return knn;
    }
    try {
      return classifier.classify(
          src.getFileName().toString(),
          ex.text(),
          ex.hasTextLayer(),
          tree,
          vectorsFor(tree),
          fileVec);
    } catch (IllegalStateException llmDown) {
      if (knn != null) {
        LOG.warn("LLM 不可达，kNN 投票兜底: {} → {}", src, knn.categoryPath(), llmDown);
        return knn;
      }
      throw llmDown;
    }
  }

  /** 类目向量表按 tree 实例惰性重建；树变更（批准/删除类别）产生新实例即自动刷新。同步防并发 prepare 的 check-then-act。 */
  private synchronized CategoryVectors vectorsFor(CategoryTree tree) {
    if (vectors == null || vectorTree != tree) {
      vectorTree = tree;
      vectors = new CategoryVectors(tree, embedder);
    }
    return vectors;
  }

  /** Lucene COSINE 拒绝全零向量：单位向量作中性兜底，保证入库不阻断（ApprovalService 共用）。 */
  static float[] neutralVector(GlobalConfig cfg) {
    float[] v = new float[cfg.vectorDim()];
    v[0] = 1f;
    return v;
  }

  static String posix(Path p) {
    return p.toString().replace('\\', '/');
  }
}
