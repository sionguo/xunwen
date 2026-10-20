package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.classify.Classifier.Outcome;
import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CategoryProposer {
  public record Proposal(String name, String reason, int fileCount) {}

  static final double SIMILAR_THRESHOLD = 0.85;

  private final EmbeddingClient embed;

  public CategoryProposer(EmbeddingClient embed) {
    this.embed = embed;
  }

  public List<Proposal> aggregate(List<Outcome> unknownOutcomes, CategoryTree tree) {
    // 1. 同名合并
    Map<String, List<Outcome>> byName = new LinkedHashMap<>();
    for (Outcome o : unknownOutcomes) {
      if (!o.proposedCategory().isBlank()) {
        byName.computeIfAbsent(o.proposedCategory(), _ -> new ArrayList<>()).add(o);
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
      kept.add(
          new Proposal(
              entry.getKey(),
              entry.getValue().getFirst().proposalReason(),
              entry.getValue().size()));
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
          merged.set(i, new Proposal(m.name(), m.reason(), m.fileCount() + p.fileCount()));
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
