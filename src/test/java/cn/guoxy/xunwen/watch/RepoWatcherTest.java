package cn.guoxy.xunwen.watch;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoWatcherTest {

  @Test
  void detectsNewStableFile(@TempDir Path root) throws Exception {
    try (RepoWatcher w = new RepoWatcher(root, Duration.ZERO)) {
      w.start();
      Path f = root.resolve("新文件.txt");
      Files.writeString(f, "内容");
      Thread.sleep(4000); // 等待 OS 事件
      w.pollEvents();
      Thread.sleep(50); // 稳定窗口为 0，mtime 已过窗口
      List<Path> stable = w.drainStable();
      assertTrue(stable.contains(f));
      assertTrue(w.drainStable().isEmpty()); // 不重复上报
    }
  }

  @Test
  void ignoresIgnoredPrefix(@TempDir Path root) throws Exception {
    try (RepoWatcher w = new RepoWatcher(root, Duration.ZERO)) {
      w.start();
      Path f = root.resolve("移动来的.txt");
      Files.writeString(f, "x");
      w.ignorePrefix(f);
      Thread.sleep(4000);
      w.pollEvents();
      Thread.sleep(50);
      assertTrue(w.drainStable().isEmpty());
    }
  }

  @Test
  void unstableFileNotReported(@TempDir Path root) throws Exception {
    try (RepoWatcher w = new RepoWatcher(root, Duration.ofHours(1))) { // 窗口 1h：必不稳定
      w.start();
      Path f = root.resolve("还在写.txt");
      Files.writeString(f, "x");
      Thread.sleep(4000);
      w.pollEvents();
      assertTrue(w.drainStable().isEmpty());
    }
  }

  @Test
  void stableEntryKeptUntilSecondSampleConfirms(@TempDir Path root) throws Exception {
    try (RepoWatcher w = new RepoWatcher(root, Duration.ofSeconds(6))) {
      w.start();
      Path f = root.resolve("双采样.txt");
      Files.writeString(f, "内容");
      Thread.sleep(4000); // macOS PollingWatchService 2s 轮询，等待事件入队
      w.pollEvents();
      // 首次采样仍在稳定窗口内（<6s）：不驱逐也不上报，条目保留待下次采样
      assertTrue(w.drainStable().isEmpty());
      Thread.sleep(3000); // 窗口已过且期间无新事件
      assertTrue(w.drainStable().contains(f)); // 第二次采样确认稳定后才上报
    }
  }
}
