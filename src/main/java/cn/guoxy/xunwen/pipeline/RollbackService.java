package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.move.FileMover;
import cn.guoxy.xunwen.move.OperationLog;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次回滚（FR-4）：读操作日志按批次逆序还原移动（dest→src），同时清掉移动后的索引条目—— 回滚后的文件回到批前「未处理」状态，下次 sync/重排重新归类。 回滚动作本身以
 * kind=ROLLBACK 留痕（仅供审计，operationLog.batch 只认 MOVE，不会被再次回滚）。
 */
public final class RollbackService {
  /** 一个可回滚批次：batchId、首条时间、可读类型、移动条数。 */
  public record Batch(String batchId, long ts, String kind, int moves) {}

  private final Path repoRoot;

  public RollbackService(Path repoRoot) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
  }

  /** 全部批次（时间倒序）与各自移动条数，供总览页展示。 */
  public List<Batch> batches() throws IOException {
    Map<String, Batch> byId = new LinkedHashMap<>();
    for (OperationLog.Op op : log().all()) {
      if (!op.kind().equals("MOVE")) {
        continue;
      }
      Batch old = byId.get(op.batchId());
      byId.put(
          op.batchId(),
          old == null
              ? new Batch(op.batchId(), op.ts(), kindOf(op.batchId()), 1)
              : new Batch(old.batchId(), old.ts(), old.kind(), old.moves() + 1));
    }
    List<Batch> out = new ArrayList<>(byId.values());
    out.sort((a, b) -> Long.compare(b.ts(), a.ts()));
    return out;
  }

  /** 整批逆序回滚，返回实际还原的文件数。 */
  public int rollbackBatch(String batchId) throws IOException {
    return rollback(log().batch(batchId), "rollback-" + System.currentTimeMillis());
  }

  /** 单条撤销，返回 1（目标已消失则 0）。 */
  public int undo(OperationLog.Op op) throws IOException {
    if (!op.kind().equals("MOVE")) {
      return 0;
    }
    return rollback(List.of(op), "undo-" + System.currentTimeMillis());
  }

  private int rollback(List<OperationLog.Op> moves, String rollbackBatchId) throws IOException {
    if (moves.isEmpty()) {
      return 0;
    }
    FileMover mover = new FileMover(repoRoot);
    OperationLog log = log();
    int restored = 0;
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot))) {
      List<OperationLog.Op> reversed = new ArrayList<>(moves);
      Collections.reverse(reversed); // 逆序：后移的先回，避免目录占用与重名连锁
      for (OperationLog.Op op : reversed) {
        Path dest = Path.of(op.dest());
        Path src = Path.of(op.src());
        if (!Files.exists(dest)) {
          continue; // 目标已被人工处理：跳过该条，其余继续
        }
        Files.createDirectories(src.getParent());
        FileMover.MoveOutcome moved = mover.move(dest, src.getParent());
        String finalPath = moved.dest().toString();
        if (moved.result() == FileMover.MoveResult.DUPLICATE_SKIPPED) {
          // 原位置已有同内容副本：只保留原位置一份
          Files.delete(dest);
          finalPath = src.toString();
        }
        log.append(
            new OperationLog.Op(
                rollbackBatchId, System.currentTimeMillis(), "ROLLBACK", op.dest(), finalPath));
        index.deleteByPath(FileProcessor.posix(repoRoot.relativize(dest)));
        restored++;
      }
    }
    return restored;
  }

  /** batchId 前缀 → 可读类型；未知前缀原样展示。 */
  public static String kindOf(String batchId) {
    String prefix = batchId.contains("-") ? batchId.substring(0, batchId.indexOf('-')) : batchId;
    return switch (prefix) {
      case "reshuffle" -> "重排";
      case "ingest" -> "增量入库";
      case "sync" -> "打开同步";
      case "approve" -> "人工归位";
      case "recat" -> "类别重分类";
      case "reclassify" -> "新类别归位";
      case "rollback" -> "回滚";
      case "undo" -> "单条撤销";
      default -> prefix;
    };
  }

  private OperationLog log() {
    return new OperationLog(RepoPaths.operationsJsonl(repoRoot));
  }
}
