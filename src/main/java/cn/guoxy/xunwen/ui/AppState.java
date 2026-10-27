package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.config.GlobalConfigStore;
import cn.guoxy.xunwen.config.RepoRegistry;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.extract.OcrFallbackExtractor;
import cn.guoxy.xunwen.llm.OpenAiCompatClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
    synchronized (queryVecCache) {
      queryVecCache.clear(); // 模型/维度可能已变，查询向量全部失效
    }
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

  /** 复用 LLM 客户端：配置对象未更换即复用；设置页保存新配置自动重建（saveConfig 换引用即可）。 */
  private volatile OpenAiCompatClient llmClient;

  private volatile GlobalConfig configOfClient; // llmClient 对应的配置快照（引用比较判定复用）

  public OpenAiCompatClient llm() {
    GlobalConfig c = config.get();
    if (llmClient == null || configOfClient != c) {
      configOfClient = c;
      llmClient =
          new OpenAiCompatClient(
              c.baseUrl(), c.embedBaseUrl(), c.apiKey(), c.chatModel(), c.embedModel());
    }
    return llmClient;
  }

  /**
   * 查询向量化（带 LRU 缓存，上限 128 条）：同一搜索词重复检索不再调 embedding 端点——首屏延迟的 主要外部项。embedding 失败返回
   * null（调用方退关键词路），失败不缓存。
   */
  public float[] queryVector(String query) {
    synchronized (queryVecCache) {
      float[] hit = queryVecCache.get(query);
      if (hit != null) {
        return hit;
      }
    }
    float[] vec;
    try {
      vec = llm().embed(query);
    } catch (RuntimeException e) {
      return null; // embedding 不可用：调用方退关键词路（不缓存失败）
    }
    synchronized (queryVecCache) {
      queryVecCache.put(query, vec);
    }
    return vec;
  }

  /** 查询向量 LRU 缓存；saveConfig 时整体失效（模型/维度可能已变）。 */
  private final Map<String, float[]> queryVecCache =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
          return size() > 128;
        }
      };

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
