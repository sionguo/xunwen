package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeProposerTest {

  @Test
  void parsesLlmTreeJson() {
    String json =
        """
        {"categories":[
          {"name":"工作文档","description":"工作相关","children":[
            {"name":"报告","description":"各类报告"}]},
          {"name":"其他","description":"兜底","children":[]}]}
        """;
    CategoryTree tree = new TreeProposer(msgs -> json).propose(List.of());
    assertEquals(List.of("工作文档", "工作文档/报告", "其他"), tree.flattenPaths());
  }

  @Test
  void retriesOnceThenSucceeds() {
    int[] calls = {0};
    LlmClient bad =
        msgs -> {
          calls[0]++;
          return calls[0] == 1 ? "oops" : "{\"categories\":[]}"; // 第二次成功即不抛
        };
    assertEquals(List.of(), new TreeProposer(bad).propose(List.of()).flattenPaths());
  }

  @Test
  void bothAttemptsFailThrows() {
    LlmClient alwaysBad = msgs -> "nope";
    assertThrows(IllegalStateException.class, () -> new TreeProposer(alwaysBad).propose(List.of()));
  }
}
