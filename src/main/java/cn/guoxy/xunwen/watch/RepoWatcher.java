package cn.guoxy.xunwen.watch;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 递归目录监视（FR-11）。非线程化：调用方周期性 pollEvents() + drainStable()。 */
public final class RepoWatcher implements AutoCloseable {
  private record Snapshot(long size, long mtimeMs) {}

  private final Path root;
  private final Duration stableWindow;
  private final WatchService watchService;
  private final Map<Path, Snapshot> pending = new HashMap<>();
  private final List<String> ignoredPrefixes = new ArrayList<>();

  public RepoWatcher(Path root, Duration stableWindow) throws IOException {
    this.root = root.toAbsolutePath().normalize();
    this.stableWindow = stableWindow;
    this.watchService = FileSystems.getDefault().newWatchService();
  }

  public void start() throws IOException {
    try (var walk = Files.walk(root)) {
      for (Path dir : walk.filter(Files::isDirectory).toList()) {
        dir.register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY);
      }
    }
  }

  public void ignorePrefix(Path p) {
    ignoredPrefixes.add(p.toAbsolutePath().normalize().toString());
  }

  public void pollEvents() {
    WatchKey key;
    while ((key = watchService.poll()) != null) {
      Path dir = (Path) key.watchable();
      for (WatchEvent<?> event : key.pollEvents()) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
          continue;
        }
        Path child = dir.resolve((Path) event.context()).toAbsolutePath().normalize();
        if (child.startsWith(root.resolve(".xunwen")) || isIgnored(child)) {
          continue;
        }
        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
          try {
            child.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);
          } catch (IOException e) {
            // 目录注册失败则放弃该子树
          }
          continue;
        }
        if (Files.isRegularFile(child)) {
          try {
            pending.put(
                child,
                new Snapshot(Files.size(child), Files.getLastModifiedTime(child).toMillis()));
          } catch (IOException e) {
            // 文件可能已消失
          }
        }
      }
      key.reset();
    }
  }

  public List<Path> drainStable() {
    List<Path> stable = new ArrayList<>();
    for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
      var entry = it.next();
      Path p = entry.getKey();
      Snapshot snap = entry.getValue();
      try {
        long size = Files.size(p);
        long mtime = Files.getLastModifiedTime(p).toMillis();
        boolean unchanged = size == snap.size() && mtime == snap.mtimeMs();
        boolean windowPassed = System.currentTimeMillis() - mtime >= stableWindow.toMillis();
        if (unchanged && windowPassed) {
          stable.add(p);
          it.remove();
        } else if (!unchanged) {
          it.remove(); // 变化过，等下一次事件重新进入 pending
        }
        // unchanged 但窗口未过：留在 pending，下次 drain 再采样（连续 2 次采样不变才算稳定）
      } catch (IOException e) {
        it.remove(); // 文件已消失
      }
    }
    return stable;
  }

  private boolean isIgnored(Path p) {
    String s = p.toString();
    String name = p.getFileName().toString();
    return name.startsWith(".")
        || name.startsWith("~$")
        || ignoredPrefixes.stream().anyMatch(s::startsWith);
  }

  @Override
  public void close() throws IOException {
    watchService.close();
  }
}
