# xunwen 核心引擎实现计划（Plan A）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建无 UI 的核心引擎：内容提取 → LLM 分类（置信度分流）→ 文件重排（日志/回滚）→ Lucene 混合索引与检索 → 目录监视，全部 JUnit 可验证，端到端冒烟测试用 fake LLM 跑通。

**Architecture:** 单 Maven 模块，按包分层（`repo` 领域 / `extract` / `llm` / `classify` / `index` / `move` / `pipeline` / `watch`）。LLM 与 embedding 经 `OpenAiCompatClient` 一个实现同时满足两个接口，测试注入 fake。所有文件操作经 `FileMover` + `OperationLog`，保证可回滚。索引/检索/高亮全在 Lucene 单引擎内完成。

**Tech Stack:** Java 25 · Lucene 10.5.2（+analysis-smartcn）· Tika 4.1.0 · Jackson 3.2.4（`tools.jackson.*`，JSON+YAML）· JUnit 5.12 · JDK HttpClient

**Spec:** `docs/xunwen-v1-spec.md`（FR 编号在任务中引用）

## Global Constraints

- Java 25，UTF-8。**非模块化**：删除 `module-info.java`，classpath 启动（Launcher 已就位），pom `mainClass` 改为 `cn.guoxy.xunwen.Launcher`
- 提交前必须 `mvn -q spotless:apply` 再 `mvn -q verify`（spotless check 在 verify 阶段，google-java-format）
- 提交信息：英文 conventional commits（`feat:` / `test:` / `chore:`）
- 所有面向用户的字符串（目录名「待分类」、提示语）为中文；代码标识符英文
- 不引入：向量数据库、搜索引擎服务、Spring、OCR/Tesseract、TestFX
- 测试不依赖网络与本地 Ollama：LLM 一律注入 fake
- **跨平台（macOS/Windows/Linux）在范围内**，遵守：
  - `Doc.path`（存索引的相对路径）一律 POSIX 风格：`repoRoot.relativize(file).toString().replace('\\', '/')`，跨平台索引可移植
  - 全局配置目录解析用 `Path.of(System.getProperty("user.home"), ".xunwen")`（Plan B 组装；引擎层测试注入路径）
  - Windows 文件被占用（Office 打开中）时移动抛 `AccessDeniedException`：`FileProcessor` 捕获所有移动/提取 `IOException`，记日志跳过该文件（本批不动，下轮再处理），**绝不让整批任务崩**

---

### Task 1: 项目骨架与依赖

**Files:**
- Modify: `pom.xml`
- Delete: `src/main/java/module-info.java`
- Create: `src/main/resources/logback.xml`（可选，无则跳过）

**Interfaces:**
- Produces: 可编译可测试的空项目；后续所有任务的包结构 `cn.guoxy.xunwen.{repo,config,extract,llm,classify,index,move,pipeline,watch}`

- [ ] **Step 1: 修改 pom.xml**

`<properties>` 增加：

```xml
<jackson.version>3.2.4</jackson.version>
<tika.version>4.1.0</tika.version>
```

`<dependencies>` 增加（Jackson 3 坐标在 `tools.jackson.*`，包名同样从 `com.fasterxml.jackson` 迁至 `tools.jackson`；annotations 仍为 `com.fasterxml.jackson.annotation`；异常为 unchecked `JacksonException`，**无 checked JsonProcessingException**；`FAIL_ON_UNKNOWN_PROPERTIES` 默认已关闭）：

```xml
<dependency>
  <groupId>org.apache.lucene</groupId>
  <artifactId>lucene-analysis-smartcn</artifactId>
  <version>${lucene.version}</version>
</dependency>
<dependency>
  <groupId>tools.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>${jackson.version}</version>
</dependency>
<dependency>
  <groupId>tools.jackson.dataformat</groupId>
  <artifactId>jackson-dataformat-yaml</artifactId>
  <version>${jackson.version}</version>
</dependency>
<dependency>
  <groupId>org.apache.tika</groupId>
  <artifactId>tika-parsers-standard-package</artifactId>
  <version>${tika.version}</version>
</dependency>
```

`<build><plugins>` 增加 surefire（JUnit 5 必需，默认绑定的 2.12.4 不支持 JUnit platform）：

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <version>3.5.2</version>
</plugin>
```

javafx-maven-plugin 的 `<mainClass>` 由 `cn.guoxy.xunwen/cn.guoxy.xunwen.HelloApplication` 改为 `cn.guoxy.xunwen.Launcher`（非模块化 classpath 启动）。

删除 `src/main/java/module-info.java`。保留 `HelloApplication`/`HelloController`/`hello-view.fxml` 不动（Plan B 处理）。

- [ ] **Step 2: 验证编译与测试基础设施**

Run: `mvn -q verify`
Expected: BUILD SUCCESS（无测试时 surefire 跳过；spotless 通过——新 pom 未格式化问题仅限 pom 本身，spotless 配置只管 java）

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "chore: non-modular build, add tika/jackson/smartcn/surefire"
```

---

### Task 2: 类别树与仓库路径（repo 包）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/repo/Category.java`
- Create: `src/main/java/cn/guoxy/xunwen/repo/CategoryTree.java`
- Create: `src/main/java/cn/guoxy/xunwen/repo/RepoPaths.java`
- Test: `src/test/java/cn/guoxy/xunwen/repo/CategoryTreeTest.java`

**Interfaces:**
- Produces:
  - `record Category(String name, String description, List<Category> children)`
  - `CategoryTree(List<Category> roots)`；`List<Category> roots()`；`Optional<Category> find(String categoryPath)`（路径格式 `"父/子"`）；`List<String> flattenPaths()`（所有 1 级与 2 级节点路径，DFS 序）
  - `static CategoryTree CategoryTree.load(Path yaml)`；`void CategoryTree.save(Path yaml) throws IOException`
  - `static final String CategoryTree.PENDING_DIR_NAME = "待分类"`
  - `RepoPaths.xunwenDir/indexDir/categoriesYaml/operationsJsonl/pendingDir(Path repoRoot)`

- [ ] **Step 1: 写失败测试**

```java
package cn.guoxy.xunwen.repo;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CategoryTreeTest {

  private CategoryTree sample() {
    return new CategoryTree(
        java.util.List.of(
            new Category(
                "工作文档",
                "与工作相关的文档",
                java.util.List.of(new Category("报告", "各类报告", java.util.List.of()))),
            new Category("其他", "未明确归类", java.util.List.of())));
  }

  @Test
  void flattenPathsReturnsTwoLevelsInDfsOrder() {
    var paths = sample().flattenPaths();
    assertEquals(java.util.List.of("工作文档", "工作文档/报告", "其他"), paths);
  }

  @Test
  void findResolvesSlashPath() {
    assertTrue(sample().find("工作文档/报告").isPresent());
    assertTrue(sample().find("不存在").isEmpty());
  }

  @Test
  void yamlRoundTrip(@TempDir Path dir) throws IOException {
    Path yaml = dir.resolve("categories.yaml");
    sample().save(yaml);
    assertTrue(Files.exists(yaml));
    CategoryTree loaded = CategoryTree.load(yaml);
    assertEquals(sample().flattenPaths(), loaded.flattenPaths());
  }

  @Test
  void repoPathsLayout(@TempDir Path root) {
    assertEquals(root.resolve(".xunwen"), RepoPaths.xunwenDir(root));
    assertEquals(root.resolve(".xunwen/index"), RepoPaths.indexDir(root));
    assertEquals(root.resolve(".xunwen/categories.yaml"), RepoPaths.categoriesYaml(root));
    assertEquals(root.resolve(".xunwen/operations.jsonl"), RepoPaths.operationsJsonl(root));
    assertEquals(root.resolve("待分类"), RepoPaths.pendingDir(root));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=CategoryTreeTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 最小实现**

`Category.java`：

```java
package cn.guoxy.xunwen.repo;

import java.util.List;

public record Category(String name, String description, List<Category> children) {
  public Category {
    children = children == null ? List.of() : List.copyOf(children);
  }
}
```

`CategoryTree.java`（Jackson YAML，`YAMLFactory`）：

```java
package cn.guoxy.xunwen.repo;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record CategoryTree(List<Category> roots) {

  public static final String PENDING_DIR_NAME = "待分类";

  private static final ObjectMapper YAML =
      new ObjectMapper(YAMLFactory.builder().build()).enable(SerializationFeature.INDENT_OUTPUT);

  public CategoryTree {
    roots = roots == null ? List.of() : List.copyOf(roots);
  }

  public Optional<Category> find(String categoryPath) {
    if (categoryPath == null || categoryPath.isBlank()) {
      return Optional.empty();
    }
    String[] parts = categoryPath.split("/");
    Optional<Category> root =
        roots.stream().filter(c -> c.name().equals(parts[0])).findFirst();
    if (parts.length == 1) {
      return root;
    }
    return root.flatMap(
        r -> r.children().stream().filter(c -> c.name().equals(parts[1])).findFirst());
  }

  public List<String> flattenPaths() {
    List<String> out = new ArrayList<>();
    for (Category root : roots) {
      out.add(root.name());
      for (Category child : root.children()) {
        out.add(root.name() + "/" + child.name());
      }
    }
    return out;
  }

  record YamlRoot(List<Category> categories) {}

  public void save(Path yaml) throws IOException {
    YAML.writeValue(yaml.toFile(), new YamlRoot(roots));
  }

  public static CategoryTree load(Path yaml) throws IOException {
    YamlRoot root = YAML.readValue(yaml.toFile(), YamlRoot.class);
    return new CategoryTree(root.categories() == null ? List.of() : root.categories());
  }
}
```

`RepoPaths.java`：

```java
package cn.guoxy.xunwen.repo;

import java.nio.file.Path;

public final class RepoPaths {
  private RepoPaths() {}

  public static Path xunwenDir(Path repoRoot) {
    return repoRoot.resolve(".xunwen");
  }

  public static Path indexDir(Path repoRoot) {
    return xunwenDir(repoRoot).resolve("index");
  }

  public static Path categoriesYaml(Path repoRoot) {
    return xunwenDir(repoRoot).resolve("categories.yaml");
  }

  public static Path operationsJsonl(Path repoRoot) {
    return xunwenDir(repoRoot).resolve("operations.jsonl");
  }

