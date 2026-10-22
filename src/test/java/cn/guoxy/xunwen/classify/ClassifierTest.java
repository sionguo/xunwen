package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassifierTest {

  private static final CategoryTree TREE =
      new CategoryTree(
          List.of(
              new Category("工作文档", "", List.of(new Category("报告", "", List.of()))),
              new Category("其他", "", List.of())));

  private static LlmClient llmReturning(String json) {
    return _ -> json;
  }

  /** 按类别文本返回互斥单位向量：报告→x 轴，工作文档→y 轴，其他→z 轴，避免余弦并列。 */
  private static final EmbeddingClient VEC_BY_NAME =
      new EmbeddingClient() {
        @Override
        public float[] embed(String text) {
          if (text.contains("报告")) {
            return new float[] {1f, 0f, 0f};
          }
          return text.contains("工作文档") ? new float[] {0f, 1f, 0f} : new float[] {0f, 0f, 1f};
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
          return texts.stream().map(this::embed).toList();
        }
      };

  @Test
  void highConfidenceAutoMove() {
    var llm =
        llmReturning(
            """
            {"category":"工作文档/报告","candidates":[
              {"name":"工作文档/报告","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
            """);
    var out = new Classifier(llm, GlobalConfig.defaults()).classify("q4.pdf", "季度报告内容", true, TREE);
    assertEquals(Action.AUTO_MOVE, out.action());
    assertEquals("工作文档/报告", out.categoryPath());
  }

  @Test
  void highSelfWithSmallMarginStillAutoMoves() {
    // self=0.97, margin=0.06 → confidence=0.97*(0.9+0.1*0.06)=0.879 → ≥0.85 → AUTO_MOVE
    // （mimo 类打分风格：候选分普遍偏高且接近，margin 不应把 self 腰斩）
    var llm =
        llmReturning(
            """
            {"category":"工作文档/报告","candidates":[
              {"name":"工作文档/报告","score":0.98},{"name":"其他","score":0.92}],"self":0.97}
            """);
    var out = new Classifier(llm, GlobalConfig.defaults()).classify("q4.pdf", "季度报告内容", true, TREE);
    assertEquals(Action.AUTO_MOVE, out.action());
  }

  @Test
  void narrowMarginDropsToPending() {
    // self=0.9, margin=0.2 → confidence=0.9*(0.9+0.1*0.2)=0.828 → 落在 [0.5, 0.85) → PENDING
    var llm =
        llmReturning(
            """
            {"category":"工作文档/报告","candidates":[
              {"name":"工作文档/报告","score":0.6},{"name":"其他","score":0.4}],"self":0.9}
            """);
    var out = new Classifier(llm, GlobalConfig.defaults()).classify("q4.pdf", "内容", true, TREE);
    assertEquals(Action.PENDING, out.action());
  }

  @Test
  void noTextLayerCapsToPending() {
    var llm =
        llmReturning(
            """
            {"category":"工作文档","candidates":[
              {"name":"工作文档","score":0.99},{"name":"其他","score":0.1}],"self":0.99}
            """);
    var out =
        new Classifier(llm, GlobalConfig.defaults())
            .classify(
                "scan.pdf",
                "",
                false,
                TREE); // 无文本层 → confidence 直接压到 pendingThreshold(0.5) → PENDING
    assertEquals(Action.PENDING, out.action());
  }

  @Test
  void badJsonRetriesThenFallsBackToUnknown() {
    int[] calls = {0};
    LlmClient bad =
        _ -> {
          calls[0]++;
          return calls[0] == 1 ? "不是json" : "{\"category\":\"\",\"candidates\":[],\"self\":0.1}";
        };
    var out = new Classifier(bad, GlobalConfig.defaults()).classify("f.bin", "x", true, TREE);
    assertEquals(Action.UNKNOWN, out.action());
    assertEquals(2, calls[0]);
  }

  @Test
  void transportFailurePropagatesWithoutRetry() {
    int[] calls = {0};
    LlmClient down =
        _ -> {
          calls[0]++;
          throw new IllegalStateException("LLM chat failed");
        };
    var classifier = new Classifier(down, GlobalConfig.defaults());
    assertThrows(IllegalStateException.class, () -> classifier.classify("f.txt", "x", true, TREE));
    assertEquals(1, calls[0]); // 传输失败不重试：LLM 不可达 ≠ 模型说不知道
  }

  @Test
  void vectorDisagreementHalvesConfidence() {
    // LLM 选「工作文档/报告」(conf=0.936)，向量 top1=「其他」→ conf×0.5=0.468 < 0.5 → UNKNOWN
    var llm =
        llmReturning(
            """
            {"category":"工作文档/报告","candidates":[
              {"name":"工作文档/报告","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
            """);
    var vecs = new CategoryVectors(TREE, VEC_BY_NAME);
    var out =
        new Classifier(llm, GlobalConfig.defaults())
            .classify("q4.pdf", "内容", true, TREE, vecs, new float[] {0f, 0f, 1f});
    assertEquals(Action.UNKNOWN, out.action());
  }

  @Test
  void vectorAgreementKeepsConfidence() {
    // 向量 top1 与 LLM 类别一致 → 不钳制，conf=0.936 ≥ 0.85 → AUTO_MOVE
    var llm =
        llmReturning(
            """
            {"category":"工作文档/报告","candidates":[
              {"name":"工作文档/报告","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
            """);
    var vecs = new CategoryVectors(TREE, VEC_BY_NAME);
    var out =
        new Classifier(llm, GlobalConfig.defaults())
            .classify("q4.pdf", "内容", true, TREE, vecs, new float[] {1f, 0f, 0f});
    assertEquals(Action.AUTO_MOVE, out.action());
  }

  @Test
  void vectorUnavailableBypassesClamp() {
    // embed 服务不可用 → 向量路旁路，纯 LLM 判定 → AUTO_MOVE
    var llm =
        llmReturning(
            """
            {"category":"工作文档/报告","candidates":[
              {"name":"工作文档/报告","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
            """);
    var vecs =
        new CategoryVectors(
            TREE,
            new EmbeddingClient() {
              @Override
              public float[] embed(String text) {
                throw new IllegalStateException("embed 服务不可达");
              }

              @Override
              public List<float[]> embedAll(List<String> texts) {
                throw new IllegalStateException("embed 服务不可达");
              }
            });
    var out =
        new Classifier(llm, GlobalConfig.defaults())
            .classify("q4.pdf", "内容", true, TREE, vecs, new float[] {1f, 0f, 0f});
    assertEquals(Action.AUTO_MOVE, out.action());
  }

  @Test
  void proposalCarriedThrough() {
    var llm =
        llmReturning(
            """
            {"category":"","candidates":[],"self":0.2,
             "proposed":"发票凭证","reason":"多个餐饮发票文件"}
            """);
    var out = new Classifier(llm, GlobalConfig.defaults()).classify("f.pdf", "发票", true, TREE);
    assertEquals(Action.UNKNOWN, out.action());
    assertEquals("发票凭证", out.proposedCategory());
    assertEquals("多个餐饮发票文件", out.proposalReason());
  }
}
