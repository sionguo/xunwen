package cn.guoxy.xunwen.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件处置状态（Lucene F_STATUS 字段的持久化协议，横跨 pipeline/index/ui 三个包的唯一 owner）。
 * 前三个值会写入索引；ALREADY_INDEXED/DUPLICATE/FAILED 只出现在批次处置结果与统计，不入索引。 枚举名即存储值：老索引数据零迁移；拼写错误从运行时静默变成编译错误。
 */
public enum FileStatus {
  INDEXED("已归档"),
  PENDING("待审"),
  UNKNOWN("未知"),
  ALREADY_INDEXED("已在库"),
  DUPLICATE("重复跳过"),
  FAILED("失败");

  private static final Logger LOG = LoggerFactory.getLogger(FileStatus.class);

  private final String label;

  FileStatus(String label) {
    this.label = label;
  }

  /** UI 展示标签（原 StatusLabels 的职责）。 */
  public String label() {
    return label;
  }

  /** Lucene 读回容错：未知/缺失值归 UNKNOWN 并留档（宁可归类不崩 UI，不静默透传）。 */
  public static FileStatus fromLucene(String value) {
    if (value == null || value.isEmpty()) {
      return UNKNOWN;
    }
    try {
      return FileStatus.valueOf(value);
    } catch (IllegalArgumentException unknown) {
      LOG.warn("索引中的未知状态值: {}", value);
      return UNKNOWN;
    }
  }
}