  public static Path pendingDir(Path repoRoot) {
    return repoRoot.resolve(CategoryTree.PENDING_DIR_NAME);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=CategoryTreeTest`
Expected: PASS（4 tests）

- [ ] **Step 5: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: category tree model with yaml persistence and repo paths"
```

---

### Task 3: 全局配置与仓库注册表（config 包）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/config/GlobalConfig.java`
- Create: `src/main/java/cn/guoxy/xunwen/config/GlobalConfigStore.java`
- Create: `src/main/java/cn/guoxy/xunwen/config/RepoRegistry.java`
- Test: `src/test/java/cn/guoxy/xunwen/config/ConfigTest.java`

**Interfaces:**
- Consumes: Jackson（Task 1）
- Produces:
  - `record GlobalConfig(String baseUrl, String apiKey, String chatModel, String embedModel, int vectorDim, double autoThreshold, double pendingThreshold, int concurrency)` + `static GlobalConfig defaults()`（值为 `http://localhost:11434/v1`, `""`, `qwen3:8b`, `bge-m3`, `1024`, `0.85`, `0.5`, `2`，对应 spec FR-10）
  - `GlobalConfigStore(Path file)`；`GlobalConfig load()`（文件缺失→defaults）；`void save(GlobalConfig)`
  - `RepoRegistry(Path file)`；`List<Path> list()`；`void add(Path)`（去重、规范化为绝对路径）；`void remove(Path)`

- [ ] **Step 1: 写失败测试**

```java
package cn.guoxy.xunwen.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

  @Test
  void globalConfigDefaults() {
    var c = GlobalConfig.defaults();
    assertEquals("http://localhost:11434/v1", c.baseUrl());
    assertEquals(0.85, c.autoThreshold());
    assertEquals(0.5, c.pendingThreshold());
    assertEquals(1024, c.vectorDim());
  }

  @Test
  void configStoreRoundTripAndMissingFile(@TempDir Path dir) throws IOException {
    var store = new GlobalConfigStore(dir.resolve("config.json"));
    assertEquals(GlobalConfig.defaults(), store.load()); // 文件缺失返回默认
    var custom = new GlobalConfig("http://x/v1", "sk-1", "m1", "e1", 768, 0.9, 0.4, 4);
    store.save(custom);
    assertEquals(custom, new GlobalConfigStore(dir.resolve("config.json")).load());
  }

  @Test
  void registryAddDedupAndRemove(@TempDir Path dir) throws IOException {
    var reg = new RepoRegistry(dir.resolve("repos.json"));
    reg.add(dir.resolve("repoA"));
    reg.add(dir.resolve("repoA")); // 重复
    reg.add(dir.resolve("repoB"));
    assertEquals(List.of(dir.resolve("repoA"), dir.resolve("repoB")), reg.list());
    reg.remove(dir.resolve("repoA"));
    assertEquals(List.of(dir.resolve("repoB")), reg.list());
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=ConfigTest`
Expected: 编译失败

- [ ] **Step 3: 最小实现**

`GlobalConfig.java`：

```java
package cn.guoxy.xunwen.config;

public record GlobalConfig(
    String baseUrl,
    String apiKey,
    String chatModel,
    String embedModel,
    int vectorDim,
    double autoThreshold,
    double pendingThreshold,
    int concurrency) {

  public static GlobalConfig defaults() {
    return new GlobalConfig(
        "http://localhost:11434/v1", "", "qwen3:8b", "bge-m3", 1024, 0.85, 0.5, 2);
  }
}
```

`GlobalConfigStore.java`：

```java
package cn.guoxy.xunwen.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GlobalConfigStore {
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private final Path file;

  public GlobalConfigStore(Path file) {
    this.file = file;
  }

  public GlobalConfig load() throws IOException {
    if (!Files.exists(file)) {
      return GlobalConfig.defaults();
    }
    return JSON.readValue(file.toFile(), GlobalConfig.class);
  }

  public void save(GlobalConfig config) throws IOException {
    Files.createDirectories(file.getParent());
    JSON.writeValue(file.toFile(), config);
  }
}
```

`RepoRegistry.java`：

```java
package cn.guoxy.xunwen.config;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RepoRegistry {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path file;

  public RepoRegistry(Path file) {
    this.file = file;
  }

  public List<Path> list() throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<String> raw =
        JSON.readValue(file.toFile(), new tools.jackson.core.type.TypeReference<List<String>>() {});
    return raw.stream().map(Path::of).toList();
  }

  public void add(Path repo) throws IOException {
    Set<Path> all = new LinkedHashSet<>(list());
    all.add(repo.toAbsolutePath().normalize());
    write(all);
  }

  public void remove(Path repo) throws IOException {
    Set<Path> all = new LinkedHashSet<>(list());
    all.remove(repo.toAbsolutePath().normalize());
    write(all);
  }

  private void write(Set<Path> all) throws IOException {
    Files.createDirectories(file.getParent());
    JSON.writeValue(file.toFile(), all.stream().map(Path::toString).toList());
  }
}
```

注意 `list()` 中 `new ArrayList<>()` 若未用到则删（spotless 不删未用变量，编译警告无碍；直接写简洁版）。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=ConfigTest`
Expected: PASS（3 tests）

- [ ] **Step 5: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: global config store and repo registry"
```

---

### Task 4: Tika 内容提取（extract 包，PipesForkParser 进程隔离）

**Files:**
- Modify: `pom.xml`（追加 `tika-pipes-fork-parser` + surefire classloader 配置）
- Create: `src/main/java/cn/guoxy/xunwen/extract/ContentExtractor.java`
- Create: `src/main/java/cn/guoxy/xunwen/extract/TikaExtractor.java`
- Test: `src/test/java/cn/guoxy/xunwen/extract/TikaExtractorTest.java`

**Interfaces:**
- Produces:
  - `interface ContentExtractor { Extracted extract(Path file) throws IOException; record Extracted(String text, boolean hasTextLayer) }`
  - `TikaExtractor(int maxChars)` 与 `TikaExtractor()`（默认 100_000）；提取文本超限截断；strip 后长度 < 20 → `hasTextLayer=false`（spec FR-7）
  - 内部用 **`PipesForkParser`**（`org.apache.tika:tika-pipes-fork-parser:4.1.0`）在 forked JVM 中解析：崩溃/超时/OOM/无限循环隔离在子进程（官方嵌入式推荐路径）；进程崩溃或失败一律返回 `("", false)` 走文件名兜底。**解析器静态单例、创建一次复用**（官方：thread-safe，每实例拥有 fork 池，逐文档新建=每文档起一个 JVM）

**前置（Step 0）pom 改动：**

```xml
<dependency>
  <groupId>org.apache.tika</groupId>
  <artifactId>tika-pipes-fork-parser</artifactId>
  <version>${tika.version}</version>
  <exclusions>
    <exclusion> <!-- 保 SLF4J 唯一 provider = logback，防 log4j-slf4j2-impl 抢绑定 -->
      <groupId>org.apache.logging.log4j</groupId>
      <artifactId>log4j-slf4j2-impl</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

surefire 配置追加（**防 fork 子进程拿到 manifest-only 假 classpath**——surefire 默认用 manifest jar 启动，`java.class.path` 对 fork 进程无效）：

```xml
<configuration>
  <useSystemClassLoader>false</useSystemClassLoader>
</configuration>
```

若实现时发现 fork 进程确需 log4j-core（子进程日志初始化失败），回退 exclusion 中的 `log4j-core` 项再验——以 fork 解析成功为准。

- [ ] **Step 1: 写失败测试**

```java
package cn.guoxy.xunwen.extract;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TikaExtractorTest {

  @Test
  void extractsPlainText(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("a.txt");
    Files.writeString(f, "这是一份关于季度销售总结的文档，包含详细的数字与分析。", StandardCharsets.UTF_8);
    var out = new TikaExtractor().extract(f);
    assertTrue(out.hasTextLayer());
    assertTrue(out.text().contains("季度销售"));
  }

  @Test
  void marksShortTextAsNoTextLayer(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("empty.txt");
    Files.writeString(f, "");
    var out = new TikaExtractor().extract(f);
    assertFalse(out.hasTextLayer());
  }

  @Test
  void truncatesToMaxChars(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("big.txt");
    Files.writeString(f, "字".repeat(50_000));
    var out = new TikaExtractor(10_000).extract(f);
    assertEquals(10_000, out.text().length());
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=TikaExtractorTest`
Expected: 编译失败

- [ ] **Step 3: 最小实现**

`ContentExtractor.java`：

```java
package cn.guoxy.xunwen.extract;

import java.io.IOException;
import java.nio.file.Path;

public interface ContentExtractor {
  Extracted extract(Path file) throws IOException;

  record Extracted(String text, boolean hasTextLayer) {}
}
```

`TikaExtractor.java`：

```java
package cn.guoxy.xunwen.extract;

import java.nio.file.Path;
import org.apache.tika.config.TimeoutLimits;
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

  private static PipesForkParser shared() {
    if (shared == null) {
      synchronized (TikaExtractor.class) {
        if (shared == null) {
          shared = new PipesForkParser(new PipesForkParserConfig()
              .setTimeoutLimits(new TimeoutLimits(
                  TimeoutLimits.DEFAULT_TOTAL_TASK_TIMEOUT_MILLIS, PARSE_TIMEOUT_MS)));
        }
      }
    }
    return shared;
  }
}
```

注：`PipesForkParserConfig`/`TimeoutLimits` 的确切 setter 以 4.1.0 javadoc 为准（官方示例见 pipes.html）；`pluginsDir` 不设，默认从 classpath 解析。测试首次运行会启动 fork JVM（约数秒），属预期。`isProcessCrash()` 与一般失败此处合并为 `("", false)`——v1 不区分死信队列，日志可见即可。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=TikaExtractorTest`
Expected: PASS（3 tests；若 fork 因 classpath/log4j 失败，按 Step 0 注记调整）

- [ ] **Step 5: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: tika extraction via PipesForkParser with process isolation"
```

---

### Task 5: OpenAI 兼容 LLM 客户端（llm 包）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/llm/LlmClient.java`
- Create: `src/main/java/cn/guoxy/xunwen/llm/EmbeddingClient.java`
- Create: `src/main/java/cn/guoxy/xunwen/llm/OpenAiCompatClient.java`
- Test: `src/test/java/cn/guoxy/xunwen/llm/OpenAiCompatClientTest.java`

**Interfaces:**
- Produces:
  - `interface LlmClient { String chat(List<Msg> messages); record Msg(String role, String content) { static Msg system(String c); static Msg user(String c) } }`
  - `interface EmbeddingClient { float[] embed(String text); List<float[]> embedAll(List<String> texts) }`
  - `OpenAiCompatClient(String baseUrl, String apiKey, String chatModel, String embedModel, int vectorDim)` 同时实现两者。chat → `POST {base}/chat/completions`，body 含 `response_format: {"type":"json_object"}`、`temperature: 0.1`，返回 `choices[0].message.content`；embed → `POST {base}/embeddings`，body `{model, input:[texts]}`，按 `data[i].index` 排序取 `embedding`。Bearer 头仅在 apiKey 非空时发送。
  - 测试用 JDK 内置 `com.sun.net.httpserver.HttpServer` 做 stub，零新依赖。

- [ ] **Step 1: 写失败测试**

```java
package cn.guoxy.xunwen.llm;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatClientTest {
  private HttpServer server;
  private String baseUrl;
  private final ObjectMapper json = new ObjectMapper();
  private final AtomicReference<String> lastChatBody = new AtomicReference<>();
  private final AtomicReference<String> lastEmbedBody = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          lastChatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String body =
              json
                  .createObjectNode()
                  .putObject("choices")
                  .putObject("message")
                  .put("content", "{\"category\":\"工作文档\"}")
                  .toString();
          byte[] resp = ("{\"choices\":[{\"message\":{\"content\":"
                  + json.writeValueAsString("{\"category\":\"工作文档\"}")
                  + "}}]}")
              .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          exchange.getResponseBody().write(resp);
          exchange.close();
        });
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          lastEmbedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] resp =
              "{\"data\":[{\"index\":1,\"embedding\":[0.4f,0.5f]},{\"index\":0,\"embedding\":[0.1f,0.2f]}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          exchange.getResponseBody().write(resp);
          exchange.close();
        });
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void chatSendsModelAndJsonFormatAndParsesContent() throws Exception {
    var client = new OpenAiCompatClient(baseUrl, "sk-test", "m1", "e1", 2);
    String out = client.chat(List.of(LlmClient.Msg.system("sys"), LlmClient.Msg.user("usr")));
    assertEquals("{\"category\":\"工作文档\"}", out);
    JsonNode body = json.readTree(lastChatBody.get());
    assertEquals("m1", body.get("model").asText());
    assertEquals("json_object", body.get("response_format").get("type").asText());
    assertEquals(2, body.get("messages").size());
    assertEquals("Authorization", "Authorization"); // header 断言在下方独立用例覆盖
  }

  @Test
  void embedAllSortsByIndex() {
    var client = new OpenAiCompatClient(baseUrl, "", "m1", "e1", 2);
    List<float[]> vecs = client.embedAll(List.of("a", "b"));
    assertArrayEquals(new float[] {0.1f, 0.2f}, vecs.get(0));
    assertArrayEquals(new float[] {0.4f, 0.5f}, vecs.get(1));
  }

  @Test
  void embedSingleDelegates() {
    var client = new OpenAiCompatClient(baseUrl, "", "m1", "e1", 2);
    assertArrayEquals(new float[] {0.1f, 0.2f}, client.embed("hello"));
  }
}
```

（写代码时删除 chat 测试里那行占位的无意义断言；header 行为以「apiKey 非空→带 Bearer」为规范，若实现直接可见可不再单测。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=OpenAiCompatClientTest`
Expected: 编译失败

- [ ] **Step 3: 最小实现**

`LlmClient.java`：

```java
package cn.guoxy.xunwen.llm;

import java.util.List;

public interface LlmClient {
  String chat(List<Msg> messages);

  record Msg(String role, String content) {
    public static Msg system(String content) {
      return new Msg("system", content);
    }

    public static Msg user(String content) {
      return new Msg("user", content);
    }
  }
}
```

`EmbeddingClient.java`：

```java
package cn.guoxy.xunwen.llm;

import java.util.List;

public interface EmbeddingClient {
  float[] embed(String text);

  List<float[]> embedAll(List<String> texts);
}
```

`OpenAiCompatClient.java`（JDK HttpClient + Jackson）：

```java
package cn.guoxy.xunwen.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

public final class OpenAiCompatClient implements LlmClient, EmbeddingClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  private final String baseUrl;
  private final String apiKey;
  private final String chatModel;
  private final String embedModel;

  public OpenAiCompatClient(
      String baseUrl, String apiKey, String chatModel, String embedModel, int vectorDim) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey == null ? "" : apiKey;
    this.chatModel = chatModel;
    this.embedModel = embedModel;
  }

  @Override
  public String chat(List<Msg> messages) {
    try {
      ObjectNode body = JSON.createObjectNode();
      body.put("model", chatModel);
      body.put("temperature", 0.1);
      body.putObject("response_format").put("type", "json_object");
      ArrayNode msgs = body.putArray("messages");
      for (Msg m : messages) {
        msgs.addObject().put("role", m.role()).put("content", m.content());
      }
      JsonNode resp = post(baseUrl + "/chat/completions", body);
      return resp.path("choices").path(0).path("message").path("content").asText();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("LLM chat failed: " + e.getMessage(), e);
    }
  }

  @Override
  public float[] embed(String text) {
    return embedAll(List.of(text)).get(0);
  }

  @Override
  public List<float[]> embedAll(List<String> texts) {
    try {
      ObjectNode body = JSON.createObjectNode();
      body.put("model", embedModel);
      body.putPOJO("input", texts);
      JsonNode data = post(baseUrl + "/embeddings", body).path("data");
      List<JsonNode> sorted = new ArrayList<>();
      data.forEach(sorted::add);
      sorted.sort(Comparator.comparingInt(n -> n.path("index").asInt()));
      return sorted.stream().map(n -> toArray(n.path("embedding"))).toList();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("Embedding failed: " + e.getMessage(), e);
    }
  }

  private static float[] toArray(JsonNode arr) {
    float[] out = new float[arr.size()];
    for (int i = 0; i < arr.size(); i++) {
      out[i] = (float) arr.get(i).asDouble();
    }
    return out;
  }

  private JsonNode post(String url, ObjectNode body)
      throws IOException, InterruptedException {
    var req = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(5))
        .header("Content-Type", "application/json");
    if (!apiKey.isBlank()) {
      req.header("Authorization", "Bearer " + apiKey);
    }
    req.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
    }
    return JSON.readTree(resp.body());
  }
}
```

（embedAll 的实现以上面读起来通顺的版本为准——先 `JSON.readTree(resp.toString())` 再取 data 流；执行者可简化为一次性 `JsonNode data = resp.path("data")` 后循环。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=OpenAiCompatClientTest`
Expected: PASS（3 tests）

