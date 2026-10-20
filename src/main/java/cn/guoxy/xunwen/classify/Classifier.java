package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class Classifier {
  public record Outcome(
      Action action,
      String categoryPath,
      double confidence,
      String proposedCategory,
      String proposalReason) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int PREVIEW_CHARS = 3000;

  private final LlmClient llm;
  private final GlobalConfig cfg;

  public Classifier(LlmClient llm, GlobalConfig cfg) {
    this.llm = llm;
    this.cfg = cfg;
  }

  public Outcome classify(
      String fileName, String contentPreview, boolean hasTextLayer, CategoryTree tree) {
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
        return toOutcome(node, hasTextLayer, tree);
      } catch (Exception parseFailure) {
        // 两次均失败则走循环外兜底
      }
    }
    return new Outcome(Action.UNKNOWN, "", 0.0, "", "");
  }

  private Outcome toOutcome(JsonNode node, boolean hasTextLayer, CategoryTree tree) {
    double self = node.path("self").asDouble(0.0);
    List<double[]> scored = new ArrayList<>(); // [score]
    JsonNode cands = node.path("candidates");
    for (JsonNode c : cands) {
      scored.add(new double[] {c.path("score").asDouble(0.0)});
    }
    double margin = scored.size() >= 2 ? clamp(scored.get(0)[0] - scored.get(1)[0]) : 1.0;
    double confidence = self * (0.5 + 0.5 * margin);
    if (!hasTextLayer) {
      confidence = Math.min(confidence, cfg.pendingThreshold());
    }
    String category = node.path("category").asString("").strip();
    boolean inTree = !category.isEmpty() && tree.find(category).isPresent();
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
