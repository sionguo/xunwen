package cn.guoxy.xunwen.extract;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TikaExtractorTest {

  @Test
  void extractsPlainText(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("a.txt");
    Files.writeString(f, "这是一份关于季度销售总结的文档，包含详细的数字与分析。", StandardCharsets.UTF_8);
    var out = new TikaExtractor().extract(f);
    assertTrue(out.hasTextLayer());
    assertTrue(out.text().contains("季度销售"));
  }

  @Test
  void marksShortTextAsNoTextLayer(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("empty.txt");
    Files.writeString(f, "");
    var out = new TikaExtractor().extract(f);
    assertFalse(out.hasTextLayer());
  }

  @Test
  void truncatesToMaxChars(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("big.txt");
    Files.writeString(f, "字".repeat(50_000));
    var out = new TikaExtractor(10_000).extract(f);
    assertEquals(10_000, out.text().length());
  }

  @Test
  void closesAndRebuildsShared(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("b.txt");
    Files.writeString(f, "这是一份关于年度审计报告的文档，包含详尽的核对说明。", StandardCharsets.UTF_8);
    var first = new TikaExtractor().extract(f); // 确保 shared 已创建
    assertTrue(first.hasTextLayer());
    TikaExtractor.closeShared();
    var out = new TikaExtractor().extract(f);
    assertTrue(out.hasTextLayer());
    assertTrue(out.text().contains("年度审计"));
  }
}
