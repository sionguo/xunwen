package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 类别路径 → 向量（各级名称与描述拼接后 embed），供分类结果做向量一致性校验。 */
public final class CategoryVectors {
  public record Scored(String categoryPath, float cosine) {}

  /** 余弦差小于该值视为并列（浮点误差内无法区分）。 */
  private static final float EPS = 1e-4f;

  private final Map<String, float[]> byPath;

  public CategoryVectors(CategoryTree tree, EmbeddingClient embedder) {
    Map<String, float[]> m;
    try {
      Map<String, String> texts = new LinkedHashMap<>();
      for (Category root : tree.roots()) {
        texts.put(root.name(), text(root.name(), root.description()));
        for (Category child : root.children()) {
          texts.put(
              root.name() + "/" + child.name(),
              text(root.name(), root.description(), child.name(), child.description()));
        }
      }
      // 构建类目向量表；embed 服务不可达时置空，分类退化为纯 LLM 判定（钳制旁路）
      m = embedAll(texts, embedder);
    } catch (RuntimeException e) {
      m = Map.of();
    }
    this.byPath = m; // 保持插入序：余弦并列时 topMatch 取先插入的类别，结果确定
  }

  /**
   * 文件向量与最接近的类别。向量表不可用、文件向量为 null、或 top1 与 top2 余弦并列 （向量路无区分度，如兜底 embedder 全返回同一向量）时返回
   * empty，分类不被误钳制。
   */
  public Optional<Scored> topMatch(float[] fileVec) {
    if (fileVec == null || byPath.isEmpty()) {
      return Optional.empty();
    }
    String best = null;
    float bestCos = -1f;
    float secondCos = -1f;
    for (var e : byPath.entrySet()) {
      float cos = cosine(fileVec, e.getValue());
      if (cos > bestCos) {
        secondCos = bestCos;
        bestCos = cos;
        best = e.getKey();
      } else if (cos > secondCos) {
        secondCos = cos;
      }
    }
    if (best == null || bestCos - secondCos < EPS) {
      return Optional.empty(); // 并列：无法判定 top1，旁路
    }
    return Optional.of(new Scored(best, bestCos));
  }

  private static Map<String, float[]> embedAll(
      Map<String, String> texts, EmbeddingClient embedder) {
    var vectors = embedder.embedAll(List.copyOf(texts.values()));
    var paths = List.copyOf(texts.keySet());
    Map<String, float[]> byPath = new LinkedHashMap<>();
    for (int i = 0; i < paths.size(); i++) {
      byPath.put(paths.get(i), vectors.get(i));
    }
    return byPath;
  }

  private static String text(String... parts) {
    return String.join(" ", parts);
  }

  private static float cosine(float[] a, float[] b) {
    if (a == null || b == null || a.length != b.length) {
      return -1f;
    }
    float dot = 0f;
    float na = 0f;
    float nb = 0f;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    if (na == 0f || nb == 0f) {
      return -1f;
    }
    return dot / (float) (Math.sqrt(na) * Math.sqrt(nb));
  }
}
