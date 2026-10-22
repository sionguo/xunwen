package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryVectorsTest {

  private static final CategoryTree TREE =
      new CategoryTree(
          List.of(
              new Category("工作文档", "", List.of(new Category("报告", "", List.of()))),
              new Category("其他", "", List.of())));

  /** 按类别文本返回固定向量：「报告」→(1,0)，其余 →(0,1)。 */
  private static final EmbeddingClient VEC_BY_NAME =
      new EmbeddingClient() {
        @Override
        public float[] embed(String text) {
          return text.contains("报告") ? new float[] {1f, 0f} : new float[] {0f, 1f};
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
          return texts.stream().map(VEC_BY_NAME::embed).toList();
        }
      };

  @Test
  void topMatchReturnsClosestCategory() {
    var vecs = new CategoryVectors(TREE, VEC_BY_NAME);
    var top = vecs.topMatch(new float[] {0.9f, 0.1f});
    assertTrue(top.isPresent());
    assertEquals("工作文档/报告", top.get().categoryPath());
  }

  @Test
  void tiedCosinesReturnEmptyToBypassClamp() {
    // 所有类别向量与文件向量余弦并列（如兜底 embedder 全返回相同向量）→ 无法区分 → empty 旁路
    EmbeddingClient allSame =
        new EmbeddingClient() {
          @Override
          public float[] embed(String text) {
            return new float[] {1f, 0f};
          }

          @Override
          public List<float[]> embedAll(List<String> texts) {
            return texts.stream().map(_ -> new float[] {1f, 0f}).toList();
          }
        };
    var vecs = new CategoryVectors(TREE, allSame);
    assertTrue(vecs.topMatch(new float[] {1f, 0f}).isEmpty());
  }

  @Test
  void embedFailureDisablesRanking() {
    EmbeddingClient down =
        new EmbeddingClient() {
          @Override
          public float[] embed(String text) {
            throw new IllegalStateException("embed 服务不可达");
          }

          @Override
          public List<float[]> embedAll(List<String> texts) {
            throw new IllegalStateException("embed 服务不可达");
          }
        };
    var vecs = new CategoryVectors(TREE, down);
    assertTrue(vecs.topMatch(new float[] {1f, 0f}).isEmpty());
  }
}
