package cn.guoxy.xunwen.ui;

import java.util.ArrayList;
import java.util.List;

/** 把 Lucene 高亮串 <em>x</em> 拆成 (text, emphasized) 段，供搜索页 TextFlow 渲染。 */
public final class Highlighter {
  public record Segment(String text, boolean emphasized) {}

  private Highlighter() {}

  public static List<Segment> parse(String highlighted) {
    List<Segment> out = new ArrayList<>();
    int i = 0;
    while (i < highlighted.length()) {
      int open = highlighted.indexOf("<em>", i);
      if (open < 0) {
        out.add(new Segment(highlighted.substring(i), false));
        break;
      }
      if (open > i) {
        out.add(new Segment(highlighted.substring(i, open), false));
      }
      int close = highlighted.indexOf("</em>", open);
      if (close < 0) {
        out.add(new Segment(highlighted.substring(open), false));
        break;
      }
      out.add(new Segment(highlighted.substring(open + 4, close), true));
      i = close + 5;
    }
    return out;
  }
}
