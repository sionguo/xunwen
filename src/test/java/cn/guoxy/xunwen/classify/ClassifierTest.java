package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
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
  void narrowMarginDropsToPending() {
    // self=0.9, margin=0.2 → confidence=0.9*(0.5+0.5*0.2)=0.54 → 落在 [0.5, 0.85) → PENDING
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
