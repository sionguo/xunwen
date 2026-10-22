package cn.guoxy.xunwen.move;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationLogTest {

  @Test
  void appendAndQueryBatch(@TempDir Path dir) throws Exception {
    var log = new OperationLog(dir.resolve("ops.jsonl"));
    log.append(new OperationLog.Op("b1", 1L, "MOVE", "a", "b"));
    log.append(new OperationLog.Op("b2", 2L, "MOVE", "c", "d"));
    assertEquals(2, log.all().size());
    assertEquals(1, log.batch("b1").size());
    assertEquals("a", log.batch("b1").getFirst().src());
  }

  @Test
  void allSkipsTornTrailingLine(@TempDir Path dir) throws Exception {
    Path jsonl = dir.resolve("ops.jsonl");
    var log = new OperationLog(jsonl);
    log.append(new OperationLog.Op("b1", 1L, "MOVE", "a", "b"));
    Files.writeString(
        jsonl,
        "{\"batchId\":\"b2\",\"ts\":2,\"kind\":\"MOVE\",\"src\":\"c\"",
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND); // 崩溃残留的尾部半行
    var ops = log.all();
    assertEquals(1, ops.size());
    assertEquals("b1", ops.getFirst().batchId());
  }
}
