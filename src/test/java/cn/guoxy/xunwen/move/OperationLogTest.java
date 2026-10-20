package cn.guoxy.xunwen.move;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
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
  void rollbackRestoresFilesAndAppendsRevert(@TempDir Path root) throws Exception {
    Path dirA = Files.createDirectories(root.resolve("a"));
    Path dirB = Files.createDirectories(root.resolve("b"));
    Path src = dirA.resolve("f.txt");
    Path dest = dirB.resolve("f.txt");
    Files.writeString(src, "x");
    var mover = new FileMover(root);
    mover.move(src, dirB); // a/f.txt -> b/f.txt（移动本身，日志由调用方记录）

    var log = new OperationLog(root.resolve("ops.jsonl"));
    log.append(
        new OperationLog.Op(
            "b1", System.currentTimeMillis(), "MOVE", src.toString(), dest.toString()));

    log.rollback("b1");
    assertTrue(Files.exists(src));
    assertFalse(Files.exists(dest));
    assertTrue(log.all().stream().anyMatch(op -> op.kind().equals("REVERT")));
  }
}