- [ ] **Step 5: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: openai-compatible chat and embedding client"
```

---

### Task 6: Lucene 单仓库索引（index 包）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/index/Doc.java`
- Create: `src/main/java/cn/guoxy/xunwen/index/RepoIndex.java`
- Test: `src/test/java/cn/guoxy/xunwen/index/RepoIndexTest.java`

**Interfaces:**
- Consumes: Jackson 无；仅 Lucene
- Produces:
  - `record Doc(String path, String name, String ext, String hash, long mtime, long size, String status, String categoryPath, String content, float[] vector)`。`status` ∈ `INDEXED | PENDING | UNKNOWN`
  - `record StoredDoc(String path, String name, String categoryPath, String status, String content)`
  - `RepoIndex(Path indexDir, int vectorDim) implements AutoCloseable`：
    - `void upsert(Doc doc)`（按 path 先删后写）
    - `void deleteByPath(String path)`
    - `Optional<StoredDoc> findByPath(String path)`
    - `List<FileInfo> allFiles()`（全索引指纹清单，供 Task 12 sync 做 diff；MatchAllDocsQuery 遍历存储字段）
  - `record FileInfo(String path, String hash, long mtime, long size) {}`（index 包独立文件，与 Doc 平级）
    - `ScoreDoc[] searchKeyword(String query, int k)`（`MultiFieldQueryParser(["name","content"], SmartChineseAnalyzer)`）
    - `TopDocs searchKnn(float[] vector, int k)`（`KnnFloatVectorQuery("vec", vector, k)`）
    - `List<StoredDoc> storedOf(ScoreDoc[] hits)`
    - `IndexReader reader()`（供 HybridSearcher/高亮使用）
  - 字段名常量集中在 `RepoIndex`：`F_PATH, F_NAME, F_EXT, F_HASH, F_MTIME, F_SIZE, F_STATUS, F_CATEGORY, F_CONTENT, F_VEC`
  - schema：path=StringField(不切词)+StoredField；name=TextField+Stored；ext/hash/status/category=StringField+Stored；mtime/size=StoredField；content=TextField+Stored；vec=KnnFloatVectorField(vectorDim, VectorSimilarityFunction.COSINE)

- [ ] **Step 1: 写失败测试**

```java
package cn.guoxy.xunwen.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Optional;
import org.apache.lucene.search.ScoreDoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoIndexTest {

  private Doc doc(String path, String name, String content, float[] vec) {
    return new Doc(path, name, "pdf", "hash-" + path, 1L, 2L, "INDEXED", "工作文档", content, vec);
  }

  @Test
  void upsertThenFindByPath(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"), 4)) {
      idx.upsert(doc("a/b.pdf", "b.pdf", "季度销售总结报告内容", new float[] {1, 0, 0, 0}));
      idx.upsert(doc("a/b.pdf", "b.pdf", "内容已更新版本二", new float[] {0, 1, 0, 0}));
      Optional<StoredDoc> hit = idx.findByPath("a/b.pdf");
      assertTrue(hit.isPresent());
      assertEquals("内容已更新版本二", hit.get().content()); // upsert 覆盖旧文档
      assertEquals(1, idx.reader().numDocs());
      List<FileInfo> files = idx.allFiles();
      assertEquals(1, files.size());
      assertEquals("a/b.pdf", files.get(0).path());
      assertEquals("hash-a/b.pdf", files.get(0).hash());
    }
  }

  @Test
  void deleteByPathRemoves(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"), 4)) {
      idx.upsert(doc("a.pdf", "a.pdf", "内容", new float[] {1, 0, 0, 0}));
      idx.deleteByPath("a.pdf");
      assertTrue(idx.findByPath("a.pdf").isEmpty());
    }
  }

  @Test
  void keywordSearchHitsChineseContent(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"), 4)) {
      idx.upsert(doc("1.pdf", "发票.pdf", "餐饮发票 报销凭证", new float[] {0, 0, 0, 1}));
      idx.upsert(doc("2.pdf", "合同.pdf", "劳动合同 条款", new float[] {0, 0, 1, 0}));
      ScoreDoc[] hits = idx.searchKeyword("发票", 10);
      assertEquals(1, hits.length);
      assertEquals("1.pdf", idx.storedOf(hits).get(0).path());
    }
  }

  @Test
  void knnSearchReturnsNearest(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"), 4)) {
      idx.upsert(doc("1.pdf", "1.pdf", "c1", new float[] {1, 0, 0, 0}));
      idx.upsert(doc("2.pdf", "2.pdf", "c2", new float[] {0, 1, 0, 0}));
      ScoreDoc[] hits = idx.searchKnn(new float[] {1, 0, 0, 0}, 1);
      assertEquals("1.pdf", idx.storedOf(hits).get(0).path());
    }
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=RepoIndexTest`
Expected: 编译失败

- [ ] **Step 3: 最小实现**

`Doc.java`：

```java
package cn.guoxy.xunwen.index;

public record Doc(
    String path,
    String name,
    String ext,
    String hash,
    long mtime,
    long size,
    String status,
    String categoryPath,
    String content,
    float[] vector) {}
```

`RepoIndex.java`：

```java
package cn.guoxy.xunwen.index;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;

public final class RepoIndex implements AutoCloseable {
  public static final String F_PATH = "path";
  public static final String F_NAME = "name";
  public static final String F_EXT = "ext";
  public static final String F_HASH = "hash";
  public static final String F_MTIME = "mtime";
  public static final String F_SIZE = "size";
  public static final String F_STATUS = "status";
  public static final String F_CATEGORY = "category";
  public static final String F_CONTENT = "content";
  public static final String F_VEC = "vec";

  private final FSDirectory dir;
  private final IndexWriter writer;
  private final SmartChineseAnalyzer analyzer;
  private final int vectorDim;

  public RepoIndex(Path indexDir, int vectorDim) throws IOException {
    this.vectorDim = vectorDim;
    this.dir = FSDirectory.open(indexDir);
    this.analyzer = new SmartChineseAnalyzer();
    this.writer = new IndexWriter(dir, new IndexWriterConfig(analyzer));
  }

  public IndexReader reader() throws IOException {
    return DirectoryReader.open(dir);
  }

  public void upsert(Doc d) throws IOException {
    writer.deleteDocuments(new Term(F_PATH, d.path()));
    Document doc = new Document();
    doc.add(new StringField(F_PATH, d.path(), Store.YES));
    doc.add(new TextField(F_NAME, d.name(), Store.YES));
    doc.add(new StringField(F_EXT, d.ext(), Store.YES));
    doc.add(new StringField(F_HASH, d.hash(), Store.YES));
    doc.add(new StoredField(F_MTIME, d.mtime()));
    doc.add(new StoredField(F_SIZE, d.size()));
    doc.add(new StringField(F_STATUS, d.status(), Store.YES));
    doc.add(new StringField(F_CATEGORY, d.categoryPath(), Store.YES));
    doc.add(new TextField(F_CONTENT, d.content(), Store.YES));
    doc.add(
        new KnnFloatVectorField(
            F_VEC, d.vector(), org.apache.lucene.index.VectorSimilarityFunction.COSINE));
    writer.addDocument(doc);
    writer.commit();
  }

  public void deleteByPath(String path) throws IOException {
    writer.deleteDocuments(new Term(F_PATH, path));
    writer.commit();
  }

  public Optional<StoredDoc> findByPath(String path) throws IOException {
    Query q = new TermQuery(new Term(F_PATH, path));
    TopDocs docs = search(q, 1);
    List<StoredDoc> out = storedOf(docs.scoreDocs);
    return out.isEmpty() ? Optional.empty() : Optional.of(out.get(0));
  }

  public List<FileInfo> allFiles() throws IOException {
    try (IndexReader reader = reader()) {
      // MatchAllDocsQuery 只命中 live docs，deleted doc 自然排除
      TopDocs all = search(new org.apache.lucene.search.MatchAllDocsQuery(), Math.max(1, reader.numDocs()));
      List<FileInfo> out = new ArrayList<>();
      for (org.apache.lucene.search.ScoreDoc hit : all.scoreDocs) {
        Document d = reader.storedFields().document(hit.doc);
        out.add(new FileInfo(
            d.get(F_PATH),
            d.get(F_HASH),
            d.getField(F_MTIME).numericValue().longValue(),
            d.getField(F_SIZE).numericValue().longValue()));
      }
      return out;
    }
  }

  public ScoreDoc[] searchKeyword(String query, int k) throws IOException {
    var parser = new MultiFieldQueryParser(new String[] {F_NAME, F_CONTENT}, analyzer);
    try {
      return search(parser.parse(query), k).scoreDocs;
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      throw new IOException("Bad query: " + query, e);
    }
  }

  public TopDocs searchKnn(float[] vector, int k) throws IOException {
    return search(new KnnFloatVectorQuery(F_VEC, vector, k), k);
  }

  public List<StoredDoc> storedOf(ScoreDoc[] hits) {
    try (IndexReader reader = reader()) {
      List<StoredDoc> out = new ArrayList<>();
      for (ScoreDoc hit : hits) {
        Document d = reader.storedFields().document(hit.doc);
        out.add(
            new StoredDoc(
                d.get(F_PATH),
                d.get(F_NAME),
                d.get(F_CATEGORY),
                d.get(F_STATUS),
                d.get(F_CONTENT)));
      }
      return out;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private TopDocs search(Query q, int k) throws IOException {
    try (IndexReader reader = reader()) {
      return new IndexSearcher(reader).search(q, k);
    }
  }

  @Override
  public void close() throws IOException {
    writer.close();
    dir.close();
    analyzer.close();
  }
}
```

