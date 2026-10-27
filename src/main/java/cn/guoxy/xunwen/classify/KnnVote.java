package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.index.RepoIndex;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * kNN 最近邻投票分类（ADR 0003）：邻居为索引中已归档文件，多数票定类别、平均相似度当置信度， 走与 LLM 相同的阈值分流。分层用法见 FileProcessor：强票直通（跳过
 * LLM），弱票仅在 LLM 不可达时兜底。
 */
public final class KnnVote {
  /** 出 AUTO_MOVE 的最低门槛：邻居数、一致占比、平均相似度；低于门槛的票只作待审/兜底信号。 */
  static final int MIN_NEIGHBORS = 3;

  static final double MIN_AGREEMENT = 0.75;
  static final double MIN_SIMILARITY = 0.7;

  private KnnVote() {}

  /**
   * 投票：top 类别 + 平均相似度为置信度。无邻居返回 null；「强票」（邻居够多且一致、相似度过线） 且置信度达自动阈值才 AUTO_MOVE，否则按置信度落 PENDING /
   * UNKNOWN。
   */
  public static Classifier.Outcome vote(
      List<RepoIndex.Neighbor> neighbors, double autoThreshold, double pendingThreshold) {
    if (neighbors.isEmpty()) {
      return null;
    }
    Map<String, Double> simSum = new HashMap<>();
    Map<String, Integer> votes = new HashMap<>();
    for (RepoIndex.Neighbor n : neighbors) {
      simSum.merge(n.categoryPath(), (double) n.similarity(), Double::sum);
      votes.merge(n.categoryPath(), 1, Integer::sum);
    }
    String top = null;
    int topVotes = 0;
    for (var e : votes.entrySet()) {
      if (top == null || e.getValue() > topVotes) {
        top = e.getKey();
        topVotes = e.getValue();
      }
    }
    double confidence = simSum.get(top) / topVotes;
    boolean strong =
        topVotes >= MIN_NEIGHBORS
            && topVotes >= neighbors.size() * MIN_AGREEMENT
            && confidence >= MIN_SIMILARITY;
    Action action =
        strong && confidence >= autoThreshold
            ? Action.AUTO_MOVE
            : confidence >= pendingThreshold ? Action.PENDING : Action.UNKNOWN;
    String reason =
        "kNN 邻居投票 "
            + topVotes
            + "/"
            + neighbors.size()
            + " 一致，平均相似度 "
            + String.format("%.2f", confidence);
    return new Classifier.Outcome(action, top, confidence, "", reason);
  }
}
