package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class TreeProposer {
  public record Sample(String fileName, String contentPreview) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Logger LOG = LoggerFactory.getLogger(TreeProposer.class);

  private final LlmClient llm;

  public TreeProposer(LlmClient llm) {
    this.llm = llm;
  }

  public CategoryTree propose(List<Sample> samples) {
    long startedAt = System.nanoTime();
    LOG.info("开始调用 LLM 归纳类别树（样本 {} 个）…", samples.size());
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
        根据以下文件样本，归纳出一套中文资料分类体系（一级扁平类别，不要子类别）。
        要求：类别数由语料的自然分布决定，不设硬性数量，通常 5到15 个一级类别便于浏览导航；
        必须包含「其他」作为兜底类别；名称2到6个汉字；禁止「文档」「资料」这类泛化词；
        每个类别配一句description。

        文件样本：
        %s
        严格输出 JSON：
        {"categories":[{"name":"...","description":"..."}]}
        """
            .formatted(sb);
    Exception last = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        JsonNode node = JSON.readTree(llm.chat(List.of(LlmClient.Msg.user(prompt))));
        CategoryTree tree = ensureFallback(parse(node));
        LOG.info(
            "类别树归纳完成：{} 个顶层类别，LLM 耗时 {} ms",
            tree.roots().size(),
            Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        return tree;
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

  /** 兜底类别「其他」是系统语义（对应待分类目录），不依赖模型自觉：缺失时补上。 */
  private static CategoryTree ensureFallback(CategoryTree tree) {
    if (tree.find("其他").isPresent()) {
      return tree;
    }
    List<Category> roots = new ArrayList<>(tree.roots());
    roots.add(new Category("其他", "未能归入以上类别的兜底分类", List.of()));
    return new CategoryTree(roots);
  }
}