注意：`vectorDim` 字段当前仅记录维度（KnnFloatVectorField 从向量本身推维度）；若 spotless/编译报未使用字段，可在构造时校验传入向量维度一致性，或删除字段——以编译干净为准。`storedOf` 每次开 reader 成本可接受（测试与 v1 数据量级）；若 lint 报性能告警不阻塞。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=RepoIndexTest`
Expected: PASS（4 tests）

- [ ] **Step 5: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: per-repo lucene index with smartcn, knn vectors and upsert"
```

---

### Task 7: 混合检索 RRF + 高亮（index 包）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/index/HybridSearcher.java`
- Test: `src/test/java/cn/guoxy/xunwen/index/HybridSearcherTest.java`

**Interfaces:**
- Consumes: `RepoIndex`（Task 6）
- Produces:
  - `record Hit(String repoName, String path, String name, String categoryPath, String highlight, double score)`
  - `static List<Hit> HybridSearcher.search(LinkedHashMap<String, RepoIndex> indices, String query, float[] queryVec, int topK)`
    - 每 index：keyword topK×2 + knn topK×2 → RRF `1/(60+rank)` 合并（同文档跨路相加）→ 全局排序取 topK → 对每个 hit 用 BM25 的 keyword Query 做高亮（`Highlighter`+`QueryScorer`，取 best fragment，无命中时取 content 前 80 字）。

- [ ] **Step 1: 写失败测试**

```java
package cn.guoxy.xunwen.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HybridSearcherTest {

  private void seed(RepoIndex idx, String path, String name, String content, float[] vec)
      throws Exception {
    idx.upsert(new Doc(path, name, "txt", "h", 1L, 2L, "INDEXED", "工作文档", content, vec));
  }

  @Test
  void keywordAndVectorFuseAcrossRepos(@TempDir Path dir) throws Exception {
    try (RepoIndex a = new RepoIndex(dir.resolve("a"), 4);
        RepoIndex b = new RepoIndex(dir.resolve("b"), 4)) {
      seed(a, "x.txt", "销售总结.txt", "本季度销售业绩总结：营收增长百分之二十。", new float[] {1, 0, 0, 0});
      seed(b, "y.txt", "合同.txt", "劳动合同条款一览。", new float[] {0, 0, 1, 0});

      var indices = new LinkedHashMap<String, RepoIndex>();
      indices.put("仓库A", a);
      indices.put("仓库B", b);

      var hits = HybridSearcher.search(indices, "销售", new float[] {1, 0, 0, 0}, 10);
      assertFalse(hits.isEmpty());
      assertEquals("仓库A", hits.get(0).repoName());
      assertEquals("x.txt", hits.get(0).path());
      assertTrue(hits.get(0).highlight().contains("<"));
      assertTrue(hits.stream().anyMatch(h -> h.repoName().equals("仓库B"))); // 两仓都返回
    }
  }

  @Test
  void rrfCombinesWhenDocHitsBothPaths(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = new RepoIndex(dir.resolve("a"), 4)) {
      // 内容同时匹配关键词且向量最近
      seed(idx, "both.txt", "both.txt", "机器学习入门笔记", new float[] {1, 0, 0, 0});
      seed(idx, "kw.txt", "kw.txt", "机器学习进阶路线", new float[] {0, 1, 0, 0});
      var indices = new LinkedHashMap<String, RepoIndex>();
      indices.put("R", idx);
      var hits = HybridSearcher.search(indices, "机器学习", new float[] {1, 0, 0, 0}, 5);
      assertEquals("both.txt", hits.get(0).path()); // 双路命中排第一
    }
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=HybridSearcherTest`
Expected: 编译失败

- [ ] **Step 3: 最小实现**

```java
package cn.guoxy.xunwen.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;

/** BM25 + KNN 的 RRF 混合检索（FR-8）。 */
public final class HybridSearcher {
  public record Hit(
      String repoName, String path, String name, String categoryPath, String highlight, double score) {}

  private static final int RRF_K = 60;
  private static final int CANDIDATES = 100; // topK*2 候选上限

  private HybridSearcher() {}

  private record Acc(double score, String repo, String path, String name, String category, String content) {}

  public static List<Hit> search(
      LinkedHashMap<String, RepoIndex> indices, String query, float[] queryVec, int topK) {
    Map<String, Acc> acc = new HashMap<>();
    Map<String, Query> kwQueries = new HashMap<>();

    try (SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer()) {
      for (var entry : indices.entrySet()) {
        RepoIndex idx = entry.getValue();
        List<StoredDoc> knnDocs = idx.storedOf(idx.searchKnn(queryVec, CANDIDATES).scoreDocs);
        ScoreDoc[] kwHits = idx.searchKeyword(query, CANDIDATES);
        List<StoredDoc> kwDocs = idx.storedOf(kwHits);
        Query kwQuery =
            new org.apache.lucene.queryparser.classic.MultiFieldQueryParser(
                    new String[] {RepoIndex.F_NAME, RepoIndex.F_CONTENT}, analyzer)
                .parse(query);
        kwQueries.put(entry.getKey(), kwQuery);

        int rank = 1;
        for (StoredDoc d : knnDocs) {
          accumulate(acc, d, entry.getKey(), rank++);
        }
        rank = 1;
        for (StoredDoc d : kwDocs) {
          accumulate(acc, d, entry.getKey(), rank++);
        }
      }

      List<Acc> sorted =
          acc.values().stream()
              .sorted(Comparator.comparingDouble(Acc::score).reversed())
              .limit(topK)
              .toList();

      List<Hit> out = new ArrayList<>();
      for (Acc a : sorted) {
        String hl = highlight(analyzer, kwQueries.get(a.repo()), a.content());
        out.add(new Hit(a.repo(), a.path(), a.name(), a.category(), hl, a.score()));
      }
      return out;
    } catch (Exception e) {
      throw new IllegalStateException("Hybrid search failed: " + query, e);
    }
  }

  private static void accumulate(Map<String, Acc> acc, StoredDoc d, String repoName, int rank) {
    String key = repoName + "\u0000" + d.path();
    double inc = 1.0 / (RRF_K + rank);
    Acc old = acc.get(key);
    if (old == null) {
      acc.put(key, new Acc(inc, repoName, d.path(), d.name(), d.categoryPath(), d.content()));
    } else {
      acc.put(
          key,
          new Acc(
              old.score() + inc, old.repo(), old.path(), old.name(), old.categoryPath(), old.content()));
    }
  }

  private static String highlight(SmartChineseAnalyzer analyzer, Query kwQuery, String content)
      throws java.io.IOException, InvalidTokenOffsetsException {
    if (content == null || content.isBlank()) {
      return "";
    }
    try (TokenStream ts = analyzer.tokenStream(RepoIndex.F_CONTENT, content)) {
      Highlighter hl = new Highlighter(new SimpleHTMLFormatter("<em>", "</em>"), new QueryScorer(kwQuery));
      String frag = hl.getBestFragment(ts, content);
      return frag != null ? frag : content.substring(0, Math.min(80, content.length()));
    }
  }
}
```

注：KNN 候选先取，BM25 后取——同一文档两路都命中时 RRF 分数相加（`accumulate` 合并）；高亮用各仓库的 keyword Query，KNN-only 命中的文档高亮退化为 content 前 80 字。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=HybridSearcherTest`
Expected: PASS（2 tests）

- [ ] **Step 5: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: rrf hybrid search with highlight across repos"
```

---

### Task 8: 分类器与置信度分流（classify 包）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/classify/Action.java`
- Create: `src/main/java/cn/guoxy/xunwen/classify/Classifier.java`
- Test: `src/test/java/cn/guoxy/xunwen/classify/ClassifierTest.java`

**Interfaces:**
- Consumes: `LlmClient`（Task 5）、`CategoryTree`（Task 2）、`GlobalConfig`（Task 3）
- Produces:
  - `enum Action { AUTO_MOVE, PENDING, UNKNOWN }`（**顶级**，独立文件 `Action.java`，包 `cn.guoxy.xunwen.classify`）
  - `record Outcome(Action action, String categoryPath, double confidence, String proposedCategory, String proposalReason) {}`
  - `Classifier(LlmClient llm, GlobalConfig cfg)`；`Outcome classify(String fileName, String contentPreview, boolean hasTextLayer, CategoryTree tree)`
  - LLM JSON 契约（response_format json_object）：`{"category":"父/子 或 空","candidates":[{"name":"...","score":0.9}],"self":0.8,"proposed":"提议的类别名或空","reason":"提议理由或空"}`
  - 合成：`margin = candidates.size() >= 2 ? clamp(s1 - s2, 0, 1) : 1.0`；`confidence = self * (0.5 + 0.5 * margin)`
  - 分流（FR-2）：`!hasTextLayer` → confidence 上限压到 `min(confidence, cfg.pendingThreshold())`；`category 命中树 && confidence >= autoThreshold` → AUTO_MOVE；`category 命中 && >= pendingThreshold` → PENDING；否则 UNKNOWN（proposed 照带）
  - 解析失败重试 1 次，再失败 → UNKNOWN 兜底（不抛异常，流水线不能因单个文件崩）

- [ ] **Step 1: 写失败测试**

```java
package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassifierTest {

  private static final CategoryTree TREE =
      new CategoryTree(
          List.of(
              new Category("工作文档", "", List.of(new Category("报告", "", List.of()))),
              new Category("其他", "", List.of())));

  private static LlmClient llmReturning(String json) {
    return msgs -> json;
  }

  @Test
  void highConfidenceAutoMove() {
    var llm = llmReturning("""
        {"category":"工作文档/报告","candidates":[
          {"name":"工作文档/报告","score":0.95},{"name":"其他","score":0.3}],"self":0.95}
        """);
    var out = new Classifier(llm, GlobalConfig.defaults())
        .classify("q4.pdf", "季度报告内容", true, TREE);
    assertEquals(Action.AUTO_MOVE, out.action());
    assertEquals("工作文档/报告", out.categoryPath());
  }

  @Test
  void narrowMarginDropsToPending() {
    // self=0.9, margin=0.1 → confidence=0.9*(0.5+0.05)=0.495 <0.5 → UNKNOWN; 用 margin=0.2 → 0.54 → PENDING
    var llm = llmReturning("""
        {"category":"工作文档/报告","candidates":[
          {"name":"工作文档/报告","score":0.6},{"name":"其他","score":0.4}],"self":0.9}
        """);
    var out = new Classifier(llm, GlobalConfig.defaults())
        .classify("q4.pdf", "内容", true, TREE);
    assertEquals(Action.PENDING, out.action()); // 0.9 * (0.5 + 0.5*0.2) = 0.54
  }

  @Test
  void noTextLayerCapsToPending() {
    var llm = llmReturning("""
        {"category":"工作文档","candidates":[
          {"name":"工作文档","score":0.99},{"name":"其他","score":0.1}],"self":0.99}
        """);
    var out = new Classifier(llm, GlobalConfig.defaults())
        .classify("scan.pdf", "", false, TREE); // 无文本层 → 压到 pendingThreshold 以下不行，是压到 <=0.85 才对
    assertEquals(Action.PENDING, out.action());
  }

  @Test
  void badJsonRetriesThenFallsBackToUnknown() {
    int[] calls = {0};
    LlmClient bad = msgs -> {
      calls[0]++;
      return calls[0] == 1 ? "不是json" : "{\"category\":\"\",\"candidates\":[],\"self\":0.1}";
    };
    var out = new Classifier(bad, GlobalConfig.defaults()).classify("f.bin", "x", true, TREE);
    assertEquals(Action.UNKNOWN, out.action());
    assertEquals(2, calls[0]);
  }

  @Test
  void proposalCarriedThrough() {
    var llm = llmReturning("""
        {"category":"","candidates":[],"self":0.2,
         "proposed":"发票凭证","reason":"多个餐饮发票文件"}
        """);
    var out = new Classifier(llm, GlobalConfig.defaults()).classify("f.pdf", "发票", true, TREE);
    assertEquals(Action.UNKNOWN, out.action());
    assertEquals("发票凭证", out.proposedCategory());
    assertEquals("多个餐饮发票文件", out.proposalReason());
  }
}
```

（注：`noTextLayerCapsToPending` 的期望按实现定义——cap 后 confidence 恰为 0.85 会落 AUTO_MOVE 边界，实现里 cap 用 `Math.min(confidence, pendingThreshold)` 即直接压到 0.5，稳定落 PENDING。测试注释中的推导以此为准。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=ClassifierTest`
Expected: 编译失败

- [ ] **Step 3: 最小实现**

`Action.java`：

```java
package cn.guoxy.xunwen.classify;

