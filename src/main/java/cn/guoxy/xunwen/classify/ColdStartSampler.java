package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 冷启动采样：抽文件内容给 TreeProposer 归纳类别树（FR-6）。 */
public final class ColdStartSampler {
  private static final Logger LOG = LoggerFactory.getLogger(ColdStartSampler.class);

  private ColdStartSampler() {}

  public static List<TreeProposer.Sample> sample(
      Path repoRoot, ContentExtractor extractor, int limit) throws IOException {
    Path root = repoRoot.toAbsolutePath().normalize();
    Path xunwen = RepoPaths.xunwenDir(root);
    Path pending = RepoPaths.pendingDir(root);
    List<TreeProposer.Sample> out = new ArrayList<>();
    long startedAt = System.nanoTime();
    List<Path> files;
    try (var walk = Files.walk(root)) {
      files =
          walk.filter(Files::isRegularFile)
              .filter(p -> !p.startsWith(xunwen) && !p.startsWith(pending))
              .filter(
                  p -> {
                    String n = p.getFileName().toString();
                    return !n.startsWith(".") && !n.startsWith("~$");
                  })
              .limit(limit)
              .toList();
      for (int i = 0; i < files.size(); i++) {
        Path p = files.get(i);
        long begin = System.nanoTime();
        ContentExtractor.Extracted ex = extractor.extract(p);
        long ms = Duration.ofNanos(System.nanoTime() - begin).toMillis();
        LOG.info(
            "采样 {}/{}：{}（提取 {} ms，{}）",
            i + 1,
            files.size(),
            p.getFileName(),
            ms,
            ex.hasTextLayer() ? "有文本层" : "无文本层，跳过");
        if (ex.hasTextLayer()) {
          out.add(new TreeProposer.Sample(p.getFileName().toString(), ex.text()));
        }
      }
    }
    LOG.info(
        "采样完成：{} 个样本，总耗时 {} ms",
        out.size(),
        Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    return out;
  }
}
