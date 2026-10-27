package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class Classifier {
  private static final Logger LOG = LoggerFactory.getLogger(Classifier.class);

  public record Outcome(
      Action action,
      String categoryPath,
      double confidence,
      String proposedCategory,
      String proposalReason) {}

  /** decide 的文件证据：文件名、内容、文本层标记、文件向量与 kNN 邻居（无则空）。 */
  public record Evidence(
      String fileName,
      String content,
      boolean hasTextLayer,
      float[] fileVec,
      List<RepoIndex.Neighbor> neighbors) {

    /** 测试便利：无向量无邻居的纯文本证据。 */
    public static Evidence plain(String fileName, String content, boolean hasTextLayer) {
      return new Evidence(fileName, content, hasTextLayer, null, List.of());
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int PREVIEW_CHARS = 3000;

  private final LlmClient llm;
  private final GlobalConfig cfg;

  public Classifier(LlmClient llm, GlobalConfig cfg) {
    this.llm = llm;
    this.cfg = cfg;
  }

  /**
   * 分类决策（分层，ADR 0003）：kNN 强票（邻居多且一致、相似度过线）直通归档，跳过 LLM—— 全量入库的 LLM 调用量从每文件一次降为「难例才调」； 否则走
   * LLM，传输失败（LLM 不可达）时以 kNN 票据兜底（弱票走待审/待分类，人工后续救援）， 无票则维持原语义（异常上抛，本批失败计数）。
   */
  public Outcome decide(CategoryTree tree, CategoryVectors vectors, Evidence e) {
    Classifier.Outcome knn =
        KnnVote.vote(e.neighbors(), cfg.autoThreshold(), cfg.pendingThreshold());
    if (knn != null && knn.action() == Action.AUTO_MOVE) {
      LOG.debug("kNN 直通: {} → {}", e.fileName(), knn.categoryPath());
      return knn;
    }
    try {
      return classify(e.fileName(), e.content(), e.hasTextLayer(), tree, vectors, e.fileVec());
    } catch (IllegalStateException llmDown) {
      if (knn != null) {
        LOG.warn("LLM 不可达，kNN 投票兜底: {} → {}", e.fileName(), knn.categoryPath(), llmDown);
        return knn;
      }
      throw llmDown;
    }
  }

  /**
   * LLM 单策略（decide 的内部零件）：LLM 给出类别与置信度后，若向量路的最相似类别与 LLM 结论不一致， 置信度减半压进待审区（两路独立证据一致才自动移动）。vectors 或
   * fileVec 为 null 时旁路。 传输异常（LLM 不可达）原样上抛，由 decide 决定兜底；解析失败重试两次后 UNKNOWN。
   */
  Outcome classify(
      String fileName,
      String contentPreview,
      boolean hasTextLayer,
      CategoryTree tree,
      CategoryVectors vectors,
      float[] fileVec) {
    String preview =
        contentPreview.length() > PREVIEW_CHARS
            ? contentPreview.substring(0, PREVIEW_CHARS)
            : contentPreview;
    String prompt = buildPrompt(fileName, preview, hasTextLayer, tree);
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        JsonNode node =
            JSON.readTree(
                llm.chat(List.of(LlmClient.Msg.system(SYSTEM_RULES), LlmClient.Msg.user(prompt))));
        return toOutcome(node, hasTextLayer, tree, vectors, fileVec);
      } catch (JacksonException parseFailure) {
        // 两次均解析失败则走循环外兜底；传输异常（LLM 不可达）不在此捕获，避免误判 UNKNOWN
      }
    }
    return new Outcome(Action.UNKNOWN, "", 0.0, "", "");
  }

  private Outcome toOutcome(
      JsonNode node,
      boolean hasTextLayer,
      CategoryTree tree,
      CategoryVectors vectors,
      float[] fileVec) {
    double self = node.path("self").asDouble(0.0);
    List<double[]> scored = new ArrayList<>(); // [score]
    JsonNode cands = node.path("candidates");
    for (JsonNode c : cands) {
      scored.add(new double[] {c.path("score").asDouble(0.0)});
    }
    double margin = scored.size() >= 2 ? clamp(scored.get(0)[0] - scored.get(1)[0]) : 1.0;
    // self 主导，候选差距（margin）只做微调：mimo 等模型候选分普遍偏高且接近，
    // 重罚 margin 会把高把握结果压进待审区（confidence = self × (0.9 + 0.1 × margin)）
    double confidence = self * (0.9 + 0.1 * margin);
    if (!hasTextLayer) {
      confidence = Math.min(confidence, cfg.pendingThreshold());
    }
    String category = node.path("category").asString("").strip();
    boolean inTree = !category.isEmpty() && tree.find(category).isPresent();
    // 向量一致性钳制：向量路 top1 与 LLM 类别不一致 → 置信度减半压回待审区。
    // 仅校验树内类别；向量路不可用（empty/null）时旁路，不误伤。
    if (inTree) {
      var top =
          vectors == null ? Optional.<CategoryVectors.Scored>empty() : vectors.topMatch(fileVec);
      if (top.isPresent() && !top.get().categoryPath().equals(category)) {
        confidence *= 0.5;
      }
    }
    Action action =
        inTree && confidence >= cfg.autoThreshold()
            ? Action.AUTO_MOVE
            : inTree && confidence >= cfg.pendingThreshold() ? Action.PENDING : Action.UNKNOWN;
    return new Outcome(
        action,
        inTree ? category : "",
        confidence,
        node.path("proposed").asString("").strip(),
        node.path("reason").asString("").strip());
  }

  private static double clamp(double v) {
    return Math.clamp(v, 0.0, 1.0);
  }

  private static String buildPrompt(
      String fileName, String preview, boolean hasTextLayer, CategoryTree tree) {
    return """
    请将文件归入给定类别体系（最多两级），只能从列表中选择，不得发明新名称。
    类别列表：
    %s
    文件名：%s
    内容可提取：%s
    内容预览：
    %s
    严格输出 JSON：{"category":"类别路径或空","candidates":[{"name":"类别路径","score":0到1}],"self":0到1,"proposed":"若列表都不合适，提议一个新的2到6字中文类别名，否则空串","reason":"提议理由或空串"}
    candidates 按分数降序给出2到3个候选。\
    """
        .formatted(String.join("\n", tree.flattenPaths()), fileName, hasTextLayer, preview);
  }

  private static final String SYSTEM_RULES = "你是文件分类助手。只输出 JSON 对象，不输出任何其他文本。";
}