public enum Action {
  AUTO_MOVE,
  PENDING,
  UNKNOWN
}
```

`Classifier.java`：

```java
package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class Classifier {
  public record Outcome(
      Action action, String categoryPath, double confidence, String proposedCategory, String proposalReason) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int PREVIEW_CHARS = 3000;

  private final LlmClient llm;
  private final GlobalConfig cfg;

  public Classifier(LlmClient llm, GlobalConfig cfg) {
    this.llm = llm;
    this.cfg = cfg;
  }

  public Outcome classify(
      String fileName, String contentPreview, boolean hasTextLayer, CategoryTree tree) {
    String preview =
        contentPreview.length() > PREVIEW_CHARS
            ? contentPreview.substring(0, PREVIEW_CHARS)
            : contentPreview;
    String prompt = buildPrompt(fileName, preview, hasTextLayer, tree);
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        JsonNode node = JSON.readTree(llm.chat(List.of(
            LlmClient.Msg.system(SYSTEM_RULES), LlmClient.Msg.user(prompt))));
        return toOutcome(node, hasTextLayer, tree);
      } catch (Exception parseFailure) {
        if (attempt == 1) {
          return new Outcome(Action.UNKNOWN, "", 0.0, "", "");
        }
      }
    }
    return new Outcome(Action.UNKNOWN, "", 0.0, "", ""); // 不可达
  }

  private Outcome toOutcome(JsonNode node, boolean hasTextLayer, CategoryTree tree) {
    double self = node.path("self").asDouble(0.0);
    List<double[]> scored = new ArrayList<>(); // [score]
    JsonNode cands = node.path("candidates");
    for (JsonNode c : cands) {
      scored.add(new double[] {c.path("score").asDouble(0.0)});
    }
    double margin = scored.size() >= 2 ? clamp(scored.get(0)[0] - scored.get(1)[0]) : 1.0;
    double confidence = self * (0.5 + 0.5 * margin);
    if (!hasTextLayer) {
      confidence = Math.min(confidence, cfg.pendingThreshold());
    }
    String category = node.path("category").asText("").strip();
    boolean inTree = !category.isEmpty() && tree.find(category).isPresent();
    Action action =
        inTree && confidence >= cfg.autoThreshold() ? Action.AUTO_MOVE
        : inTree && confidence >= cfg.pendingThreshold() ? Action.PENDING
        : Action.UNKNOWN;
    return new Outcome(
        action,
        inTree ? category : "",
        confidence,
        node.path("proposed").asText("").strip(),
        node.path("reason").asText("").strip());
  }

  private static double clamp(double v) {
    return Math.max(0.0, Math.min(1.0, v));
  }

  private static String buildPrompt(
      String fileName, String preview, boolean hasTextLayer, CategoryTree tree) {
    return """
        请将文件归入给定类别体系（最多两级），只能从列表中选择，不得发明新名称。
        类别列表：
        %s
        文件名：%s
        内容可提取：%s
        内容预览：
        %s
        严格输出 JSON：{"category":"类别路径或空","candidates":[{"name":"类别路径","score":0到1}],"self":0到1,"proposed":"若列表都不合适，提议一个新的2到6字中文类别名，否则空串","reason":"提议理由或空串"}
        candidates 按分数降序给出2到3个候选。""".formatted(
            String.join("\n", tree.flattenPaths()), fileName, hasTextLayer, preview);
  }

  private static final String SYSTEM_RULES =
      "你是文件分类助手。只输出 JSON 对象，不输出任何其他文本。";
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=ClassifierTest`
Expected: PASS（5 tests）

- [ ] **Step 5: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: llm classifier with confidence synthesis and action routing"
```

---

### Task 9: 冷启动类别树生成（classify 包）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/classify/TreeProposer.java`
- Test: `src/test/java/cn/guoxy/xunwen/classify/TreeProposerTest.java`

**Interfaces:**
- Consumes: `LlmClient`（Task 5）、`CategoryTree`/`Category`（Task 2）
- Produces:
  - `record TreeProposer.Sample(String fileName, String contentPreview) {}`
  - `TreeProposer(LlmClient llm)`；`CategoryTree propose(List<Sample> samples)`
  - LLM JSON 契约：`{"categories":[{"name":"...","description":"...","children":[{"name":"...","description":"..."}]}]}`
  - 解析失败重试 1 次，再失败抛 `IllegalStateException`（冷启动是显式动作，失败要可见）
  - prompt 约束（spec 常量表）：顶层 ≤ 6、每级子类 ≤ 6、必含「其他」、名称 2–6 字中文

- [ ] **Step 1: 写失败测试**

```java
package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeProposerTest {

  @Test
  void parsesLlmTreeJson() {
    String json = """
        {"categories":[
          {"name":"工作文档","description":"工作相关","children":[
            {"name":"报告","description":"各类报告"}]},
          {"name":"其他","description":"兜底","children":[]}]}
        """;
    CategoryTree tree = new TreeProposer(msgs -> json).propose(List.of());
    assertEquals(List.of("工作文档", "工作文档/报告", "其他"), tree.flattenPaths());
  }

  @Test
  void retriesOnceThenSucceeds() {
    int[] calls = {0};
    LlmClient bad = msgs -> {
      calls[0]++;
      return calls[0] == 1 ? "oops" : "{\"categories\":[]}"; // 第二次成功即不抛
    };
    assertEquals(List.of(), new TreeProposer(bad).propose(List.of()).flattenPaths());
  }

  @Test
  void bothAttemptsFailThrows() {
    LlmClient alwaysBad = msgs -> "nope";
    assertThrows(IllegalStateException.class, () -> new TreeProposer(alwaysBad).propose(List.of()));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=TreeProposerTest`
Expected: 编译失败

- [ ] **Step 3: 最小实现**

```java
package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

public final class TreeProposer {
  public record Sample(String fileName, String contentPreview) {}

  private static final ObjectMapper JSON = new ObjectMapper();

  private final LlmClient llm;

  public TreeProposer(LlmClient llm) {
    this.llm = llm;
  }

  public CategoryTree propose(List<Sample> samples) {
    StringBuilder sb = new StringBuilder();
    for (Sample s : samples) {
      sb.append("文件：").append(s.fileName()).append('\n');
      String preview =
          s.contentPreview().length() > 500
              ? s.contentPreview().substring(0, 500)
              : s.contentPreview();
      sb.append("内容：").append(preview.replace('\n', ' ')).append("\n\n");
    }
    String prompt =
        """
        根据以下文件样本，归纳出一套中文资料分类体系（恰好两级）。
        要求：顶层不超过6个类别；每个类别下不超过6个子类；必须包含「其他」作为兜底顶层类别；
        名称2到6个汉字；每个类别配一句description。

        文件样本：
        %s
        严格输出 JSON：
        {"categories":[{"name":"...","description":"...","children":[{"name":"...","description":"..."}]}]}
        """
            .formatted(sb);
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        JsonNode node = JSON.readTree(llm.chat(List.of(LlmClient.Msg.user(prompt))));
        return parse(node);
      } catch (Exception e) {
        if (attempt == 1) {
          throw new IllegalStateException("类别树生成失败", e);
        }
      }
    }
    throw new IllegalStateException("unreachable");
  }

  private static CategoryTree parse(JsonNode node) {
    List<Category> roots = new ArrayList<>();
    for (JsonNode c : node.path("categories")) {
      List<Category> children = new ArrayList<>();
      for (JsonNode ch : c.path("children")) {
        children.add(new Category(ch.path("name").asText(), ch.path("description").asText(), List.of()));
      }
      roots.add(new Category(c.path("name").asText(), c.path("description").asText(), children));
    }
    return new CategoryTree(roots);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=TreeProposerTest`
Expected: PASS（3 tests）

- [ ] **Step 5: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: llm cold-start category tree proposer"
```

---

### Task 10: 文件移动引擎与操作日志（move 包）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/move/FileMover.java`
- Create: `src/main/java/cn/guoxy/xunwen/move/OperationLog.java`
- Test: `src/test/java/cn/guoxy/xunwen/move/FileMoverTest.java`
- Test: `src/test/java/cn/guoxy/xunwen/move/OperationLogTest.java`

**Interfaces:**
- Consumes: 无（纯 JDK）
- Produces:
  - `enum MoveResult { MOVED, RENAMED, DUPLICATE_SKIPPED }`
  - `record MoveOutcome(MoveResult result, Path dest, String hash) {}`
  - `FileMover(Path repoRoot)`；`MoveOutcome move(Path src, Path destDir) throws IOException`
    - SHA-256；destDir 下同名且同 hash → `DUPLICATE_SKIPPED`（不移动，src 留原处）
    - 同名不同 hash → `名字 (2).ext` 递增 → `RENAMED`
    - 移动用 `ATOMIC_MOVE`：同文件系统 rename(2) 零拷贝；跨挂载点抛异常快速失败（不静默 copy）
    - 移动后向上清理空目录（止于 repoRoot，不删 repoRoot/.xunwen/待分类 本身）
  - `record OperationLog.Op(String batchId, long ts, String kind, String src, String dest) {}`，`kind ∈ MOVE | REVERT`
  - `OperationLog(Path jsonl)`；`void append(Op)`；`List<Op> all()`；`List<Op> batch(String batchId)`
  - `void rollback(String batchId) throws IOException`：逆序执行该批 MOVE 的反向移动（REVERT 也 append 进日志），目标已存在则跳过该条

- [ ] **Step 1: 写失败测试（FileMover）**

```java
package cn.guoxy.xunwen.move;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMoverTest {

  @Test
  void movesFileAndCleansEmptyDirs(@TempDir Path root) throws Exception {
    Path src = Files.createDirectories(root.resolve("旧目录/更深")).resolve("a.txt");
    Files.writeString(src, "内容甲");
    var mover = new FileMover(root);
    var out = mover.move(src, root.resolve("工作文档"));
    assertEquals(MoveResult.MOVED, out.result());
    assertTrue(Files.exists(root.resolve("工作文档/a.txt")));
    assertFalse(Files.exists(root.resolve("旧目录"))); // 空壳目录已清理
  }

  @Test
  void sameNameDifferentContentGetsSuffix(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("工作文档"));
    Files.writeString(root.resolve("工作文档/发票.pdf"), "旧内容");
    Path src = root.resolve("新发票.pdf");
    Files.writeString(src, "新内容");
    var out = new FileMover(root).move(src, root.resolve("工作文档"));
    assertEquals(MoveResult.RENAMED, out.result());
    assertTrue(Files.exists(root.resolve("工作文档/发票 (2).pdf")));
  }

  @Test
  void identicalDuplicateIsSkipped(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("工作文档"));
    Files.writeString(root.resolve("工作文档/发票.pdf"), "相同内容");
    Path src = root.resolve("副本.pdf");
    Files.writeString(src, "相同内容");
    var out = new FileMover(root).move(src, root.resolve("工作文档"));
    assertEquals(MoveResult.DUPLICATE_SKIPPED, out.result());
    assertTrue(Files.exists(src)); // 原处保留
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=FileMoverTest`
Expected: 编译失败

- [ ] **Step 3: 实现 FileMover**

