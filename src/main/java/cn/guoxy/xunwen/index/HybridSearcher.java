package cn.guoxy.xunwen.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
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
      String highlight,
      double score) {}

  private static final int RRF_K = 60;
  private static final int CANDIDATES = 100; // topK*2 候选上限

  private HybridSearcher() {}

  private record Acc(
      double score, String repo, String path, String name, String category, String content) {}

  public static List<Hit> search(
      LinkedHashMap<String, RepoIndex> indices, String query, float[] queryVec, int topK) {
    Map<String, Acc> acc = new HashMap<>();
    Map<String, Query> kwQueries = new HashMap<>();

    try (SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer()) {
      for (var entry : indices.entrySet()) {
        RepoIndex idx = entry.getValue();
        List<StoredDoc> knnDocs = idx.storedOf(idx.searchKnn(queryVec, CANDIDATES).scoreDocs);
        ScoreDoc[] kwHits = idx.searchKeyword(query, CANDIDATES);
        List<StoredDoc> kwDocs = idx.storedOf(kwHits);
        Query kwQuery =
            new org.apache.lucene.queryparser.classic.MultiFieldQueryParser(
                    new String[] {RepoIndex.F_NAME, RepoIndex.F_CONTENT}, analyzer)
                .parse(query);
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
        out.add(new Hit(a.repo(), a.path(), a.name(), a.category(), hl, a.score()));
      }
      return out;
    } catch (Exception e) {
      throw new IllegalStateException("Hybrid search failed: " + query, e);
    }
  }

  private static void accumulate(Map<String, Acc> acc, StoredDoc d, String repoName, int rank) {
    String key = repoName + "\u0000" + d.path();
    double inc = 1.0 / (RRF_K + rank);
    Acc old = acc.get(key);
    if (old == null) {
      acc.put(key, new Acc(inc, repoName, d.path(), d.name(), d.categoryPath(), d.content()));
    } else {
      acc.put(
          key,
          new Acc(
              old.score() + inc,
              old.repo(),
              old.path(),
              old.name(),
              old.category(),
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
