package cn.guoxy.xunwen.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class HighlighterTest {

  @Test
  void splitsEmFragments() {
    var segs = Highlighter.parse("本季度<em>销售</em>增长");
    assertEquals(
        List.of(
            new Highlighter.Segment("本季度", false),
            new Highlighter.Segment("销售", true),
            new Highlighter.Segment("增长", false)),
        segs);
  }

  @Test
  void noEmReturnsSinglePlainSegment() {
    assertEquals(List.of(new Highlighter.Segment("纯文本", false)), Highlighter.parse("纯文本"));
  }
}