```java
package cn.guoxy.xunwen.move;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class FileMover {
  public enum MoveResult { MOVED, RENAMED, DUPLICATE_SKIPPED }

  public record MoveOutcome(MoveResult result, Path dest, String hash) {}

  private final Path repoRoot;

  public FileMover(Path repoRoot) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
  }

  public MoveOutcome move(Path src, Path destDir) throws IOException {
    String hash = sha256(src);
    Path absDestDir = destDir.toAbsolutePath().normalize();
    Files.createDirectories(absDestDir);
    String name = src.getFileName().toString();
    Path target = absDestDir.resolve(name);
    MoveResult result = MoveResult.MOVED;

    if (Files.exists(target)) {
      if (sha256(target).equals(hash)) {
        return new MoveOutcome(MoveResult.DUPLICATE_SKIPPED, target, hash); // FR-5
      }
      String base = name;
      String ext = "";
      int dot = name.lastIndexOf('.');
      if (dot > 0) {
        base = name.substring(0, dot);
        ext = name.substring(dot);
      }
      int i = 2;
      do {
        target = absDestDir.resolve(base + " (" + i + ")" + ext);
        i++;
      } while (Files.exists(target) && !sha256(target).equals(hash));
      if (Files.exists(target)) {
        return new MoveOutcome(MoveResult.DUPLICATE_SKIPPED, target, hash);
      }
      result = MoveResult.RENAMED;
    }

    // ATOMIC_MOVE：同文件系统 = rename(2) 零拷贝；跨挂载点直接抛异常快速失败，
    // 绝不静默退化为 copy+delete（失败由 FileProcessor 捕获跳过）
    Files.move(src, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    cleanupEmptyDirs(src.getParent());
    return new MoveOutcome(result, target, hash);
  }

  private void cleanupEmptyDirs(Path dir) throws IOException {
    Path cur = dir.toAbsolutePath().normalize();
    while (!cur.equals(repoRoot) && cur.startsWith(repoRoot)
        && !cur.getFileName().toString().equals(".xunwen")
        && !cur.getFileName().toString().equals("待分类")) {
      if (!isDirEmpty(cur)) {
        return;
      }
      Files.delete(cur);
      cur = cur.getParent();
    }
  }

  private static boolean isDirEmpty(Path dir) throws IOException {
    try (var stream = Files.list(dir)) {
      return stream.findAny().isEmpty();
    }
  }

  static String sha256(Path file) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
          md.update(buf, 0, n);
        }
      }
      StringBuilder sb = new StringBuilder();
      for (byte b : md.digest()) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
```

- [ ] **Step 4: 写失败测试（OperationLog）**

```java
package cn.guoxy.xunwen.move;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationLogTest {

  @Test
  void appendAndQueryBatch(@TempDir Path dir) throws Exception {
    var log = new OperationLog(dir.resolve("ops.jsonl"));
    log.append(new OperationLog.Op("b1", 1L, "MOVE", "a", "b"));
    log.append(new OperationLog.Op("b2", 2L, "MOVE", "c", "d"));
    assertEquals(2, log.all().size());
    assertEquals(1, log.batch("b1").size());
    assertEquals("a", log.batch("b1").get(0).src());
  }

  @Test
  void rollbackRestoresFilesAndAppendsRevert(@TempDir Path root) throws Exception {
    Path dirA = Files.createDirectories(root.resolve("a"));
    Path dirB = Files.createDirectories(root.resolve("b"));
    Path src = dirA.resolve("f.txt");
    Path dest = dirB.resolve("f.txt");
    Files.writeString(src, "x");
    var mover = new FileMover(root);
    mover.move(src, dirB); // a/f.txt -> b/f.txt（移动本身，日志由调用方记录）

    var log = new OperationLog(root.resolve("ops.jsonl"));
    log.append(new OperationLog.Op("b1", System.currentTimeMillis(), "MOVE", src.toString(), dest.toString()));

    log.rollback("b1");
    assertTrue(Files.exists(src));
    assertFalse(Files.exists(dest));
    assertTrue(log.all().stream().anyMatch(op -> op.kind().equals("REVERT")));
  }
}
```

- [ ] **Step 5: 跑测试确认失败，实现 OperationLog**

Run: `mvn -q test -Dtest=OperationLogTest`
Expected: 先编译失败；实现后 PASS

```java
package cn.guoxy.xunwen.move;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class OperationLog {
  public record Op(String batchId, long ts, String kind, String src, String dest) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path file;

  public OperationLog(Path file) {
    this.file = file;
  }

  public void append(Op op) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, JSON.writeValueAsString(op) + "\n", java.nio.charset.StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
  }

  public List<Op> all() throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<Op> out = new ArrayList<>();
    for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        out.add(JSON.readValue(line, Op.class));
      }
    }
    return out;
  }

  public List<Op> batch(String batchId) throws IOException {
    return all().stream().filter(op -> op.batchId().equals(batchId) && op.kind().equals("MOVE")).toList();
  }

  /** 逆序回滚一个批次（FR-4）。目标已存在的条目跳过。 */
  public void rollback(String batchId) throws IOException {
    List<Op> ops = batch(batchId);
    for (int i = ops.size() - 1; i >= 0; i--) {
      Op op = ops.get(i);
      Path src = Path.of(op.src());
      Path dest = Path.of(op.dest());
      if (!Files.exists(dest)) {
        continue;
      }
      Files.createDirectories(src.getParent());
      if (Files.exists(src)) {
        continue; // 源位已被占用，跳过
      }
      Files.move(dest, src, java.nio.file.StandardCopyOption.ATOMIC_MOVE); // 回滚同样零拷贝
      append(new Op(batchId, System.currentTimeMillis(), "REVERT", op.dest(), op.src()));
    }
  }
}
```

注意：`FileMover.move` 只移动不写日志——**日志由 pipeline 层写**（Task 11），`FileMover` 保持纯粹；但 Task 10 的 rollback 测试需要日志里有 MOVE 记录，所以 rollback 测试里手工 `log.append(...)` 模拟（修正上面测试：move 之后 `log.append(new OperationLog.Op("b1", System.currentTimeMillis(), "MOVE", dirA.resolve("f.txt").toString(), dirB.resolve("f.txt").toString()))` 再 rollback）。按此修正测试代码。

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -q test "-Dtest=FileMoverTest,OperationLogTest"`
Expected: PASS（3 + 2 tests）

- [ ] **Step 7: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: file mover with dedup/suffix and batch operation log with rollback"
```

---

### Task 11: 单文件处理器与首次重排编排（pipeline 包）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/pipeline/FileProcessor.java`
- Create: `src/main/java/cn/guoxy/xunwen/pipeline/ReshuffleTask.java`
- Test: `src/test/java/cn/guoxy/xunwen/pipeline/ReshuffleTaskTest.java`

**Interfaces:**
- Consumes: `ContentExtractor`(T4) · `LlmClient`/`EmbeddingClient`(T5) · `RepoIndex`/`Doc`(T6) · `Action`/`Classifier`/`Outcome`(T8) · `FileMover`/`MoveResult`/`OperationLog`(T10) · `RepoPaths`/`CategoryTree`(T2) · `GlobalConfig`(T3)
- Produces:
  - `FileProcessor(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor)`
    - `void process(Path file, CategoryTree tree, String batchId, RepoIndex index) throws IOException`
    - 单文件流程：索引已含该路径（POSIX 相对路径）→ 跳过（中断续跑）；提取 → 分类 → 分流移动（`AUTO_MOVE`→`repoRoot/<categoryPath>`，否则→`待分类/`）→ `log.append(MOVE)` → `index.upsert`
    - `DUPLICATE_SKIPPED` → 不移动不入索引（FR-5）；移动/提取抛 `IOException`（Windows 文件占用等）→ 捕获记 warn 跳过，**不向上抛**（跨平台约束）
    - embedding 失败 → 零向量 `new float[cfg.vectorDim()]` 兜底，不阻断入库；文本截前 4000 字
  - `ReshuffleTask(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor)`
    - `interface Progress { void onProgress(int done, int total, String currentFile); boolean cancelled(); static Progress noop(); }`
    - `String run(Progress progress) throws IOException` → batchId（`"reshuffle-" + ts`）；类别树文件缺失 → `IllegalStateException("先完成冷启动")`；遍历全仓库（跳过 `.xunwen/`、`待分类/`、`.`/`~$` 开头文件）；`cancelled()` 立即停止，已完成部分保留（批次可回滚）

- [ ] **Step 1: 写失败测试**

```java
package cn.guoxy.xunwen.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.move.OperationLog;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReshuffleTaskTest {

  private static final GlobalConfig CFG =
      new GlobalConfig("http://localhost:9/v1", "", "m", "e", 4, 0.85, 0.5, 2);

  /** fake LLM：分类一律高置信「工作文档」 */
  private static LlmClient fakeLlm() {
    return msgs -> """
        {"category":"工作文档","candidates":[
          {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
        """;
  }

  private static ContentExtractor fakeExtractor() {
    return file -> new ContentExtractor.Extracted("测试内容足够长的一些文字用于通过文本层判断逻辑", true);
  }

  @Test
  void reshufflesHighConfidenceIntoCategory(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("报告.txt"), "内容");
    Path nested = Files.createDirectories(root.resolve("手动目录")).resolve("a.pdf");
    Files.writeString(nested, "pdf 内容");

    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));

    var task = new ReshuffleTask(root, CFG, fakeLlm(), fakeExtractor());
    String batch = task.run(ReshuffleTask.Progress.noop());

    assertTrue(Files.exists(root.resolve("工作文档/报告.txt")));
    assertTrue(Files.exists(root.resolve("工作文档/a.pdf")));
    assertFalse(Files.exists(root.resolve("手动目录"))); // 空壳目录已清理
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      assertTrue(idx.findByPath("工作文档/报告.txt").isPresent());
    }
    var log = new OperationLog(RepoPaths.operationsJsonl(root));
    assertEquals(2, log.batch(batch).size());
    log.rollback(batch); // FR-4：批次回滚还原
    assertTrue(Files.exists(root.resolve("报告.txt")));
    assertTrue(Files.exists(nested));
  }

  @Test
  void lowConfidenceGoesToPendingDir(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("神秘文件.bin"), "短");
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    LlmClient unknown = msgs -> "{\"category\":\"\",\"candidates\":[],\"self\":0.1}";
    new ReshuffleTask(root, CFG, unknown, fakeExtractor()).run(ReshuffleTask.Progress.noop());
    assertTrue(Files.exists(RepoPaths.pendingDir(root).resolve("神秘文件.bin")));
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      assertEquals("UNKNOWN", idx.findByPath("待分类/神秘文件.bin").orElseThrow().status());
    }
  }

  @Test
  void missingTreeFails(@TempDir Path root) throws Exception {
    var task = new ReshuffleTask(root, CFG, fakeLlm(), fakeExtractor());
    assertThrows(IllegalStateException.class, () -> task.run(ReshuffleTask.Progress.noop()));
  }

  @Test
  void alreadyIndexedFileIsSkippedOnRerun(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("报告.txt"), "内容");
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    var task = new ReshuffleTask(root, CFG, fakeLlm(), fakeExtractor());
    task.run(ReshuffleTask.Progress.noop());
    // 中断续跑：第二次运行不重复移动（文件已在类别目录且已索引）
    assertDoesNotThrow(() -> task.run(ReshuffleTask.Progress.noop()));
    assertTrue(Files.exists(root.resolve("工作文档/报告.txt")));
  }
}
```

注意：`Doc.path` 约定为**相对 repoRoot 的 POSIX 风格路径**（`待分类/神秘文件.bin`），`findByPath` 按此断言。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=ReshuffleTaskTest`
Expected: 编译失败

- [ ] **Step 3: 实现 FileProcessor 与 ReshuffleTask**

`FileProcessor.java`：

```java
package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.classify.Action;
import cn.guoxy.xunwen.classify.Classifier;
import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.Doc;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.move.FileMover;
import cn.guoxy.xunwen.move.OperationLog;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 单文件处理：提取 → 分类 → 置信度分流移动 → 写日志 → 写索引（FR-1/2/3）。 */
public final class FileProcessor {
  private static final int EMBED_CHARS = 4000;

  private final Path repoRoot;
  private final GlobalConfig cfg;
  private final Classifier classifier;
  private final EmbeddingClient embedder;
  private final ContentExtractor extractor;
  private final FileMover mover;
  private final OperationLog log;

  public FileProcessor(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
    this.cfg = cfg;
    this.classifier = new Classifier(llm, cfg);
    this.embedder = llm instanceof EmbeddingClient ec ? ec : text -> new float[cfg.vectorDim()];
    this.extractor = extractor;
    this.mover = new FileMover(this.repoRoot);
    this.log = new OperationLog(RepoPaths.operationsJsonl(this.repoRoot));
  }

