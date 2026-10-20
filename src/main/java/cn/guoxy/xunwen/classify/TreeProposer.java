package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class TreeProposer {
  public record Sample(String fileName, String contentPreview) {}

  private static final ObjectMapper JSON = new ObjectMapper();

  private final LlmClient llm;

  public TreeProposer(LlmClient llm) {
    this.llm = llm;
  }

  public CategoryTree propose(List<Sample> samples) {
    StringBuilder sb = new StringBuilder();
    for (Sample s : samples) {
      sb.append("文件：").append(s.fileName()).append('\n');
      String preview =
          s.contentPreview().length() > 500
              ? s.contentPreview().substring(0, 500)
              : s.contentPreview();
      sb.append("内容：").append(preview.replace('\n', ' ')).append("\n\n");
    }
    String prompt =
        """
        根据以下文件样本，归纳出一套中文资料分类体系（恰好两级）。
        要求：顶层不超过6个类别；每个类别下不超过6个子类；必须包含「其他」作为兜底顶层类别；
        名称2到6个汉字；每个类别配一句description。

        文件样本：
        %s
        严格输出 JSON：
        {"categories":[{"name":"...","description":"...","children":[{"name":"...","description":"..."}]}]}
        """
            .formatted(sb);
    Exception last = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        JsonNode node = JSON.readTree(llm.chat(List.of(LlmClient.Msg.user(prompt))));
        return parse(node);
      } catch (Exception e) {
        last = e;
      }
    }
    throw new IllegalStateException("类别树生成失败", last);
  }

  private static CategoryTree parse(JsonNode node) {
    List<Category> roots = new ArrayList<>();
    for (JsonNode c : node.path("categories")) {
      List<Category> children = new ArrayList<>();
      for (JsonNode ch : c.path("children")) {
        children.add(
            new Category(ch.path("name").asString(), ch.path("description").asString(), List.of()));
      }
      roots.add(
          new Category(c.path("name").asString(), c.path("description").asString(), children));
    }
    return new CategoryTree(roots);
  }
}
