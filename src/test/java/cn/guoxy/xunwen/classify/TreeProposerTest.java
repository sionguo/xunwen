package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeProposerTest {

  @Test
  void parsesFlatCategoryJson() {
    String json =
        """
        {"categories":[
          {"name":"白皮书","description":"产品白皮书"},
          {"name":"技术设计","description":"设计文档"},
          {"name":"其他","description":"兜底"}]}
        """;
    CategoryTree tree = new TreeProposer(_ -> json).propose(List.of());
    assertEquals(List.of("白皮书", "技术设计", "其他"), tree.flattenPaths());
  }

  @Test
  void toleratesUnexpectedChildren() {
    // LLM 不守一级约束多给了 children：解析不炸，子类照收（用户批准时可人工删减）
    String json =
        """
        {"categories":[
          {"name":"工作文档","description":"工作相关","children":[
            {"name":"报告","description":"各类报告"}]}]}
        """;
    CategoryTree tree = new TreeProposer(_ -> json).propose(List.of());
    assertEquals(List.of("工作文档", "工作文档/报告", "其他"), tree.flattenPaths());
  }

  @Test
  void appendsMissingFallbackCategory() {
    // 模型无视「必须包含其他」指令时（4b 常见），代码补兜底而非依赖 prompt 遵从性
    String json =
        """
        {"categories":[{"name":"财务制度","description":"报销与付款"},{"name":"技术架构","description":"系统设计"}]}
        """;
    List<String> paths = new TreeProposer(_ -> json).propose(List.of()).flattenPaths();
    assertEquals(List.of("财务制度", "技术架构", "其他"), paths);
  }

  @Test
  void retriesOnceThenSucceeds() {
    int[] calls = {0};
    LlmClient bad =
        _ -> {
          calls[0]++;
          return calls[0] == 1 ? "oops" : "{\"categories\":[]}"; // 第二次成功即不抛
        };
    assertEquals(List.of("其他"), new TreeProposer(bad).propose(List.of()).flattenPaths());
  }

  @Test
  void bothAttemptsFailThrows() {
    LlmClient alwaysBad = _ -> "nope";
    assertThrows(IllegalStateException.class, () -> new TreeProposer(alwaysBad).propose(List.of()));
  }
}