  public void process(Path file, CategoryTree tree, String batchId, RepoIndex index) {
    String name = file.getFileName().toString();
    String relPath = posix(repoRoot.relativize(file.toAbsolutePath().normalize()));
    try {
      if (index.findByPath(relPath).isPresent()) {
        return; // 已入库（中断续跑）
      }
      ContentExtractor.Extracted ex = extractor.extract(file);
      Classifier.Outcome outcome = classifier.classify(name, ex.text(), ex.hasTextLayer(), tree);

      Path destDir =
          outcome.action() == Action.AUTO_MOVE
              ? repoRoot.resolve(outcome.categoryPath())
              : RepoPaths.pendingDir(repoRoot);
      FileMover.MoveOutcome moved = mover.move(file, destDir);
      if (moved.result() == FileMover.MoveResult.DUPLICATE_SKIPPED) {
        return; // 重复副本留在原处（FR-5），不入索引
      }
      Path actual = moved.dest();
      log.append(new OperationLog.Op(
          batchId, System.currentTimeMillis(), "MOVE", file.toString(), actual.toString()));

      String status =
          switch (outcome.action()) {
            case AUTO_MOVE -> "INDEXED";
            case PENDING -> "PENDING";
            case UNKNOWN -> "UNKNOWN";
          };
      String text = ex.text();
      String embedText = text.length() > EMBED_CHARS ? text.substring(0, EMBED_CHARS) : text;
      index.upsert(new Doc(
          posix(repoRoot.relativize(actual)),
          actual.getFileName().toString(),
          ext(name),
          moved.hash(),
          Files.getLastModifiedTime(actual).toMillis(),
          Files.size(actual),
          status,
          outcome.action() == Action.AUTO_MOVE ? outcome.categoryPath() : "",
          text,
          embedSafely(embedText)));
    } catch (IOException e) {
      // Windows 占用等移动失败：本批跳过，下轮再处理，不让整批任务崩
      System.err.println("[xunwen] 跳过无法处理的文件: " + relPath + " (" + e + ")");
    }
  }

  private float[] embedSafely(String text) {
    try {
      return embedder.embed(text);
    } catch (RuntimeException e) { // Jackson 3 unchecked / HTTP 异常
      return new float[cfg.vectorDim()];
    }
  }

  static String posix(Path p) {
    return p.toString().replace('\\', '/');
  }

  static String ext(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(dot + 1) : "";
  }
}
```

`ReshuffleTask.java`：

```java
package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class ReshuffleTask {
  public interface Progress {
    void onProgress(int done, int total, String currentFile);

    boolean cancelled();

    static Progress noop() {
      return (done, total, file) -> {};
    }
  }

  private final Path repoRoot;
  private final GlobalConfig cfg;
  private final LlmClient llm;
  private final ContentExtractor extractor;

  public ReshuffleTask(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
    this.cfg = cfg;
    this.llm = llm;
    this.extractor = extractor;
  }

  public String run(Progress progress) throws IOException {
    Path treeYaml = RepoPaths.categoriesYaml(repoRoot);
    if (!Files.exists(treeYaml)) {
      throw new IllegalStateException("先完成冷启动：缺少 " + treeYaml);
    }
    CategoryTree tree = CategoryTree.load(treeYaml);
    String batchId = "reshuffle-" + System.currentTimeMillis();
    FileProcessor processor = new FileProcessor(repoRoot, cfg, llm, extractor);

    List<Path> files = listFiles();
    int done = 0;
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot), cfg.vectorDim())) {
      for (Path file : files) {
        if (progress.cancelled()) {
          break; // 已完成的保留，批次仍可回滚（FR-3/FR-4）
        }
        progress.onProgress(done++, files.size(), FileProcessor.posix(repoRoot.relativize(file)));
        processor.process(file, tree, batchId, index);
      }
    }
    return batchId;
  }

  private List<Path> listFiles() throws IOException {
    Path xunwen = RepoPaths.xunwenDir(repoRoot);
    Path pending = RepoPaths.pendingDir(repoRoot);
    try (Stream<Path> walk = Files.walk(repoRoot)) {
      return walk
          .filter(Files::isRegularFile)
          .filter(p -> !p.startsWith(xunwen) && !p.startsWith(pending))
          .filter(p -> {
            String n = p.getFileName().toString();
            return !n.startsWith(".") && !n.startsWith("~$");
          })
          .toList();
    }
  }
}
```

（若编译器报 `UncheckedIOException` import 未用则删除——异常处理只 catch `IOException`；FileMover 的 Files.move 抛 checked IOException，已被 catch 覆盖。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=ReshuffleTaskTest`
Expected: PASS（4 tests）

- [ ] **Step 5: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: file processor and full-repo reshuffle with confidence routing and batch rollback"
```

---

### Task 12: 增量入库、积压同步与类别提议（pipeline + classify 包）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/pipeline/IngestTask.java`
- Create: `src/main/java/cn/guoxy/xunwen/classify/CategoryProposer.java`
- Test: `src/test/java/cn/guoxy/xunwen/pipeline/IngestTaskTest.java`
- Test: `src/test/java/cn/guoxy/xunwen/classify/CategoryProposerTest.java`

**Interfaces:**
- Consumes: `FileProcessor`（Task 11）· `RepoIndex.allFiles()`/`FileInfo`（Task 6）· 其余前序组件
- Produces:
  - `IngestTask(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor)`
  - `List<Path> ingest(List<Path> newFiles) throws IOException`：对每个新文件执行单文件流程（复用 `FileProcessor`），batchId `"ingest-" + ts`，返回处理过的文件列表（watch 回调路径）
  - `int sync() throws IOException`：**FR-12 积压/漂移修正**，以文件系统为准 diff 索引，batchId `"sync-" + ts`：
    - 文件系统有、索引无 → `processor.process()`（新文件入库）
    - 索引有、文件系统无 → `index.deleteByPath()`（外部删除）
    - 都有但 mtime/size 指纹不同 → `deleteByPath` + `process`（外部修改，重新提取重分类）
    - 返回处理条数。GUI 打开时调用一次（积压），watch 期间无需全量 diff
  - `record CategoryProposer.Proposal(String name, String reason, int fileCount) {}`
  - `CategoryProposer(EmbeddingClient embed)`；`List<Proposal> aggregate(List<Outcome> unknownOutcomes, CategoryTree tree)`
    - 只取 `proposedCategory` 非空的条目；同名直接合并
    - 相似拒绝（FR-6）：提议名 embedding 与任一现有类别路径 embedding 余弦 > 0.85 → 丢弃该提议（文件已由 Classifier 归 UNKNOWN，重跑分类会命中该现有类别）
    - 提议间余弦 > 0.85 → 聚合到首个（fileCount 累加，reason 取首个）
    - 返回按 fileCount 降序
  - `static double CategoryProposer.cosine(float[] a, float[] b)`

- [ ] **Step 1: 写失败测试（CategoryProposer）**

```java
package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.classify.Classifier.Outcome;
import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CategoryProposerTest {

  /** 确定性 embedding：词 -> 向量 */
  private static EmbeddingClient vec(Map<String, float[]> table) {
    return new EmbeddingClient() {
      @Override
      public float[] embed(String text) {
        return table.getOrDefault(text, new float[] {0, 0, 0, 1});
      }

      @Override
      public List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
      }
    };
  }

  private static Outcome unknown(String proposed) {
    return new Outcome(Action.UNKNOWN, "", 0.1, proposed, "理由");
  }

  @Test
  void mergesIdenticalProposals() {
    var tree = new CategoryTree(List.of(new Category("工作文档", "", List.of())));
    Map<String, float[]> table = new HashMap<>();
    table.put("发票凭证", new float[] {1, 0, 0, 0});
    table.put("工作文档", new float[] {0, 1, 0, 0});
    var proposer = new CategoryProposer(vec(table));
    var out = proposer.aggregate(List.of(unknown("发票凭证"), unknown("发票凭证")), tree);
    assertEquals(1, out.size());
    assertEquals(2, out.get(0).fileCount());
  }

  @Test
  void rejectsProposalSimilarToExistingCategory() {
    var tree = new CategoryTree(List.of(new Category("工作文档", "", List.of())));
    Map<String, float[]> table = new HashMap<>();
    table.put("工作资料", new float[] {0.99f, 0.1f, 0, 0}); // 与 工作文档 近
    table.put("工作文档", new float[] {1, 0, 0, 0});
    var out = new CategoryProposer(vec(table))
        .aggregate(List.of(unknown("工作资料")), tree);
    assertTrue(out.isEmpty()); // 相似拒绝（FR-6）
  }

  @Test
  void aggregatesSemanticallyCloseProposals() {
    var tree = new CategoryTree(List.of(new Category("工作文档", "", List.of())));
    Map<String, float[]> table = new HashMap<>();
    table.put("发票凭证", new float[] {1, 0, 0, 0});
    table.put("票据发票", new float[] {0.95f, 0.3f, 0, 0}); // cos>0.85
    table.put("工作文档", new float[] {0, 1, 0, 0});
    var out = new CategoryProposer(vec(table))
        .aggregate(List.of(unknown("发票凭证"), unknown("票据发票")), tree);
    assertEquals(1, out.size());
    assertEquals("发票凭证", out.get(0).name());
    assertEquals(2, out.get(0).fileCount());
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=CategoryProposerTest`
Expected: 编译失败

- [ ] **Step 3: 实现 CategoryProposer**

```java
package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.classify.Classifier.Outcome;
import cn.guoxy.xunwen.llm.EmbeddingClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CategoryProposer {
  public record Proposal(String name, String reason, int fileCount) {}

  static final double SIMILAR_THRESHOLD = 0.85;

  private final EmbeddingClient embed;

  public CategoryProposer(EmbeddingClient embed) {
    this.embed = embed;
  }

  public List<Proposal> aggregate(List<Outcome> unknownOutcomes, CategoryTree tree) {
    // 1. 同名合并
    Map<String, List<Outcome>> byName = new LinkedHashMap<>();
    for (Outcome o : unknownOutcomes) {
      if (!o.proposedCategory().isBlank()) {
        byName.computeIfAbsent(o.proposedCategory(), k -> new ArrayList<>()).add(o);
      }
    }
    // 2. 相似拒绝
    Map<String, float[]> existing = new LinkedHashMap<>();
    for (String p : tree.flattenPaths()) {
      existing.put(p, embed.embed(p));
    }
    List<Proposal> kept = new ArrayList<>();
    for (var entry : byName.entrySet()) {
      float[] vec = embed.embed(entry.getKey());
      boolean similarToExisting = existing.values().stream().anyMatch(v -> cosine(vec, v) > SIMILAR_THRESHOLD);
      if (similarToExisting) {
        continue;
      }
      kept.add(new Proposal(entry.getKey(), entry.getValue().get(0).proposalReason(), entry.getValue().size()));
    }
    // 3. 提议间聚合（贪心：与已保留项相似则并入）
    List<Proposal> merged = new ArrayList<>();
    List<float[]> mergedVecs = new ArrayList<>();
    for (Proposal p : kept) {
      float[] vec = embed.embed(p.name());
      boolean mergedIntoExisting = false;
      for (int i = 0; i < merged.size(); i++) {
        if (cosine(vec, mergedVecs.get(i)) > SIMILAR_THRESHOLD) {
          Proposal m = merged.get(i);
          merged.set(i, new Proposal(m.name(), m.reason(), m.fileCount() + p.fileCount()));
          mergedIntoExisting = true;
          break;
        }
      }
      if (!mergedIntoExisting) {
        merged.add(p);
        mergedVecs.add(vec);
      }
    }
    return merged.stream().sorted((a, b) -> Integer.compare(b.fileCount(), a.fileCount())).toList();
  }

  public static double cosine(float[] a, float[] b) {
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < a.length && i < b.length; i++) {
      dot += a[i] * b[i];
      na += (double) a[i] * a[i];
      nb += (double) b[i] * b[i];
    }
    return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
  }
}
```

- [ ] **Step 4: 写失败测试（IngestTask）**

