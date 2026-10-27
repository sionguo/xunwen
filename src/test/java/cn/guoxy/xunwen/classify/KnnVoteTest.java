package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.guoxy.xunwen.index.RepoIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnnVoteTest {

  private static RepoIndex.Neighbor n(String cat, double sim) {
    return new RepoIndex.Neighbor(cat, (float) sim);
  }

  @Test
  void strongAgreementAutoMoves() {
    Classifier.Outcome out =
        KnnVote.vote(List.of(n("工作文档", 0.95), n("工作文档", 0.92), n("工作文档", 0.9)), 0.85, 0.5);
    assertEquals(Action.AUTO_MOVE, out.action());
    assertEquals("工作文档", out.categoryPath());
    assertEquals(0.92, out.confidence(), 0.01);
    assertEquals("", out.proposedCategory());
  }

  @Test
  void fewNeighborsNeverAutoMove() {
    // 2 票一致但低于 MIN_NEIGHBORS=3：不出 AUTO_MOVE，置信度过待审线 → PENDING
    Classifier.Outcome out = KnnVote.vote(List.of(n("工作文档", 0.95), n("工作文档", 0.9)), 0.85, 0.5);
    assertEquals(Action.PENDING, out.action());
    assertEquals("工作文档", out.categoryPath());
  }

  @Test
  void disagreementGoesToPending() {
    // 多数派 3 票但平均相似度 0.6：不达强票线，置信度 0.6 ≥ 待审线 → PENDING
    Classifier.Outcome out =
        KnnVote.vote(
            List.of(n("工作文档", 0.6), n("工作文档", 0.6), n("工作文档", 0.6), n("个人", 0.9), n("个人", 0.9)),
            0.85,
            0.5);
    assertEquals(Action.PENDING, out.action());
    assertEquals("工作文档", out.categoryPath());
  }

  @Test
  void lowConfidenceGoesToUnknown() {
    Classifier.Outcome out =
        KnnVote.vote(List.of(n("个人", 0.4), n("个人", 0.3), n("个人", 0.35)), 0.85, 0.5);
    assertEquals(Action.UNKNOWN, out.action());
  }

  @Test
  void emptyNeighborsYieldNull() {
    assertNull(KnnVote.vote(List.of(), 0.85, 0.5));
  }
}
