package cn.guoxy.xunwen.extract;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.tika.config.TimeoutLimits;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.pipes.fork.PipesForkParser;
import org.apache.tika.pipes.fork.PipesForkParserConfig;
import org.apache.tika.pipes.fork.PipesForkResult;

/** Tika 提取，解析在 forked JVM 中执行（进程隔离：崩溃/超时/无限循环不影响主进程）。 */
public final class TikaExtractor implements ContentExtractor {
  private static final int MIN_TEXT_LAYER = 20;
  private static final long PARSE_TIMEOUT_MS = 60_000;
  private static volatile PipesForkParser shared; // 创建一次复用（官方：thread-safe，每实例一个 fork 池）

  private final int maxChars;

  public TikaExtractor() {
    this(100_000);
  }

  public TikaExtractor(int maxChars) {
    this.maxChars = maxChars;
  }

  @Override
  public Extracted extract(Path file) throws java.io.IOException {
    try {
      PipesForkResult result = shared().parse(file.toAbsolutePath());
      if (!result.isSuccess()) {
        // 进程崩溃（OOM/超时，fork 自动重启）或该文档解析失败 → 文件名兜底（FR-7）
        return new Extracted("", false);
      }
      String text = result.getContent().strip();
      if (text.length() > maxChars) {
        text = text.substring(0, maxChars);
      }
      return new Extracted(text, text.length() >= MIN_TEXT_LAYER);
    } catch (Throwable e) {
      // 与 fork 的通信失败等极端情况
      return new Extracted("", false);
    }
  }

  // 构造器声明 throws IOException/TikaConfigException（jar 实际签名）；extract 的 try 块内调用，失败同样走 ("", false)
  private static PipesForkParser shared() throws IOException, TikaConfigException {
    if (shared == null) {
      synchronized (TikaExtractor.class) {
        if (shared == null) {
          shared =
              new PipesForkParser(
                  new PipesForkParserConfig()
                      .setTimeoutLimits(
                          new TimeoutLimits(
                              TimeoutLimits.DEFAULT_TOTAL_TASK_TIMEOUT_MILLIS, PARSE_TIMEOUT_MS)));
        }
      }
    }
    return shared;
  }
}
