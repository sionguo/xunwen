package cn.guoxy.xunwen.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

/** Fx.rootMessage：错误弹窗人话化——弹根因消息而非异常 toString。 */
class FxTest {

  @Test
  void rootMessageUnwrapsToRootCauseMessage() {
    assertEquals("Connection refused", Fx.rootMessage(new ConnectException("Connection refused")));
    var wrapped = new UncheckedIOException("调用向量化端点失败", new ConnectException("Connection refused"));
    assertEquals("Connection refused", Fx.rootMessage(wrapped)); // 多层包装取根因
  }

  @Test
  void rootMessageFallsBackToSimpleClassNameWhenMessageBlank() {
    assertEquals("IOException", Fx.rootMessage(new IOException()));
    assertEquals("IOException", Fx.rootMessage(new IOException("  ")));
    assertEquals(
        "ConnectException",
        Fx.rootMessage(new RuntimeException("包装", new ConnectException()))); // 根因无消息 → 类名
  }
}
