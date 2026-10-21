package cn.guoxy.xunwen.move;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class OperationLog {
  public record Op(String batchId, long ts, String kind, String src, String dest) {}

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path file;

  public OperationLog(Path file) {
    this.file = file;
  }

  public void append(Op op) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        JSON.writeValueAsString(op) + "\n",
        java.nio.charset.StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND);
  }

  public List<Op> all() throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<Op> out = new ArrayList<>();
    for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
      if (line.isBlank()) {
        continue;
      }
      try {
        out.add(JSON.readValue(line, Op.class));
      } catch (JacksonException e) {
        // 只容忍崩溃残留的尾部半行：跳过损坏行，回滚不受影响
      }
    }
    return out;
  }

  public List<Op> batch(String batchId) throws IOException {
    return all().stream()
        .filter(op -> op.batchId().equals(batchId) && op.kind().equals("MOVE"))
        .toList();
  }

  /** 逆序回滚一个批次（FR-4），返回成功还原的条目（src=原路径,dest=类别内落点）。目标已存在的条目跳过。 */
  public List<Op> rollback(String batchId) throws IOException {
    List<Op> ops = batch(batchId);
    List<Op> reverted = new ArrayList<>();
    for (int i = ops.size() - 1; i >= 0; i--) {
      Op op = ops.get(i);
      Path src = Path.of(op.src());
      Path dest = Path.of(op.dest());
      if (!Files.exists(dest)) {
        continue;
      }
      Files.createDirectories(src.getParent());
      if (Files.exists(src)) {
        continue; // 源位已被占用，跳过
      }
      Files.move(dest, src, java.nio.file.StandardCopyOption.ATOMIC_MOVE); // 回滚同样零拷贝
      append(new Op(batchId, System.currentTimeMillis(), "REVERT", op.dest(), op.src()));
      reverted.add(op);
    }
    return reverted;
  }
}
