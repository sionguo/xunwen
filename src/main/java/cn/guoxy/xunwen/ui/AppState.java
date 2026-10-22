package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.config.GlobalConfigStore;
import cn.guoxy.xunwen.config.RepoRegistry;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.extract.TikaExtractor;
import cn.guoxy.xunwen.llm.OpenAiCompatClient;
import java.io.IOException;
import java.nio.file.Path;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ListProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;

/** UI 层共享状态:配置、仓库列表、当前仓库,外加一把全局引擎锁(单写者防 Lucene 写锁冲突)。 */
public final class AppState {
  private final Object engineLock = new Object();
  private final GlobalConfigStore configStore;
  private final RepoRegistry registry;
  private final ObjectProperty<GlobalConfig> config =
      new SimpleObjectProperty<>(GlobalConfig.defaults());
  private final ListProperty<Path> repos =
      new SimpleListProperty<>(FXCollections.observableArrayList());
  private final ObjectProperty<Path> currentRepo = new SimpleObjectProperty<>();

  /** 后台任务(watch 归置等)落库后的数据变更信号,视图监听它刷新;每次翻转取反。 */
  private final BooleanProperty dataChanged = new SimpleBooleanProperty();

  public AppState(Path configFile, Path reposFile) {
    this.configStore = new GlobalConfigStore(configFile);
    this.registry = new RepoRegistry(reposFile);
  }

  public void load() throws IOException {
    config.set(configStore.load());
    repos.setAll(registry.list());
  }

  public void saveConfig(GlobalConfig c) throws IOException {
    configStore.save(c);
    config.set(c);
  }

  public void addRepo(Path p) throws IOException {
    registry.add(p);
    repos.setAll(registry.list());
  }

  public void removeRepo(Path p) throws IOException {
    registry.remove(p);
    repos.setAll(registry.list());
    if (p.equals(currentRepo.get())) {
      currentRepo.set(null);
    }
  }

  /** 所有引擎后台任务的串行化锁:同一时刻只允许一个任务开 RepoIndex(单写者)。 */
  public Object engineLock() {
    return engineLock;
  }

  public OpenAiCompatClient llm() {
    GlobalConfig c = config.get();
    return new OpenAiCompatClient(
        c.baseUrl(), c.embedBaseUrl(), c.apiKey(), c.chatModel(), c.embedModel());
  }

  public ContentExtractor extractor() {
    return new TikaExtractor();
  }

  public ObjectProperty<GlobalConfig> configProperty() {
    return config;
  }

  public GlobalConfig config() {
    return config.get();
  }

  public ListProperty<Path> reposProperty() {
    return repos;
  }

  public ObjectProperty<Path> currentRepoProperty() {
    return currentRepo;
  }

  public Path currentRepo() {
    return currentRepo.get();
  }

  public BooleanProperty dataChangedProperty() {
    return dataChanged;
  }

  public void markDataChanged() {
    dataChanged.set(!dataChanged.get());
  }
}