```java
package cn.guoxy.xunwen.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestTaskTest {

  private static final GlobalConfig CFG =
      new GlobalConfig("http://localhost:9/v1", "", "m", "e", 4, 0.85, 0.5, 2);

  private static LlmClient fakeLlm() {
    return msgs -> """
        {"category":"工作文档","candidates":[
          {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
        """;
  }

  private static ContentExtractor fakeExtractor() {
    return file -> new ContentExtractor.Extracted("测试内容足够长的一些文字用于通过文本层判断逻辑", true);
  }

  private static Path prepareRepo(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    return root;
  }

  @Test
  void ingestMovesAndIndexesNewFile(@TempDir Path root) throws Exception {
    prepareRepo(root);
    Path f = root.resolve("新通知.txt");
    Files.writeString(f, "又一份通知");
    new IngestTask(root, CFG, fakeLlm(), fakeExtractor()).ingest(List.of(f));
    assertTrue(Files.exists(root.resolve("工作文档/新通知.txt")));
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      assertTrue(idx.findByPath("工作文档/新通知.txt").isPresent());
    }
  }

  @Test
  void syncIndexesBacklogAndDropsDeleted(@TempDir Path root) throws Exception {
    prepareRepo(root);
    Path a = root.resolve("已入库.txt");
    Files.writeString(a, "旧");
    var task = new IngestTask(root, CFG, fakeLlm(), fakeExtractor());
    task.ingest(List.of(a));

    // 积压：新文件未经 ingest
    Path b = root.resolve("积压文件.txt");
    Files.writeString(b, "新");
    // 外部修改：内容变化 → mtime/size 变化
    Files.writeString(root.resolve("工作文档/已入库.txt"), "内容变长了很多之后指纹就不同了");
    // 外部删除：直接删掉一个已索引文件
    Files.delete(root.resolve("工作文档/已入库.txt"));
    Path c = root.resolve("将删除.txt");
    Files.writeString(c, "x");
    task.ingest(List.of(c));
    Files.delete(c);

    int processed = task.sync();
    assertTrue(processed >= 2); // 积压入库 + 删除清理（修改的文件被重处理也算）
    assertTrue(Files.exists(root.resolve("工作文档/积压文件.txt")));
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      assertTrue(idx.findByPath("工作文档/积压文件.txt").isPresent());
      assertTrue(idx.findByPath("工作文档/已入库.txt").isEmpty()); // 外部删除 → 索引清理
      assertTrue(idx.findByPath("工作文档/将删除.txt").isEmpty());
    }
  }
}
```

- [ ] **Step 5: 实现 IngestTask**

```java
package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.FileInfo;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 增量入库与积压同步（FR-12：以文件系统为准修正索引）。 */
public final class IngestTask {
  private final Path repoRoot;
  private final GlobalConfig cfg;
  private final FileProcessor processor;

  public IngestTask(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
    this.cfg = cfg;
    this.processor = new FileProcessor(this.repoRoot, cfg, llm, extractor);
  }

  public List<Path> ingest(List<Path> newFiles) throws IOException {
    CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(repoRoot));
    String batchId = "ingest-" + System.currentTimeMillis();
    List<Path> done = new ArrayList<>();
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot), cfg.vectorDim())) {
      for (Path f : newFiles) {
        processor.process(f, tree, batchId, index);
        done.add(f);
      }
    }
    return done;
  }

  public int sync() throws IOException {
    CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(repoRoot));
    String batchId = "sync-" + System.currentTimeMillis();
    // 文件系统侧指纹（POSIX 相对路径 → [mtime, size]）
    Map<String, long[]> fs = new HashMap<>();
    for (Path p : walkFiles()) {
      FileTime ft = Files.getLastModifiedTime(p);
      fs.put(FileProcessor.posix(repoRoot.relativize(p)), new long[] {ft.toMillis(), Files.size(p)});
    }
    int processed = 0;
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot), cfg.vectorDim())) {
      // 1. 索引有、文件系统无 → 清理；指纹不同 → 删旧记录待重处理
      for (FileInfo info : index.allFiles()) {
        long[] fp = fs.get(info.path());
        if (fp == null) {
          index.deleteByPath(info.path());
          processed++;
        } else if (fp[0] != info.mtime() || fp[1] != info.size()) {
          index.deleteByPath(info.path()); // 下面的 process 会重新提取入库
        }
      }
      // 2. 文件系统有、索引无 → 入库
      for (Path p : walkFiles()) {
        String rel = FileProcessor.posix(repoRoot.relativize(p));
        if (index.findByPath(rel).isEmpty()) {
          processor.process(p, tree, batchId, index);
          processed++;
        }
      }
    }
    return processed;
  }

  private List<Path> walkFiles() throws IOException {
    Path xunwen = RepoPaths.xunwenDir(repoRoot);
    Path pending = RepoPaths.pendingDir(repoRoot);
    try (var walk = Files.walk(repoRoot)) {
      return walk
          .filter(Files::isRegularFile)
          .filter(p -> !p.startsWith(xunwen) && !p.startsWith(pending))
          .filter(p -> {
            String n = p.getFileName().toString();
            return !n.startsWith(".") && !n.startsWith("~$");
          })
          .toList();
    }
  }
}
```

- [ ] **Step 6: 跑全部测试确认通过**

Run: `mvn -q test`
Expected: PASS（含此前全部任务）

- [ ] **Step 7: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: incremental ingest, backlog sync and deduplicated category proposals"
```

---

### Task 13: 目录监视（watch 包）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/watch/RepoWatcher.java`
- Test: `src/test/java/cn/guoxy/xunwen/watch/RepoWatcherTest.java`

**Interfaces:**
- Consumes: JDK WatchService
- Produces:
  - `RepoWatcher(Path root, java.time.Duration stableWindow) implements AutoCloseable`（root 需存在）
  - `void start()`：注册根目录及全部既有子目录（`MAX_DEPTH` 足够深：注册时 `Files.walk` 递归 register；新目录出现时（`ENTRY_CREATE` 且是目录）同样 register）
  - `void ignorePrefix(Path p)`：回环过滤（FR-11）——路径字符串以此开头的事件丢弃；pipeline 移动完成后对移动目标与源调用
  - `List<Path> drainStable()`：返回自上次 drain 以来「已稳定」的文件列表。稳定性判定（spec 常量表）：事件先进入 pending map（path → size+mtime 快照与最后事件时刻）；`drainStable` 被调用时对每个 pending 项再采样一次，若 size+mtime 与快照相同且 `now - lastModified > stableWindow` → 判稳定并移出 pending
  - `void close()`：停线程、关 WatchService

  设计说明：**不自起后台线程**——由调用方（UI/调度层）周期调用 `pollEvents()`（内部 `watchService.poll()` 非阻塞）+ `drainStable()`。这样测试免线程同步，UI 侧用 JavaFX Timeline 周期驱动。`pollEvents()` 将 CREATE/MODIFY 事件的文件路径收进 pending。

- [ ] **Step 1: 写失败测试**

```java
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
      Thread.sleep(300); // 等待 OS 事件
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
      Thread.sleep(300);
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
      Thread.sleep(300);
      w.pollEvents();
      assertTrue(w.drainStable().isEmpty());
    }
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=RepoWatcherTest`
Expected: 编译失败

- [ ] **Step 3: 实现 RepoWatcher**

```java
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
  private record Snapshot(long size, long mtimeMs, long seenAtMs) {}

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
            watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
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
                new Snapshot(
                    Files.size(child),
                    Files.getLastModifiedTime(child).toMillis(),
                    System.currentTimeMillis()));
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
        boolean windowPassed =
            System.currentTimeMillis() - mtime >= stableWindow.toMillis();
        if (unchanged && windowPassed) {
          stable.add(p);
          it.remove();
        } else {
          it.remove(); // 变化过，等下一次事件重新进入 pending
        }
      } catch (IOException e) {
        it.remove(); // 文件已消失
      }
    }
    return stable;
  }

  private boolean isIgnored(Path p) {
    String s = p.toString();
    String name = p.getFileName().toString();
    return name.startsWith(".") || name.startsWith("~$")
        || ignoredPrefixes.stream().anyMatch(s::startsWith);
  }

  @Override
  public void close() throws IOException {
    watchService.close();
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=RepoWatcherTest`
Expected: PASS（3 tests）

- [ ] **Step 5: 格式化 + 全量验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: recursive repo watcher with stability detection and loop filter"
```

---

### Task 14: 端到端冒烟测试（全流程）

**Files:**
- Create: `src/test/java/cn/guoxy/xunwen/EndToEndSmokeTest.java`

**Interfaces:**
- Consumes: 全部前序任务
- Produces: 一条跑通「注册 → 冷启动 → 重排 → 增量 → 搜索 → 回滚」的测试，作为引擎的回归门

- [ ] **Step 1: 写测试**

```java
package cn.guoxy.xunwen;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.classify.TreeProposer;
import cn.guoxy.xunwen.classify.TreeProposer.Sample;
import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.HybridSearcher;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.move.OperationLog;
import cn.guoxy.xunwen.pipeline.IngestTask;
import cn.guoxy.xunwen.pipeline.ReshuffleTask;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EndToEndSmokeTest {

  /** fake LLM：冷启动返回固定树；分类按文件名关键词分流（发票→UNKNOWN+提议，其余→高置信工作文档） */
  private static LlmClient fakeLlm() {
    return msgs -> {
      String user = msgs.get(msgs.size() - 1).content();
      if (user.contains("归纳出一套中文资料分类体系")) {
        return """
            {"categories":[
              {"name":"工作文档","description":"d","children":[{"name":"报告","description":"d"}]},
              {"name":"其他","description":"d","children":[]}]}
            """;
      }
      if (user.contains("发票")) {
        return """
            {"category":"","candidates":[],"self":0.1,
             "proposed":"发票凭证","reason":"餐饮发票"}
            """;
      }
      return """
          {"category":"工作文档","candidates":[
            {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
          """;
    };
  }

  private static final ContentExtractor EXTRACTOR =
      file -> new ContentExtractor.Extracted("这是一段足够长且包含主题词的测试文本内容用于提取", true);

  @Test
  void fullPipelineColdStartReshuffleIngestSearchRollback(@TempDir Path root) throws Exception {
    // 1. 造仓库：3 个文件
    Files.writeString(root.resolve("季度报告.txt"), "销售报告内容");
    Files.writeString(root.resolve("年会通知.txt"), "通知内容");
    Path sub = Files.createDirectories(root.resolve("旧结构")).resolve("发票.txt");
    Files.writeString(sub, "发票");

    GlobalConfig cfg =
        new GlobalConfig("http://localhost:9/v1", "", "m", "e", 4, 0.85, 0.5, 2); // 4 维便于测试
    LlmClient llm = fakeLlm();

    // 2. 冷启动
    CategoryTree tree = new TreeProposer(llm).propose(List.of(new Sample("x.txt", "内容")));
    tree.save(RepoPaths.categoriesYaml(root));
    assertEquals(List.of("工作文档", "工作文档/报告", "其他"), tree.flattenPaths());

    // 3. 首次重排
    var reshuffle = new ReshuffleTask(root, cfg, llm, EXTRACTOR);
    String batch = reshuffle.run(ReshuffleTask.Progress.noop());
    assertTrue(Files.exists(root.resolve("工作文档/季度报告.txt")));
    assertTrue(Files.exists(RepoPaths.pendingDir(root).resolve("发票.txt"))); // UNKNOWN → 待分类

    // 4. 增量入库
    Path newFile = root.resolve("新通知.txt");
    Files.writeString(newFile, "又一份通知");
    new IngestTask(root, cfg, llm, EXTRACTOR).ingest(List.of(newFile));
    assertTrue(Files.exists(root.resolve("工作文档/新通知.txt")));

    // 5. 混合搜索（fake embedding 由零向量兜底，关键词路生效）
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      var indices = new LinkedHashMap<String, RepoIndex>();
      indices.put("测试仓库", idx);
      var hits = HybridSearcher.search(indices, "通知", new float[] {0, 0, 0, 0}, 10);
      assertTrue(hits.size() >= 2);
      assertTrue(hits.stream().allMatch(h -> h.path().contains("通知")));
    }

    // 6. 批次回滚：重排前结构还原
    new OperationLog(RepoPaths.operationsJsonl(root)).rollback(batch);
    assertTrue(Files.exists(root.resolve("季度报告.txt")));
    assertTrue(Files.exists(sub)); // 旧结构里的发票.txt 回到原位
  }
}
```

注意：fake 的 embedding——`ReshuffleTask`/`IngestTask` 构造里 `llm instanceof EmbeddingClient` 不成立时用零向量兜底，KNN 路对零向量无贡献，关键词路独立生效，符合测试预期。

- [ ] **Step 2: 跑测试确认通过（一次跑通；失败则修 pipeline 缺陷，不在测试上让步）**

Run: `mvn -q test -Dtest=EndToEndSmokeTest`
Expected: PASS

- [ ] **Step 3: 全量回归 + 格式化 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "test: end-to-end engine smoke test (cold start, reshuffle, ingest, search, rollback)"
```

---

## 后续

Plan B（JavaFX UI）在 Plan A 合入后另行编写：仓库管理、冷启动审批、待审队列、搜索、设置五个页面，复用本计划的引擎接口，UI 层零业务逻辑。
