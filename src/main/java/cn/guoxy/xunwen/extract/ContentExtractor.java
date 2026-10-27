package cn.guoxy.xunwen.extract;

import java.io.IOException;
import java.nio.file.Path;

public interface ContentExtractor {
  Extracted extract(Path file) throws IOException;

  record Extracted(String text, boolean hasTextLayer) {}

  /** 文件扩展名（不含点；无扩展名为空串）。 */
  static String ext(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(dot + 1) : "";
  }
}
