package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 冷启动采样：抽文件内容给 TreeProposer 归纳类别树（FR-6）。 */
public final class ColdStartSampler {
  private ColdStartSampler() {}

  public static List<TreeProposer.Sample> sample(
      Path repoRoot, ContentExtractor extractor, int limit) throws IOException {
    Path root = repoRoot.toAbsolutePath().normalize();
    Path xunwen = RepoPaths.xunwenDir(root);
    Path pending = RepoPaths.pendingDir(root);
    List<TreeProposer.Sample> out = new ArrayList<>();
    try (var walk = Files.walk(root)) {
      for (Path p :
          walk.filter(Files::isRegularFile)
              .filter(p -> !p.startsWith(xunwen) && !p.startsWith(pending))
              .filter(
                  p -> {
                    String n = p.getFileName().toString();
                    return !n.startsWith(".") && !n.startsWith("~$");
                  })
              .limit(limit)
              .toList()) {
        ContentExtractor.Extracted ex = extractor.extract(p);
        if (ex.hasTextLayer()) {
          out.add(new TreeProposer.Sample(p.getFileName().toString(), ex.text()));
        }
      }
    }
    return out;
  }
}
