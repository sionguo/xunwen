package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.config.GlobalConfigStore;
import cn.guoxy.xunwen.config.RepoRegistry;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.extract.OcrFallbackExtractor;
import cn.guoxy.xunwen.llm.OpenAiCompatClient;
import java.io.IOException;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ListProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
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

  /** 最近一次后台活动的摘要(所有状态可见):主界面顶栏常驻显示,各任务完成时更新。 */
  private final StringProperty activity = new SimpleStringProperty("");

  /** 跨页面导航请求(架构:控制器间零耦合):消费者由 MainController 注册,值为页面名。 */
  private final ObjectProperty<java.util.function.Consumer<String>> navigationHandler =
      new SimpleObjectProperty<>();

  /** 引导式初始化标志:仓库页发起「生成类别树提案」跳转后,类别树页消费该标志自动开始。 */
  private final BooleanProperty autoGenerateProposal = new SimpleBooleanProperty();

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
    return new OcrFallbackExtractor();
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

  public StringProperty activityProperty() {
    return activity;
  }

  /** 最近活动历史（新在前，最多 5 条）：顶栏单行展示最新一条，点击可查看全部。 */
  private final javafx.collections.ObservableList<String> activityHistory =
      FXCollections.observableArrayList();

  public javafx.collections.ObservableList<String> activityHistory() {
    return activityHistory;
  }

  /** 更新顶栏活动摘要(任意线程可调,内部切 FX 线程)；历史保留最近 5 条不互相覆盖。 */
  public void activity(String text) {
    Platform.runLater(
        () -> {
          activity.set(text);
          activityHistory.add(0, text);
          if (activityHistory.size() > 5) {
            activityHistory.remove(5, activityHistory.size());
          }
        });
  }

  /** 注册导航处理器(MainController 启动时调用一次)。 */
  public void setNavigationHandler(java.util.function.Consumer<String> handler) {
    navigationHandler.set(handler);
  }

  /** 请求切换页面(view 为页面名,如 "TREE"/"PENDING");未注册处理器时静默忽略。 */
  public void requestNavigation(String view) {
    var handler = navigationHandler.get();
    if (handler != null) {
      handler.accept(view);
    }
  }

  /** 设置「跳转后自动生成类别树提案」标志(引导式初始化)。 */
  public void setAutoGenerateProposal(boolean value) {
    autoGenerateProposal.set(value);
  }

  /** 消费自动提案标志(读后即清,保证只触发一次)。 */
  public boolean consumeAutoGenerateProposal() {
    boolean v = autoGenerateProposal.get();
    autoGenerateProposal.set(false);
    return v;
  }
}
