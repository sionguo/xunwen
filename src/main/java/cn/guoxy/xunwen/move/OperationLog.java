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
        // 只容忍崩溃残留的尾部半行：跳过损坏行，不影响审计读取
      }
    }
    return out;
  }

  public List<Op> batch(String batchId) throws IOException {
    return all().stream()
        .filter(op -> op.batchId().equals(batchId) && op.kind().equals("MOVE"))
        .toList();
  }
}
