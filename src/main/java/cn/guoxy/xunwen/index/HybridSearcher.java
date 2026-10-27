package cn.guoxy.xunwen.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;

/** BM25 + KNN 的 RRF 混合检索（FR-8）。 */
public final class HybridSearcher {
  public record Hit(
      String repoName,
      String path,
      String name,
      String categoryPath,
      FileStatus status,
      String highlight,
      double score) {}

  private static final int RRF_K = 60;
  private static final int CANDIDATES = 100; // topK*2 候选上限

  private HybridSearcher() {}

  private record Acc(
      double score,
      String repo,
      String path,
      String name,
      String category,
      FileStatus status,
      String content) {}

  public static List<Hit> search(
      LinkedHashMap<String, RepoIndex> indices, String query, float[] queryVec, int topK) {
    Map<String, Acc> acc = new HashMap<>();
    Map<String, Query> kwQueries = new HashMap<>();

    try (SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer()) {
      // 零向量无方向信号，cosine 相似度无意义（Lucene 对零向量直接断言失败）：
      // 此时跳过 KNN 路，仅关键词路出结果
      boolean hasVecSignal = !isAllZero(queryVec);
      // 关键词路解析一次（同字段同分词器，各仓库复用同一 Query；高亮复用同一实例）
      Query kwQuery =
          new MultiFieldQueryParser(new String[] {RepoIndex.F_NAME, RepoIndex.F_CONTENT}, analyzer)
              .parse(query);
      for (var entry : indices.entrySet()) {
        RepoIndex idx = entry.getValue();
        List<StoredDoc> knnDocs =
            hasVecSignal ? idx.storedOf(idx.searchKnn(queryVec, CANDIDATES).scoreDocs) : List.of();
        List<StoredDoc> kwDocs = idx.storedOf(idx.searchDocs(kwQuery, CANDIDATES));
        kwQueries.put(entry.getKey(), kwQuery);

        int rank = 1;
        for (StoredDoc d : knnDocs) {
          accumulate(acc, d, entry.getKey(), rank++);
        }
        rank = 1;
        for (StoredDoc d : kwDocs) {
          accumulate(acc, d, entry.getKey(), rank++);
        }
      }

      List<Acc> sorted =
          acc.values().stream()
              .sorted(Comparator.comparingDouble(Acc::score).reversed())
              .limit(topK)
              .toList();

      List<Hit> out = new ArrayList<>();
      for (Acc a : sorted) {
        String hl = highlight(analyzer, kwQueries.get(a.repo()), a.content());
        out.add(new Hit(a.repo(), a.path(), a.name(), a.category(), a.status(), hl, a.score()));
      }
      return out;
    } catch (Exception e) {
      throw new IllegalStateException("Hybrid search failed: " + query, e);
    }
  }

  private static boolean isAllZero(float[] v) {
    for (float x : v) {
      if (x != 0f) {
        return false;
      }
    }
    return true;
  }

  private static void accumulate(Map<String, Acc> acc, StoredDoc d, String repoName, int rank) {
    String key = repoName + "\u0000" + d.path();
    double inc = 1.0 / (RRF_K + rank);
    Acc old = acc.get(key);
    if (old == null) {
      acc.put(
          key,
          new Acc(inc, repoName, d.path(), d.name(), d.categoryPath(), d.status(), d.content()));
    } else {
      acc.put(
          key,
          new Acc(
              old.score() + inc,
              old.repo(),
              old.path(),
              old.name(),
              old.category(),
              old.status(),
              old.content()));
    }
  }

  private static String highlight(SmartChineseAnalyzer analyzer, Query kwQuery, String content)
      throws java.io.IOException, InvalidTokenOffsetsException {
    if (content == null || content.isBlank()) {
      return "";
    }
    try (TokenStream ts = analyzer.tokenStream(RepoIndex.F_CONTENT, content)) {
      Highlighter hl =
          new Highlighter(new SimpleHTMLFormatter("<em>", "</em>"), new QueryScorer(kwQuery));
      String frag = hl.getBestFragment(ts, content);
      return frag != null ? frag : content.substring(0, Math.min(80, content.length()));
    }
  }
}
