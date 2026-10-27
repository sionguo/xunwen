package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.index.FileStatus;

public enum Action {
  AUTO_MOVE,
  PENDING,
  UNKNOWN;

  /** 该决策动作对应的落库状态（决策域到持久化域的唯一映射）。 */
  public FileStatus fileStatus() {
    return switch (this) {
      case AUTO_MOVE -> FileStatus.INDEXED;
      case PENDING -> FileStatus.PENDING;
      case UNKNOWN -> FileStatus.UNKNOWN;
    };
  }
}
