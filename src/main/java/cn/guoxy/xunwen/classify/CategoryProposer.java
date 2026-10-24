package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.index.StoredDoc;
import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 新类别提议聚合（FR-6）：输入待审页读出的 UNKNOWN 文档（提议已在入库时写入索引， 无需重新调 LLM），同名合并 → 与现有类别相似拒绝 → 提议间相似聚合。 */
public final class CategoryProposer {
  /** 提议：名称、理由、关联文件数与文件相对路径（批准后可定位归位）。 */
  public record Proposal(String name, String reason, int fileCount, List<String> files) {

    public Proposal {
      files = files == null ? List.of() : List.copyOf(files);
    }
  }

  static final double SIMILAR_THRESHOLD = 0.85;

  private final EmbeddingClient embed;

  public CategoryProposer(EmbeddingClient embed) {
    this.embed = embed;
  }

  public List<Proposal> aggregate(List<StoredDoc> unknownDocs, CategoryTree tree) {
    // 1. 同名合并（关联文件路径随组携带）
    Map<String, List<StoredDoc>> byName = new LinkedHashMap<>();
    for (StoredDoc d : unknownDocs) {
      if (!d.proposed().isBlank()) {
        byName.computeIfAbsent(d.proposed(), _ -> new ArrayList<>()).add(d);
      }
    }
    // 2. 相似拒绝
    Map<String, float[]> existing = new LinkedHashMap<>();
    for (String p : tree.flattenPaths()) {
      existing.put(p, embed.embed(p));
    }
    List<Proposal> kept = new ArrayList<>();
    for (var entry : byName.entrySet()) {
      float[] vec = embed.embed(entry.getKey());
      boolean similarToExisting =
          existing.values().stream().anyMatch(v -> cosine(vec, v) > SIMILAR_THRESHOLD);
      if (similarToExisting) {
        continue;
      }
      List<StoredDoc> docs = entry.getValue();
      kept.add(
          new Proposal(
              entry.getKey(),
              docs.getFirst().reason(),
              docs.size(),
              docs.stream().map(StoredDoc::path).toList()));
    }
    // 3. 提议间聚合（贪心：与已保留项相似则并入）
    List<Proposal> merged = new ArrayList<>();
    List<float[]> mergedVecs = new ArrayList<>();
    for (Proposal p : kept) {
      float[] vec = embed.embed(p.name());
      boolean mergedIntoExisting = false;
      for (int i = 0; i < merged.size(); i++) {
        if (cosine(vec, mergedVecs.get(i)) > SIMILAR_THRESHOLD) {
          Proposal m = merged.get(i);
          List<String> files = new ArrayList<>(m.files());
          files.addAll(p.files());
          merged.set(i, new Proposal(m.name(), m.reason(), m.fileCount() + p.fileCount(), files));
          mergedIntoExisting = true;
          break;
        }
      }
      if (!mergedIntoExisting) {
        merged.add(p);
        mergedVecs.add(vec);
      }
    }
    return merged.stream().sorted((a, b) -> Integer.compare(b.fileCount(), a.fileCount())).toList();
  }

  public static double cosine(float[] a, float[] b) {
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < a.length && i < b.length; i++) {
      dot += a[i] * b[i];
      na += (double) a[i] * a[i];
      nb += (double) b[i] * b[i];
    }
    return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
  }
}
