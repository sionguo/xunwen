package cn.guoxy.xunwen.extract;

import java.io.IOException;
import java.nio.file.Path;

public interface ContentExtractor {
  Extracted extract(Path file) throws IOException;

  record Extracted(String text, boolean hasTextLayer) {}
}
