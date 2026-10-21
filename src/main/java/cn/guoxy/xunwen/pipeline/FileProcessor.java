package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.classify.Action;
import cn.guoxy.xunwen.classify.Classifier;
import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.Doc;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.move.FileMover;
import cn.guoxy.xunwen.move.OperationLog;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 单文件处理：提取 → 分类 → 置信度分流移动 → 写日志 → 写索引（FR-1/2/3）。 */
public final class FileProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(FileProcessor.class);
  private static final int EMBED_CHARS = 4000;

  private final Path repoRoot;
  private final GlobalConfig cfg;
  private final Classifier classifier;
  private final EmbeddingClient embedder;
  private final ContentExtractor extractor;
  private final FileMover mover;
  private final OperationLog log;

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
                return neutralVector();
              }

              @Override
              public List<float[]> embedAll(List<String> texts) {
                return texts.stream().map(_ -> neutralVector()).toList();
              }
            };
    this.extractor = extractor;
    this.mover = new FileMover(this.repoRoot);
    this.log = new OperationLog(RepoPaths.operationsJsonl(this.repoRoot));
  }

  /** 返回实际落点（含 RENAMED 改名后路径）；已入库/重复跳过/失败返回 null。 */
  public Path process(Path file, CategoryTree tree, String batchId, RepoIndex index) {
    String name = file.getFileName().toString();
    Path src = file.toAbsolutePath().normalize();
    String relPath = posix(repoRoot.relativize(src));
    try {
      if (index.findByPath(relPath).isPresent()) {
        return null; // 已入库（中断续跑）
      }
      ContentExtractor.Extracted ex = extractor.extract(file);
      Classifier.Outcome outcome = classifier.classify(name, ex.text(), ex.hasTextLayer(), tree);

      Path destDir =
          outcome.action() == Action.AUTO_MOVE
              ? repoRoot.resolve(outcome.categoryPath())
              : RepoPaths.pendingDir(repoRoot);
      Path actual;
      String hash;
      if (destDir.equals(src.getParent())) {
        // 已在目标目录（如待分类内未入库文件）：跳过移动与日志直接入库，消除 dest==src 自重复循环
        actual = src;
        hash = "";
      } else {
        FileMover.MoveOutcome moved = mover.move(src, destDir);
        if (moved.result() == FileMover.MoveResult.DUPLICATE_SKIPPED) {
          return null; // 重复副本留在原处（FR-5），不入索引
        }
        actual = moved.dest();
        hash = moved.hash();
        log.append(
            new OperationLog.Op(
                batchId, System.currentTimeMillis(), "MOVE", file.toString(), actual.toString()));
      }

      String status =
          switch (outcome.action()) {
            case AUTO_MOVE -> "INDEXED";
            case PENDING -> "PENDING";
            case UNKNOWN -> "UNKNOWN";
          };
      String text = ex.text();
      String embedText = text.length() > EMBED_CHARS ? text.substring(0, EMBED_CHARS) : text;
      index.upsert(
          new Doc(
              posix(repoRoot.relativize(actual)),
              actual.getFileName().toString(),
              ext(name),
              hash,
              Files.getLastModifiedTime(actual).toMillis(),
              Files.size(actual),
              status,
              outcome.action() == Action.AUTO_MOVE ? outcome.categoryPath() : "",
              text,
              embedSafely(embedText)));
      return actual;
    } catch (IOException | IllegalStateException e) {
      // 移动失败（Windows 占用等）或 LLM 传输失败：本批跳过，下轮再处理，不让整批任务崩
      LOG.warn("跳过无法处理的文件: {}", relPath, e);
    }
    return null;
  }

  private float[] embedSafely(String text) {
    try {
      return embedder.embed(text);
    } catch (RuntimeException e) { // Jackson 3 unchecked / HTTP 异常
      return neutralVector();
    }
  }

  /** Lucene COSINE 拒绝全零向量：单位向量作中性兜底，保证入库不阻断。 */
  private float[] neutralVector() {
    float[] v = new float[cfg.vectorDim()];
    v[0] = 1f;
    return v;
  }

  static String posix(Path p) {
    return p.toString().replace('\\', '/');
  }

  static String ext(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(dot + 1) : "";
  }
}
