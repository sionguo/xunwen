package cn.guoxy.xunwen.ui;

/** 索引状态 → 中文标签（所有状态可见：UI 展示统一走这里）。 */
final class StatusLabels {
  private StatusLabels() {}

  static String of(String status) {
    return switch (status == null ? "" : status) {
      case "INDEXED" -> "已归档";
      case "PENDING" -> "待审";
      case "UNKNOWN" -> "未知";
      case "ALREADY_INDEXED" -> "已在库";
      case "DUPLICATE" -> "重复跳过";
      case "FAILED" -> "失败";
      default -> status == null ? "" : status;
    };
  }
}
