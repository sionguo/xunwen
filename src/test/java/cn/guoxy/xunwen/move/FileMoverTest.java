package cn.guoxy.xunwen.move;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.move.FileMover.MoveResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMoverTest {

  @Test
  void movesFileAndCleansEmptyDirs(@TempDir Path root) throws Exception {
    Path src = Files.createDirectories(root.resolve("旧目录/更深")).resolve("a.txt");
    Files.writeString(src, "内容甲");
    var mover = new FileMover(root);
    var out = mover.move(src, root.resolve("工作文档"));
    assertEquals(MoveResult.MOVED, out.result());
    assertTrue(Files.exists(root.resolve("工作文档/a.txt")));
    assertFalse(Files.exists(root.resolve("旧目录"))); // 空壳目录已清理
  }

  @Test
  void sameNameDifferentContentGetsSuffix(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("工作文档"));
    Files.writeString(root.resolve("工作文档/发票.pdf"), "旧内容");
    Path src = root.resolve("发票.pdf");
    Files.writeString(src, "新内容");
    var out = new FileMover(root).move(src, root.resolve("工作文档"));
    assertEquals(MoveResult.RENAMED, out.result());
    assertTrue(Files.exists(root.resolve("工作文档/发票 (2).pdf")));
  }

  @Test
  void identicalDuplicateIsSkipped(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("工作文档"));
    Files.writeString(root.resolve("工作文档/发票.pdf"), "相同内容");
    Path src = root.resolve("发票.pdf");
    Files.writeString(src, "相同内容");
    var out = new FileMover(root).move(src, root.resolve("工作文档"));
    assertEquals(MoveResult.DUPLICATE_SKIPPED, out.result());
    assertTrue(Files.exists(src)); // 原处保留
  }
}
