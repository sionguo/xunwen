package cn.guoxy.xunwen.pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 一批任务的处置统计（所有状态可见）：归档/待审/未知为成功分流，已在库/重复为合理跳过， 失败与清理计数保证问题不被静默吞掉；moved 供监视回环过滤与审计。 */
public final class BatchStats {
  private final String batchId;
  private int indexed;
  private int pending;
  private int unknown;
  private int alreadyIndexed;
  private int duplicates;
  private int failed;
  private int cleaned; // 同步时清理的失效索引条目（文件已删/指纹变化）
  private final List<MoveRecord> moved = new ArrayList<>();

  public BatchStats(String batchId) {
    this.batchId = batchId;
  }

  public BatchStats add(FileProcessor.Result r) {
    if (r == null || r.dest() == null) {
      switch (r == null ? "FAILED" : r.status()) {
        case "ALREADY_INDEXED" -> alreadyIndexed++;
        case "DUPLICATE" -> duplicates++;
        default -> failed++;
      }
      return this;
    }
    switch (r.status()) {
      case "INDEXED" -> indexed++;
      case "PENDING" -> pending++;
      case "UNKNOWN" -> unknown++;
      default -> failed++;
    }
    return this;
  }

  public BatchStats moved(String batchId, Path from, Path to) {
    moved.add(new MoveRecord(batchId, from, to));
    return this;
  }

  public BatchStats cleaned(int n) {
    cleaned += n;
    return this;
  }

  public String batchId() {
    return batchId;
  }

  public int indexed() {
    return indexed;
  }

  public int pending() {
    return pending;
  }

  public int unknown() {
    return unknown;
  }

  public int alreadyIndexed() {
    return alreadyIndexed;
  }

  public int duplicates() {
    return duplicates;
  }

  public int failed() {
    return failed;
  }

  public int cleaned() {
    return cleaned;
  }

  public List<MoveRecord> moved() {
    return List.copyOf(moved);
  }

  /** 成功分流（归档+待审+未知）的文件数。 */
  public int processed() {
    return indexed + pending + unknown;
  }

  /** 本批触碰过的文件总数（含清理的索引条目、跳过与失败）。 */
  public int touched() {
    return processed() + alreadyIndexed + duplicates + failed + cleaned;
  }

  /** 供 UI 弹窗/活动栏展示的一句话摘要，非零项全部列出。 */
  public String summary() {
    StringBuilder sb = new StringBuilder("归档 ").append(indexed);
    sb.append(" · 待审 ").append(pending).append(" · 未知 ").append(unknown);
    if (duplicates > 0) {
      sb.append(" · 重复跳过 ").append(duplicates);
    }
    if (alreadyIndexed > 0) {
      sb.append(" · 已在库 ").append(alreadyIndexed);
    }
    if (cleaned > 0) {
      sb.append(" · 清理 ").append(cleaned);
    }
    if (failed > 0) {
      sb.append(" · 失败 ").append(failed);
    }
    return sb.toString();
  }
}
