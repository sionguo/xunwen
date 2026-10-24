package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.index.StoredDoc;
import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CategoryProposerTest {

  /** 确定性 embedding：词 -> 向量 */
  private static EmbeddingClient vec(Map<String, float[]> table) {
    return new EmbeddingClient() {
      @Override
      public float[] embed(String text) {
        return table.getOrDefault(text, new float[] {0, 0, 0, 1});
      }

      @Override
      public List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
      }
    };
  }

  /** 待审页读出的 UNKNOWN 文档：提议/理由已在入库时写入索引。 */
  private static StoredDoc unknown(String proposed) {
    return new StoredDoc(
        "待分类/" + proposed + ".txt", proposed + ".txt", "", "UNKNOWN", "内容", proposed, "理由", 0.1);
  }

  @Test
  void mergesIdenticalProposals() {
    var tree = new CategoryTree(List.of(new Category("工作文档", "", List.of())));
    Map<String, float[]> table = new HashMap<>();
    table.put("发票凭证", new float[] {1, 0, 0, 0});
    table.put("工作文档", new float[] {0, 1, 0, 0});
    var proposer = new CategoryProposer(vec(table));
    var out = proposer.aggregate(List.of(unknown("发票凭证"), unknown("发票凭证")), tree);
    assertEquals(1, out.size());
    assertEquals(2, out.getFirst().fileCount());
    assertEquals(2, out.getFirst().files().size()); // 关联文件随组携带
  }

  @Test
  void rejectsProposalSimilarToExistingCategory() {
    var tree = new CategoryTree(List.of(new Category("工作文档", "", List.of())));
    Map<String, float[]> table = new HashMap<>();
    table.put("工作资料", new float[] {0.99f, 0.1f, 0, 0}); // 与 工作文档 近
    table.put("工作文档", new float[] {1, 0, 0, 0});
    var out = new CategoryProposer(vec(table)).aggregate(List.of(unknown("工作资料")), tree);
    assertTrue(out.isEmpty()); // 相似拒绝（FR-6）
  }

  @Test
  void aggregatesSemanticallyCloseProposals() {
    var tree = new CategoryTree(List.of(new Category("工作文档", "", List.of())));
    Map<String, float[]> table = new HashMap<>();
    table.put("发票凭证", new float[] {1, 0, 0, 0});
    table.put("票据发票", new float[] {0.95f, 0.3f, 0, 0}); // cos>0.85
    table.put("工作文档", new float[] {0, 1, 0, 0});
    var out =
        new CategoryProposer(vec(table)).aggregate(List.of(unknown("发票凭证"), unknown("票据发票")), tree);
    assertEquals(1, out.size());
    assertEquals("发票凭证", out.getFirst().name());
    assertEquals(2, out.getFirst().fileCount());
    assertEquals(2, out.getFirst().files().size()); // 聚合后两个文件都在提议下
  }
}
