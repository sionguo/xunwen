package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.extract.ContentExtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColdStartSamplerTest {

  @Test
  void samplesFilesSkippingXunwenAndHidden(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("a.txt"), "内容A足够长通过文本层判断的字符串");
    Files.createDirectories(root.resolve("子目录"));
    Files.writeString(root.resolve("子目录/b.txt"), "内容B足够长通过文本层判断的字符串");
    Files.writeString(root.resolve(".hidden"), "隐藏");
    Files.createDirectories(root.resolve(".xunwen"));
    Files.writeString(root.resolve(".xunwen/secret.txt"), "内部文件");
    Files.createDirectories(root.resolve(".index")); // 隐藏目录内的缓存文件同样要跳过
    Files.writeString(root.resolve(".index/app.db"), "索引缓存");
    Files.createDirectories(root.resolve("待分类"));
    Files.writeString(root.resolve("待分类/低置信.txt"), "待分类文件不应采样");

    var samples =
        ColdStartSampler.sample(
            root,
            file -> new ContentExtractor.Extracted(java.nio.file.Files.readString(file), true),
            20);
    assertEquals(2, samples.size());
    // Files.walk 顺序不保证「根文件先于子目录」，按集合断言
    assertTrue(samples.stream().anyMatch(s -> "a.txt".equals(s.fileName())));
    assertTrue(samples.stream().anyMatch(s -> "b.txt".equals(s.fileName())));
  }

  @Test
  void respectsLimit(@TempDir Path root) throws Exception {
    for (int i = 0; i < 30; i++) {
      Files.writeString(root.resolve("f" + i + ".txt"), "内容" + i + "足够长通过文本层判断");
    }
    assertEquals(
        5,
        ColdStartSampler.sample(root, file -> new ContentExtractor.Extracted("x", true), 5).size());
  }
}
