# xunwen JavaFX UI 实现计划（Plan B）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Plan A 核心引擎接上 JavaFX 桌面界面：仓库管理、类别树（冷启动/重排/回滚）、待审队列（审批+提议）、混合搜索、设置五个页面，中文 UI，watch 生命周期由 UI 驱动。

**Architecture:** 单窗口 BorderPane：左侧导航栏切换右侧 StackPane 中的子视图（FXML + Controller）。共享状态集中在 `AppState`（JavaFX Property）。所有引擎 I/O 走后台线程（`Fx.runAsync` 包装），结果回 FX 线程更新控件。watch 由 `Timeline`（2s tick）驱动，移动落点经 `ignorePrefix` 防回环。可测逻辑全部下沉引擎层（Task B1/B2）或纯静态方法（Highlighter），Controller 薄壳不测（无 TestFX）。

**Tech Stack:** JavaFX 27（pom 已有全栈；atlantaFX/ikonli/controlsfx v1 **不接线**，避免外观依赖阻塞功能）· JUnit 5 · 引擎接口（Plan A 全部）

**Spec:** `docs/xunwen-v1-spec.md` · 引擎计划 `docs/superpowers/plans/2026-10-20-xunwen-core-engine.md`（Plan A 已合入，接口以其为准）

## Global Constraints

- JavaFX 线程铁律：**引擎任何 I/O（提取/LLM/索引/移动/watch）不得在 FX Application Thread 上调用**；一律 `Fx.runAsync(...)`，回调内才碰控件
- UI 文案全部中文；代码标识符英文
- 不引入 TestFX/Mockito；每个任务的测试 = 引擎层/纯逻辑的 JUnit + Controller 的手工验证清单
- 跳过 atlantaFX/ikonli/controlsfx 接线（依赖已在 pom 但不使用；外观是润色不是功能）
- 打包：v1 以 `mvn javafx:run` 运行为准，jpackage 不做（spec 非目标）
- 提交前 `mvn -q spotless:apply && mvn -q verify`；英文 conventional commits

---

### Task B1: 引擎适配——移动落点、状态查询、冷启动采样

Plan A 的接口有三处 UI 驱动不了的缺口：ingest 不返回落点（无法 ignorePrefix 防回环）、索引无法按 status 查待审列表、冷启动采样逻辑散落。本任务补齐。

**Files:**
- Modify: `src/main/java/cn/guoxy/xunwen/pipeline/FileProcessor.java`（`process` 返回落点）
- Modify: `src/main/java/cn/guoxy/xunwen/pipeline/IngestTask.java`（`ingest` 返回 `List<MoveRecord>`）
- Modify: `src/main/java/cn/guoxy/xunwen/index/RepoIndex.java`（`byStatus`）
- Create: `src/main/java/cn/guoxy/xunwen/pipeline/MoveRecord.java`
- Create: `src/main/java/cn/guoxy/xunwen/classify/ColdStartSampler.java`
- Test: `src/test/java/cn/guoxy/xunwen/pipeline/FileProcessorTest.java`
- Test: `src/test/java/cn/guoxy/xunwen/classify/ColdStartSamplerTest.java`

**Interfaces:**
- Produces:
  - `record MoveRecord(String batchId, Path from, Path to) {}`
  - `Path FileProcessor.process(...)`：返回实际落点（含 RENAMED 后的路径）；`DUPLICATE_SKIPPED`/已索引/IOException 跳过 → 返回 `null`。`ReshuffleTask` 相应把落点收集为 `List<MoveRecord>`（`run` 返回值不变，新增 getter `List<MoveRecord> moves()` 或直接忽略——**忽略**，回滚靠 OperationLog，Reshuffle 不改）
  - `List<MoveRecord> IngestTask.ingest(List<Path>)`（替代原 `List<Path>` 返回）
  - `List<StoredDoc> RepoIndex.byStatus(String status, int limit)`（按 F_STATUS TermQuery 检索，返回存储字段）
  - `class ColdStartSampler { static List<TreeProposer.Sample> sample(Path repoRoot, ContentExtractor extractor, int limit) }`：复用重排的文件过滤规则（跳过 `.xunwen/`、`待分类/`、`.`/`~$`），取前 `limit`（默认 20）个文件提取预览

- [ ] **Step 1: 写失败测试（FileProcessor 落点 + RepoIndex.byStatus + ColdStartSampler）**

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

class FileProcessorTest {

  private static final GlobalConfig CFG =
      new GlobalConfig("http://localhost:9/v1", "", "m", "e", 4, 0.85, 0.5, 2);

  @Test
  void processReturnsMoveDestination(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path f = root.resolve("报告.txt");
    Files.writeString(f, "内容");
    LlmClient llm = msgs -> """
        {"category":"工作文档","candidates":[
          {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
        """;
    ContentExtractor ex = file -> new ContentExtractor.Extracted("足够长的测试文本内容用于提取流程", true);

    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      Path dest = new FileProcessor(root, CFG, llm, ex)
          .process(f, CategoryTree.load(RepoPaths.categoriesYaml(root)), "b1", index);
      assertNotNull(dest);
      assertEquals(root.resolve("工作文档/报告.txt"), dest);
    }
  }

  @Test
  void duplicateSkipReturnsNull(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Files.createDirectories(root.resolve("工作文档"));
    Files.writeString(root.resolve("工作文档/报告.txt"), "相同");
    Path src = root.resolve("副本.txt");
    Files.writeString(src, "相同");
    LlmClient llm = msgs -> """
        {"category":"工作文档","candidates":[{"name":"工作文档","score":0.95}],"self":0.95}
        """;
    ContentExtractor ex = file -> new ContentExtractor.Extracted("相同", true);

    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      assertNull(new FileProcessor(root, CFG, llm, ex)
          .process(src, CategoryTree.load(RepoPaths.categoriesYaml(root)), "b1", index));
    }
  }
}
```

```java
package cn.guoxy.xunwen.classify;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColdStartSamplerTest {

  @Test
  void samplesFilesSkippingXunwenAndHidden(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("a.txt"), "内容A足够长通过文本层判断的字符串");
    Files.createDirectories(root.resolve("子目录"));
    Files.writeString(root.resolve("子目录/b.txt"), "内容B足够长通过文本层判断的字符串");
    Files.writeString(root.resolve(".hidden"), "隐藏");
    Files.createDirectories(root.resolve(".xunwen"));
    Files.writeString(root.resolve(".xunwen/secret.txt"), "内部文件");

    var samples = ColdStartSampler.sample(root, file -> new cn.guoxy.xunwen.extract.ContentExtractor.Extracted(
        java.nio.file.Files.readString(file), true), 20);
    assertEquals(2, samples.size());
    assertEquals("a.txt", samples.get(0).fileName());
    assertEquals("子目录/b.txt", samples.get(1).fileName());
  }

  @Test
  void respectsLimit(@TempDir Path root) throws Exception {
    for (int i = 0; i < 30; i++) {
      Files.writeString(root.resolve("f" + i + ".txt"), "内容" + i + "足够长通过文本层判断");
    }
    assertEquals(5, ColdStartSampler.sample(root, file -> new cn.guoxy.xunwen.extract.ContentExtractor.Extracted(
        "x", true), 5).size());
  }
}
```

`RepoIndex.byStatus` 的断言并入 FileProcessorTest 之外的 RepoIndexTest（追加一个用例）：

```java
  @Test
  void byStatusFiltersDocuments(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"), 4)) {
      idx.upsert(new Doc("p.pdf", "p.pdf", "pdf", "h", 1L, 2L, "PENDING", "", "c", new float[] {1, 0, 0, 0}));
      idx.upsert(new Doc("q.pdf", "q.pdf", "pdf", "h", 1L, 2L, "INDEXED", "工作文档", "c", new float[] {0, 1, 0, 0}));
      assertEquals(1, idx.byStatus("PENDING", 10).size());
      assertEquals("p.pdf", idx.byStatus("PENDING", 10).get(0).path());
    }
  }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest="FileProcessorTest,ColdStartSamplerTest,RepoIndexTest"`
Expected: 编译失败

- [ ] **Step 3: 最小实现**

`MoveRecord.java`：

```java
package cn.guoxy.xunwen.pipeline;

import java.nio.file.Path;

public record MoveRecord(String batchId, Path from, Path to) {}
```

`FileProcessor.process` 尾部三处 `return;` 改 `return null;`，末尾 `}` 前加 `return actual;`；签名改 `public Path process(...)`。

`IngestTask.ingest`：

```java
  public List<MoveRecord> ingest(List<Path> newFiles) throws IOException {
    CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(repoRoot));
    String batchId = "ingest-" + System.currentTimeMillis();
    List<MoveRecord> done = new ArrayList<>();
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot), cfg.vectorDim())) {
      for (Path f : newFiles) {
        Path dest = processor.process(f, tree, batchId, index);
        if (dest != null) {
          done.add(new MoveRecord(batchId, f, dest));
        }
      }
    }
    return done;
  }
```

`RepoIndex.byStatus`：

```java
  public List<StoredDoc> byStatus(String status, int limit) throws IOException {
    TopDocs docs = search(new TermQuery(new Term(F_STATUS, status)), limit);
    return storedOf(docs.scoreDocs);
  }
```

`ColdStartSampler.java`：

```java
package cn.guoxy.xunwen.classify;

import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 冷启动采样：抽文件内容给 TreeProposer 归纳类别树（FR-6）。 */
public final class ColdStartSampler {
  private ColdStartSampler() {}

  public static List<TreeProposer.Sample> sample(Path repoRoot, ContentExtractor extractor, int limit)
      throws IOException {
    Path root = repoRoot.toAbsolutePath().normalize();
    Path xunwen = RepoPaths.xunwenDir(root);
    Path pending = RepoPaths.pendingDir(root);
    List<TreeProposer.Sample> out = new ArrayList<>();
    try (var walk = Files.walk(root)) {
      for (Path p : walk.filter(Files::isRegularFile)
          .filter(p -> !p.startsWith(xunwen) && !p.startsWith(pending))
          .filter(p -> {
            String n = p.getFileName().toString();
            return !n.startsWith(".") && !n.startsWith("~$");
          })
          .limit(limit)
          .toList()) {
        ContentExtractor.Extracted ex = extractor.extract(p);
        if (ex.hasTextLayer()) {
          out.add(new TreeProposer.Sample(p.getFileName().toString(), ex.text()));
        }
      }
    }
    return out;
  }
}
```

同步修正既有测试编译：`ReshuffleTaskTest`/`IngestTaskTest`/`EndToEndSmokeTest` 若直接调 `process`/`ingest` 的返回值需按新签名适配（`ingest` 返回值被忽略的调用点无需改动）。

- [ ] **Step 4: 全量测试通过**

Run: `mvn -q test`
Expected: PASS（全部既有 + 新增）

- [ ] **Step 5: 格式化 + 验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: move records, status query and cold-start sampler for UI layer"
```

---

### Task B2: ApprovalService（审批归位 / 提议入树 / 删类别重分类）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/pipeline/ApprovalService.java`
- Test: `src/test/java/cn/guoxy/xunwen/pipeline/ApprovalServiceTest.java`

**Interfaces:**
- Consumes: `FileMover`/`OperationLog`(T10) · `RepoIndex`(T6) · `CategoryTree`(T2) · `Classifier`(T8) · `ContentExtractor`(T4)
- Produces:
  - `ApprovalService(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor)`
  - `String approve(Path pendingFile, String categoryPath) throws IOException`：待审文件人工归位——`FileMover.move(pendingFile, repoRoot/categoryPath)` → `log.append(MOVE, batchId="approve-"+ts)` → 索引该文件 `status=INDEXED`、`categoryPath` 更新（先 deleteByPath 旧相对路径再 upsert 新路径，content/向量沿用索引中原记录，查不到则空串/零向量）。返回 batchId
  - `CategoryTree approveProposal(CategoryTree tree, String name, String description, boolean asTopLevel, String parentName) throws IOException`：新类别入树（顶层或挂在 parentName 下）→ `tree.save(categoriesYaml)` → 对索引中全部 `UNKNOWN` 文件重分类归位（复用 FileProcessor：这些文件在 `待分类/`，先 deleteByPath 索引记录再 process——process 会重新提取分类，命中新类别则自动移出待分类）。返回更新后的树
  - `int removeCategory(CategoryTree tree, String categoryPath) throws IOException`：树中删除该节点（含子树）→ save → 索引中 `categoryPath` 精确匹配（或以其为前缀的 2 级路径）的文件：`deleteByPath` + 物理移回 `待分类/`（MoveRecord 计入 batchId=`"recat-"+ts`）→ 逐个重分类。返回处理文件数
  - 审批与重分类都是普通批次，遵循 FR-4 可回滚

- [ ] **Step 1: 写失败测试**

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

class ApprovalServiceTest {

  private static final GlobalConfig CFG =
      new GlobalConfig("http://localhost:9/v1", "", "m", "e", 4, 0.85, 0.5, 2);

  private static final ContentExtractor EX =
      file -> new ContentExtractor.Extracted("足够长的测试文本内容用于提取分类流程", true);

  private static final LlmClient HIGH_LLm = msgs -> """
      {"category":"工作文档","candidates":[
        {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
      """;

  @Test
  void approveMovesPendingFileIntoCategory(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path pending = Files.createDirectories(RepoPaths.pendingDir(root)).resolve("文档.txt");
    Files.writeString(pending, "内容");
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      idx.upsert(new cn.guoxy.xunwen.index.Doc(
          "待分类/文档.txt", "文档.txt", "txt", "h", 1L, 2L, "PENDING", "", "内容", new float[4]));

      new ApprovalService(root, CFG, HIGH_LLm, EX).approve(pending, "工作文档");

      assertTrue(Files.exists(root.resolve("工作文档/文档.txt")));
      var doc = idx.findByPath("工作文档/文档.txt").orElseThrow();
      assertEquals("INDEXED", doc.status());
      assertEquals("工作文档", doc.categoryPath());
      assertTrue(idx.findByPath("待分类/文档.txt").isEmpty());
    }
  }

  @Test
  void approveProposalAddsCategoryAndReclassifiesUnknowns(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("其他", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path unknown = Files.createDirectories(RepoPaths.pendingDir(root)).resolve("发票.txt");
    Files.writeString(unknown, "内容");
    // 先以 UNKNOWN 入索引（模拟已重排的低置信文件）
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      idx.upsert(new cn.guoxy.xunwen.index.Doc(
          "待分类/发票.txt", "发票.txt", "txt", "h", 1L, 2L, "UNKNOWN", "", "内容", new float[4]));
    }
    // LLM 第二次分类时命中新类别「发票凭证」
    LlmClient llm = msgs -> """
        {"category":"发票凭证","candidates":[
          {"name":"发票凭证","score":0.9},{"name":"其他","score":0.1}],"self":0.9}
        """;

    CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(root));
    new ApprovalService(root, CFG, llm, EX).approveProposal(tree, "发票凭证", "各类票据", true, null);

    var updated = CategoryTree.load(RepoPaths.categoriesYaml(root));
    assertTrue(updated.find("发票凭证").isPresent());
    assertTrue(Files.exists(root.resolve("发票凭证/发票.txt"))); // 重分类后移出待分类
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      assertEquals("INDEXED", idx.findByPath("发票凭证/发票.txt").orElseThrow().status());
    }
  }

  @Test
  void removeCategoryMovesFilesBackToPending(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
    Path f = root.resolve("工作文档/a.txt");
    Files.writeString(f, "内容");
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      idx.upsert(new cn.guoxy.xunwen.index.Doc(
          "工作文档/a.txt", "a.txt", "txt", "h", 1L, 2L, "INDEXED", "工作文档", "内容", new float[4]));
    }
    LlmClient unknown = msgs -> "{\"category\":\"\",\"candidates\":[],\"self\":0.1}";

    int n = new ApprovalService(root, CFG, unknown, EX)
        .removeCategory(CategoryTree.load(RepoPaths.categoriesYaml(root)), "工作文档");

    assertEquals(1, n);
    assertFalse(Files.exists(root.resolve("工作文档/a.txt")));
    assertTrue(Files.exists(RepoPaths.pendingDir(root).resolve("a.txt")));
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root), 4)) {
      assertEquals("UNKNOWN", idx.findByPath("待分类/a.txt").orElseThrow().status());
    }
    assertTrue(CategoryTree.load(RepoPaths.categoriesYaml(root)).find("工作文档").isEmpty());
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=ApprovalServiceTest`
Expected: 编译失败

- [ ] **Step 3: 实现 ApprovalService**

```java
package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.classify.Classifier;
import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.Doc;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.index.StoredDoc;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.move.FileMover;
import cn.guoxy.xunwen.move.OperationLog;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 人工审批与类别树变更后的归位（FR-2/FR-6）。 */
public final class ApprovalService {
  private final Path repoRoot;
  private final GlobalConfig cfg;
  private final LlmClient llm;
  private final ContentExtractor extractor;

  public ApprovalService(Path repoRoot, GlobalConfig cfg, LlmClient llm, ContentExtractor extractor) {
    this.repoRoot = repoRoot.toAbsolutePath().normalize();
    this.cfg = cfg;
    this.llm = llm;
    this.extractor = extractor;
  }

  public String approve(Path pendingFile, String categoryPath) throws IOException {
    String batchId = "approve-" + System.currentTimeMillis();
    FileMover mover = new FileMover(repoRoot);
    OperationLog log = new OperationLog(RepoPaths.operationsJsonl(repoRoot));
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot), cfg.vectorDim())) {
      String oldRel = FileProcessor.posix(repoRoot.relativize(pendingFile.toAbsolutePath().normalize()));
      StoredDoc old = index.findByPath(oldRel).orElse(null);
      FileMover.MoveOutcome moved = mover.move(pendingFile, repoRoot.resolve(categoryPath));
      log.append(new OperationLog.Op(
          batchId, System.currentTimeMillis(), "MOVE", pendingFile.toString(), moved.dest().toString()));
      index.deleteByPath(oldRel);
      index.upsert(new Doc(
          FileProcessor.posix(repoRoot.relativize(moved.dest())),
          moved.dest().getFileName().toString(),
          FileProcessor.ext(moved.dest().getFileName().toString()),
          moved.hash(),
          Files.getLastModifiedTime(moved.dest()).toMillis(),
          Files.size(moved.dest()),
          "INDEXED",
          categoryPath,
          old == null ? "" : old.content(),
          new float[cfg.vectorDim()]));
    }
    return batchId;
  }

  public CategoryTree approveProposal(
      CategoryTree tree, String name, String description, boolean asTopLevel, String parentName)
      throws IOException {
    Category node = new Category(name, description, List.of());
    List<Category> roots = new ArrayList<>(tree.roots());
    if (asTopLevel || parentName == null) {
      roots.add(node);
    } else {
      List<Category> rebuilt = new ArrayList<>();
      for (Category r : roots) {
        rebuilt.add(
            r.name().equals(parentName)
                ? new Category(r.name(), r.description(), append(r.children(), node))
                : r);
      }
      roots = rebuilt;
    }
    CategoryTree updated = new CategoryTree(roots);
    updated.save(RepoPaths.categoriesYaml(repoRoot));
    reclassifyAllUnknowns(updated);
    return updated;
  }

  public int removeCategory(CategoryTree tree, String categoryPath) throws IOException {
    List<Category> roots = new ArrayList<>();
    String top = categoryPath.split("/")[0];
    for (Category r : tree.roots()) {
      if (!r.name().equals(top)) {
        roots.add(r);
      }
    }
    CategoryTree updated = new CategoryTree(roots);
    updated.save(RepoPaths.categoriesYaml(repoRoot));

    String batchId = "recat-" + System.currentTimeMillis();
    FileMover mover = new FileMover(repoRoot);
    OperationLog log = new OperationLog(RepoPaths.operationsJsonl(repoRoot));
    int count = 0;
    List<StoredDoc> affected = new ArrayList<>();
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot), cfg.vectorDim())) {
      for (StoredDoc d : index.all()) {
        if (d.categoryPath().equals(categoryPath) || d.categoryPath().startsWith(categoryPath + "/")) {
          affected.add(d);
        }
      }
      for (StoredDoc d : affected) {
        Path file = repoRoot.resolve(d.path());
        if (Files.exists(file)) {
          var moved = mover.move(file, RepoPaths.pendingDir(repoRoot));
          log.append(new OperationLog.Op(
              batchId, System.currentTimeMillis(), "MOVE", file.toString(), moved.dest().toString()));
        }
        index.deleteByPath(d.path());
        Path pendingFile = RepoPaths.pendingDir(repoRoot).resolve(Path.of(d.path()).getFileName());
        if (Files.exists(pendingFile)) {
          index.deleteByPath(FileProcessor.posix(repoRoot.relativize(pendingFile)));
          new FileProcessor(repoRoot, cfg, llm, extractor)
              .process(pendingFile, updated, batchId, index);
          count++;
        }
      }
    }
    return count;
  }

  private void reclassifyAllUnknowns(CategoryTree tree) throws IOException {
    String batchId = "reclassify-" + System.currentTimeMillis();
    FileProcessor processor = new FileProcessor(repoRoot, cfg, llm, extractor);
    try (RepoIndex index = new RepoIndex(RepoPaths.indexDir(repoRoot), cfg.vectorDim())) {
      for (StoredDoc d : index.byStatus("UNKNOWN", 10_000)) {
        index.deleteByPath(d.path()); // 让 process 重新走完整流程
        Path file = repoRoot.resolve(d.path());
        if (Files.exists(file)) {
          processor.process(file, tree, batchId, index);
        }
      }
    }
  }

  private static List<Category> append(List<Category> children, Category node) {
    List<Category> out = new ArrayList<>(children);
    out.add(node);
    return out;
  }
}
```

注意：实现里用到了 `index.all()`（全量 StoredDoc）——Task 6 只有 `allFiles()`（FileInfo）。**在 RepoIndex 补一个 `List<StoredDoc> all() throws IOException`**（MatchAllDocsQuery + storedOf，复用 `search` 私有方法），并在 RepoIndexTest 追加断言 `assertEquals(2, idx.all().size())`。或者复用 `storedOf(search(MatchAllDocsQuery, maxDoc).scoreDocs)`——实现放 Task 6 已建的 `search` 通道上，最小代码。

`FileProcessor.ext`/`posix` 是 Task 11 里的包级静态方法，直接复用。

- [ ] **Step 4: 全量测试通过**

Run: `mvn -q test`
Expected: PASS

- [ ] **Step 5: 格式化 + 验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: approval service for manual routing, proposal adoption and category removal"
```

---

### Task B3: AppShell——XunwenApp 改造、AppState、主视图导航

**Files:**
- Delete: `src/main/java/cn/guoxy/xunwen/HelloApplication.java`
- Delete: `src/main/java/cn/guoxy/xunwen/HelloController.java`
- Delete: `src/main/resources/cn/guoxy/xunwen/hello-view.fxml`
- Create: `src/main/java/cn/guoxy/xunwen/ui/XunwenApp.java`
- Create: `src/main/java/cn/guoxy/xunwen/ui/AppState.java`
- Create: `src/main/java/cn/guoxy/xunwen/ui/Fx.java`
- Create: `src/main/java/cn/guoxy/xunwen/ui/MainController.java`
- Create: `src/main/resources/cn/guoxy/xunwen/ui/main-view.fxml`
- Modify: `src/main/java/cn/guoxy/xunwen/Launcher.java`（指向 XunwenApp）
- Test: `src/test/java/cn/guoxy/xunwen/ui/AppStateTest.java`
- Test: `src/test/java/cn/guoxy/xunwen/ui/HighlighterTest.java`（Highlighter 在 Task B8 用，测试先行放这里）

**Interfaces:**
- Consumes: `GlobalConfigStore`/`RepoRegistry`(T3)
- Produces:
  - `XunwenApp extends Application`：`start` 加载 `main-view.fxml`，窗口 1280×800，标题「寻文」
  - `AppState`：`ObjectProperty<GlobalConfig> config`（启动时 `GlobalConfigStore.load()`）、`ListProperty<Path> repos`、`ObjectProperty<Path> currentRepo`；`saveConfig(GlobalConfig)`；`LlmClient llm()`（从 config 组装 `OpenAiCompatClient`）；`ContentExtractor extractor()`（`new TikaExtractor()`）
  - `Fx`：`static <T> void runAsync(java.util.function.Supplier<T> job, java.util.function.Consumer<T> onDone, java.util.function.Consumer<Throwable> onError)`——`Executors.newVirtualThreadPerTaskExecutor()` 执行，`Platform.runLater` 回调；`static void alert(String msg)`/`error(String msg, Throwable t)` 弹窗
  - `MainController`：左侧 5 个按钮（仓库 / 类别树 / 待审 / 搜索 / 设置），右侧 StackPane 按需加载对应 FXML 子视图（每视图懒加载一次并缓存）

- [ ] **Step 1: 写测试（AppState 与 Highlighter——两者皆无 UI 控件依赖）**

```java
package cn.guoxy.xunwen.ui;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppStateTest {

  @Test
  void loadFallsBackToDefaultsAndSavePersists(@TempDir Path dir) throws IOException {
    var state = new AppState(dir.resolve("config.json"), dir.resolve("repos.json"));
    assertEquals(GlobalConfig.defaults().baseUrl(), state.config().get().baseUrl());
    var custom =
        new GlobalConfig("http://x/v1", "", "m", "e", 4, 0.9, 0.4, 2);
    state.saveConfig(custom);
    assertEquals(custom.baseUrl(),
        new AppState(dir.resolve("config.json"), dir.resolve("repos.json")).config().get().baseUrl());
  }
}
```

（AppState 构造接受 store 路径以便测试注入；生产路径 `Path.of(System.getProperty("user.home"), ".xunwen", ...)` 由 XunwenApp 组装。）

```java
package cn.guoxy.xunwen.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class HighlighterTest {

  @Test
  void splitsEmFragments() {
    var segs = Highlighter.parse("本季度<em>销售</em>增长");
    assertEquals(List.of(
        new Highlighter.Segment("本季度", false),
        new Highlighter.Segment("销售", true),
        new Highlighter.Segment("增长", false)), segs);
  }

  @Test
  void noEmReturnsSinglePlainSegment() {
    assertEquals(List.of(new Highlighter.Segment("纯文本", false)), Highlighter.parse("纯文本"));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest="AppStateTest,HighlighterTest"`
Expected: 编译失败

- [ ] **Step 3: 实现**

`AppState.java`：

```java
package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.config.GlobalConfigStore;
import cn.guoxy.xunwen.config.RepoRegistry;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.extract.TikaExtractor;
import cn.guoxy.xunwen.llm.LlmClient;
import cn.guoxy.xunwen.llm.OpenAiCompatClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javafx.beans.property.ListProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;

public final class AppState {
  private final GlobalConfigStore configStore;
  private final RepoRegistry registry;
  private final ObjectProperty<GlobalConfig> config =
      new SimpleObjectProperty<>(GlobalConfig.defaults());
  private final ListProperty<Path> repos =
      new SimpleListProperty<>(FXCollections.observableArrayList());
  private final ObjectProperty<Path> currentRepo = new SimpleObjectProperty<>();

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

  public LlmClient llm() {
    GlobalConfig c = config.get();
    return new OpenAiCompatClient(c.baseUrl(), c.apiKey(), c.chatModel(), c.embedModel(), c.vectorDim());
  }

  public ContentExtractor extractor() {
    return new TikaExtractor();
  }

  public ObjectProperty<GlobalConfig> configProperty() { return config; }
  public GlobalConfig config() { return config.get(); }
  public ListProperty<Path> reposProperty() { return repos; }
  public ObjectProperty<Path> currentRepoProperty() { return currentRepo; }
  public Path currentRepo() { return currentRepo.get(); }
}
```

`Fx.java`：

```java
package cn.guoxy.xunwen.ui;

import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.Alert;

public final class Fx {
  private static final java.util.concurrent.ExecutorService POOL =
      Executors.newVirtualThreadPerTaskExecutor();

  private Fx() {}

  public static <T> void runAsync(Supplier<T> job, Consumer<T> onDone, Consumer<Throwable> onError) {
    POOL.execute(() -> {
      try {
        T result = job.get();
        Platform.runLater(() -> onDone.accept(result));
      } catch (Throwable t) {
        Platform.runLater(() -> onError.accept(t));
      }
    });
  }

  public static void alert(String msg) {
    new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
  }

  public static void error(String msg, Throwable t) {
    Alert a = new Alert(Alert.AlertType.ERROR, msg + "\n" + String.valueOf(t));
    a.showAndWait();
  }
}
```

`Highlighter.java`（纯逻辑，供搜索页用）：

```java
package cn.guoxy.xunwen.ui;

import java.util.ArrayList;
import java.util.List;

/** 把 Lucene 高亮串 `<em>x</em>` 拆成 (text, emphasized) 段，供 TextFlow 渲染。 */
public final class Highlighter {
  public record Segment(String text, boolean emphasized) {}

  private Highlighter() {}

  public static List<Segment> parse(String highlighted) {
    List<Segment> out = new ArrayList<>();
    int i = 0;
    while (i < highlighted.length()) {
      int open = highlighted.indexOf("<em>", i);
      if (open < 0) {
        out.add(new Segment(highlighted.substring(i), false));
        break;
      }
      if (open > i) {
        out.add(new Segment(highlighted.substring(i, open), false));
      }
      int close = highlighted.indexOf("</em>", open);
      if (close < 0) {
        out.add(new Segment(highlighted.substring(open), false));
        break;
      }
      out.add(new Segment(highlighted.substring(open + 4, close), true));
      i = close + 5;
    }
    return out;
  }
}
```

`XunwenApp.java`（替代 HelloApplication）：

```java
package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.config.GlobalConfig;
import java.io.IOException;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class XunwenApp extends Application {
  private AppState state;

  @Override
  public void init() throws IOException {
    Path dir = Path.of(System.getProperty("user.home"), ".xunwen");
    state = new AppState(dir.resolve("config.json"), dir.resolve("repos.json"));
    state.load();
  }

  @Override
  public void start(Stage stage) throws IOException {
    FXMLLoader loader = new FXMLLoader(getClass().getResource("main-view.fxml"));
    MainController controller = new MainController(state);
    loader.setController(controller);
    Parent root = loader.load();
    stage.setTitle("寻文");
    stage.setScene(new Scene(root, 1280, 800));
    stage.show();
  }
}
```

`Launcher.java`：`Application.launch(HelloApplication.class, args)` → `Application.launch(XunwenApp.class, args)`。

`main-view.fxml`（骨架，控制器由代码注入所以 FXML 不带 fx:controller；左侧竖排侧栏，按已确认原型）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.layout.BorderPane?>
<?import javafx.scene.layout.VBox?>
<?import javafx.scene.layout.StackPane?>
<?import javafx.geometry.Insets?>
<BorderPane xmlns="http://javafx.com/javafx/17" xmlns:fx="http://javafx.com/fxml/1">
  <left>
    <VBox spacing="8">
      <padding><Insets top="12" right="12" bottom="12" left="12"/></padding>
      <Button fx:id="reposBtn" text="仓库" maxWidth="Infinity"/>
      <Button fx:id="treeBtn" text="类别树" maxWidth="Infinity"/>
      <Button fx:id="pendingBtn" text="待审" maxWidth="Infinity"/>
      <Button fx:id="searchBtn" text="搜索" maxWidth="Infinity"/>
      <Button fx:id="settingsBtn" text="设置" maxWidth="Infinity"/>
    </VBox>
  </left>
  <center>
    <StackPane fx:id="content" prefWidth="900"/>
  </center>
</BorderPane>
```

`MainController.java`：

```java
package cn.guoxy.xunwen.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;

public class MainController {
  private enum Page { REPOS, TREE, PENDING, SEARCH, SETTINGS }

  private final AppState state;
  private final Map<Page, Node> cache = new EnumMap<>(Page.class);

  @FXML private Button reposBtn;
  @FXML private Button treeBtn;
  @FXML private Button pendingBtn;
  @FXML private Button searchBtn;
  @FXML private Button settingsBtn;
  @FXML private StackPane content;

  public MainController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    reposBtn.setOnAction(e -> show(Page.REPOS, "repos-view.fxml"));
    treeBtn.setOnAction(e -> show(Page.TREE, "tree-view.fxml"));
    pendingBtn.setOnAction(e -> show(Page.PENDING, "pending-view.fxml"));
    searchBtn.setOnAction(e -> show(Page.SEARCH, "search-view.fxml"));
    settingsBtn.setOnAction(e -> show(Page.SETTINGS, "settings-view.fxml"));
    show(Page.REPOS, "repos-view.fxml");
  }

  private void show(Page page, String fxml) {
    content.getChildren().setAll(cache.computeIfAbsent(page, p -> load(fxml)));
  }

  private Node load(String fxml) {
    try {
      FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
      loader.setControllerFactory(type -> newController(fxml));
      return loader.load();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Object newController(String fxml) {
    return switch (fxml) {
      case "repos-view.fxml" -> new ReposController(state);
      case "tree-view.fxml" -> new TreeController(state);
      case "pending-view.fxml" -> new PendingController(state);
      case "search-view.fxml" -> new SearchController(state);
      case "settings-view.fxml" -> new SettingsController(state);
      default -> throw new IllegalArgumentException(fxml);
    };
  }
}
```

（`ReposController` 等五个类在后续任务实现；为使本任务可编译可运行，先创建五个**最小占位 Controller**——空类带 `AppState` 构造与 `@FXML initialize(){}`，对应 fxml 为只含一个 `Label` 的骨架。占位文件在后续任务被替换。）

- [ ] **Step 4: 跑测试 + 手工验证**

Run: `mvn -q test -Dtest="AppStateTest,HighlighterTest"`
Expected: PASS

手工验证：`mvn javafx:run` → 窗口「寻文」打开，左侧 5 个按钮可点击切换（占位 Label），无异常栈。

- [ ] **Step 5: 格式化 + 验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: app shell with navigation, app state and async helpers"
```

---

### Task B4: 设置页

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/ui/SettingsController.java`（替换占位）
- Create: `src/main/resources/cn/guoxy/xunwen/ui/settings-view.fxml`
- Test: 无新 JUnit（表单读写经 AppStateTest 已覆盖；手工验证清单）

**Interfaces:**
- Consumes: `AppState`（B3）· `OpenAiCompatClient.embed("ping")`（T5，测试连接）
- Produces: 表单字段 base-url / api-key / chat 模型 / embedding 模型 / 向量维度 / 自动阈值 / 待审阈值 / 并发 → 「保存」调 `state.saveConfig`；「测试连接」后台调 `embed("ping")` 成功弹「连接正常」，失败弹错误

- [ ] **Step 1: FXML 表单**

`settings-view.fxml`：`GridPane` 两列 8 行（8 个 `TextField`，阈值/维度/并发仍用文本框 + `Double/Integer.parse` 校验，不做 Slider——先能用）+ 底部 `Button`「保存」「测试连接」。控制器注入同 B3 模式。

- [ ] **Step 2: SettingsController**

```java
package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.config.GlobalConfig;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;

public class SettingsController {
  private final AppState state;

  @FXML private TextField baseUrl;
  @FXML private TextField apiKey;
  @FXML private TextField chatModel;
  @FXML private TextField embedModel;
  @FXML private TextField vectorDim;
  @FXML private TextField autoThreshold;
  @FXML private TextField pendingThreshold;
  @FXML private TextField concurrency;

  public SettingsController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    GlobalConfig c = state.config();
    baseUrl.setText(c.baseUrl());
    apiKey.setText(c.apiKey());
    chatModel.setText(c.chatModel());
    embedModel.setText(c.embedModel());
    vectorDim.setText(String.valueOf(c.vectorDim()));
    autoThreshold.setText(String.valueOf(c.autoThreshold()));
    pendingThreshold.setText(String.valueOf(c.pendingThreshold()));
    concurrency.setText(String.valueOf(c.concurrency()));
  }

  @FXML
  private void onSave() {
    try {
      state.saveConfig(new GlobalConfig(
          baseUrl.getText().strip(),
          apiKey.getText().strip(),
          chatModel.getText().strip(),
          embedModel.getText().strip(),
          Integer.parseInt(vectorDim.getText().strip()),
          Double.parseDouble(autoThreshold.getText().strip()),
          Double.parseDouble(pendingThreshold.getText().strip()),
          Integer.parseInt(concurrency.getText().strip())));
      Fx.alert("设置已保存");
    } catch (Exception e) {
      Fx.error("保存失败（检查数字格式）", e);
    }
  }

  @FXML
  private void onTestConnection() {
    Fx.runAsync(
        () -> state.llm().embed("连接测试"),
        v -> Fx.alert("连接正常，向量维度=" + v.length),
        t -> Fx.error("连接失败", t));
  }
}
```

- [ ] **Step 3: 手工验证**

`mvn javafx:run` → 设置页显示当前配置；改 base-url 为错误地址 → 测试连接弹错；恢复 → 连接正常；保存后重启应用配置仍在。

- [ ] **Step 4: 格式化 + 验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: settings view with provider config and connection test"
```

---

### Task B5: 仓库管理页

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/ui/ReposController.java`（替换占位）
- Create: `src/main/resources/cn/guoxy/xunwen/ui/repos-view.fxml`

**Interfaces:**
- Consumes: `AppState.reposProperty/currentRepo/addRepo/removeRepo` · `IngestTask.sync()`（T12）· `Fx.runAsync`
- Produces:
  - 仓库 `ListView<Path>` 绑定 `repos`；「添加仓库」`DirectoryChooser` → `addRepo`（空仓库首次添加时提示先去类别树页冷启动）；「移除仓库」（确认对话框）；「打开」→ `currentRepo.set` + 后台 `sync()`（FR-12 积压修正），显示「同步完成，处理 N 个文件」
  - 双击仓库行 = 打开

- [ ] **Step 1: FXML + Controller**

`repos-view.fxml`：`VBox`[ `HBox`[添加, 打开, 移除] , `ListView` ]。

```java
package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.pipeline.IngestTask;
import java.nio.file.Path;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;
import javafx.stage.DirectoryChooser;

public class ReposController {
  private final AppState state;

  @FXML private ListView<Path> repoList;

  public ReposController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    repoList.setItems(state.reposProperty());
    repoList.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2) {
        onOpen();
      }
    });
  }

  @FXML
  private void onAdd() {
    var chooser = new DirectoryChooser();
    chooser.setTitle("选择资料仓库文件夹");
    java.io.File dir = chooser.showDialog(repoList.getScene().getWindow());
    if (dir == null) {
      return;
    }
    try {
      state.addRepo(dir.toPath());
    } catch (Exception ex) {
      Fx.error("添加仓库失败", ex);
    }
  }

  @FXML
  private void onOpen() {
    Path selected = repoList.getSelectionModel().getSelectedItem();
    if (selected == null) {
      return;
    }
    state.currentRepoProperty().set(selected);
    Fx.runAsync(
        () -> new IngestTask(selected, state.config(), state.llm(), state.extractor()).sync(),
        n -> Fx.alert("已打开仓库「" + selected.getFileName() + "」，同步处理 " + n + " 个文件"),
        t -> Fx.error("同步失败", t));
  }

  @FXML
  private void onRemove() {
    Path selected = repoList.getSelectionModel().getSelectedItem();
    if (selected == null) {
      return;
    }
    var confirm = new javafx.scene.control.Alert(
        javafx.scene.control.Alert.AlertType.CONFIRMATION,
        "移除仓库「" + selected.getFileName() + "」？仅解除管理，不删除任何文件。");
    confirm.showAndWait().ifPresent(bt -> {
      if (bt == javafx.scene.control.ButtonType.OK) {
        try {
          state.removeRepo(selected);
        } catch (Exception ex) {
          Fx.error("移除失败", ex);
        }
      }
    });
  }
}
```

- [ ] **Step 2: 手工验证**

添加一个含若干 txt 的文件夹 → 出现在列表；双击打开 → 提示同步结果；关闭应用重开 → 列表仍在（repos.json 持久化）；移除后列表消失、文件夹原样。

- [ ] **Step 3: 格式化 + 验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: repo management view with backlog sync on open"
```

---

### Task B6: 类别树页（冷启动 / 重排 / 回滚）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/ui/TreeController.java`（替换占位）
- Create: `src/main/resources/cn/guoxy/xunwen/ui/tree-view.fxml`

**Interfaces:**
- Consumes: `ColdStartSampler`/`TreeProposer`(B1/T9) · `ReshuffleTask`(T11) · `OperationLog`(T10) · `CategoryTree`(T2) · `ApprovalService.removeCategory`(B2)
- Produces:
  - `TreeView<String>` 显示当前仓库类别树（根节点=仓库名，子节点=类别路径）；右键菜单：`加子类别` / `重命名`（`TextInputDialog`）/ `删除类别`（确认后走 `ApprovalService.removeCategory`，引擎重分类，**禁删「其他」**——prompt 里约定的兜底）
  - 「生成类别树提案」：后台 `ColdStartSampler.sample(root, extractor, 20)` → `TreeProposer.propose` → 结果写入提案 `TreeView`（可编辑态：右键加/删/改名）→「批准入树」把提案树 save；「放弃」还原显示已生效树
  - 「重排仓库」：`ReshuffleTask.run` 后台 + `Progress`（`ProgressBar` + 当前文件名 + 「取消」置 `cancelled`）；完成后提示批次号
  - 「回滚批次」：`TextInputDialog` 输入批次号（重排完成弹窗里可复制）→ 后台 `OperationLog.rollback`
  - 无仓库选中/无树时按钮禁用并提示

- [ ] **Step 1: FXML**

`tree-view.fxml`：`BorderPane`[top=`ToolBar`[生成提案, 批准入树, 放弃提案, 重排仓库, 回滚批次, 取消重排], center=`TreeView fx:id=tree`, bottom=`HBox`[ProgressBar fx:id=progress, Label fx:id=status]]。

- [ ] **Step 2: TreeController**

```java
package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.classify.ColdStartSampler;
import cn.guoxy.xunwen.classify.TreeProposer;
import cn.guoxy.xunwen.move.OperationLog;
import cn.guoxy.xunwen.pipeline.ApprovalService;
import cn.guoxy.xunwen.pipeline.ReshuffleTask;
import cn.guoxy.xunwen.repo.Category;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

public class TreeController {
  private final AppState state;
  private CategoryTree proposal; // 非空 = 正在编辑提案

  @FXML private TreeView<String> tree;
  @FXML private ProgressBar progress;
  @FXML private Label status;

  public TreeController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    state.currentRepoProperty().addListener((obs, o, repo) -> reload());
    reload();
    tree.setContextMenu(buildMenu());
  }

  private void reload() {
    proposal = null;
    Path repo = state.currentRepo();
    if (repo == null || !java.nio.file.Files.exists(RepoPaths.categoriesYaml(repo))) {
      tree.setRoot(null);
      return;
    }
    Fx.runAsync(
        () -> CategoryTree.load(RepoPaths.categoriesYaml(repo)),
        t -> tree.setRoot(buildRoot(repo.getFileName().toString(), t)),
        t -> Fx.error("加载类别树失败", t));
  }

  private TreeItem<String> buildRoot(String repoName, CategoryTree t) {
    TreeItem<String> root = new TreeItem<>(repoName);
    root.setExpanded(true);
    for (String path : t.flattenPaths()) {
      TreeItem<String> node = root;
      for (String part : path.split("/")) {
        TreeItem<String> child = find(node, part);
        if (child == null) {
          child = new TreeItem<>(part);
          node.getChildren().add(child);
        }
        node = child;
      }
    }
    return root;
  }

  private static TreeItem<String> find(TreeItem<String> parent, String name) {
    for (TreeItem<String> c : parent.getChildren()) {
      if (c.getValue().equals(name)) {
        return c;
      }
    }
    return null;
  }

  private ContextMenu buildMenu() {
    MenuItem addChild = new MenuItem("加子类别");
    addChild.setOnAction(e -> onAddChild());
    MenuItem rename = new MenuItem("重命名");
    rename.setOnAction(e -> onRename());
    MenuItem remove = new MenuItem("删除类别");
    remove.setOnAction(e -> onRemove());
    return new ContextMenu(addChild, rename, remove);
  }

  @FXML
  private void onGenerateProposal() {
    Path repo = state.currentRepo();
    if (repo == null) {
      Fx.alert("先在「仓库」页添加并打开仓库");
      return;
    }
    status.setText("正在采样并生成类别树提案…");
    Fx.runAsync(
        () -> {
          var samples = ColdStartSampler.sample(repo, state.extractor(), 20);
          return new TreeProposer(state.llm()).propose(samples);
        },
        t -> {
          proposal = t;
          tree.setRoot(buildRoot("提案（未生效）", t));
          status.setText("提案已生成：右键可增删改，确认后「批准入树」");
        },
        t -> {
          status.setText("");
          Fx.error("生成失败（检查 LLM 配置）", t);
        });
  }

  @FXML
  private void onApproveProposal() {
    Path repo = state.currentRepo();
    if (repo == null || proposal == null) {
      Fx.alert("没有待批准的提案");
      return;
    }
    try {
      proposal.save(RepoPaths.categoriesYaml(repo));
      proposal = null;
      reload();
      Fx.alert("类别树已生效，可执行「重排仓库」");
    } catch (Exception e) {
      Fx.error("保存失败", e);
    }
  }

  @FXML
  private void onDiscardProposal() {
    if (proposal != null) {
      reload();
      status.setText("已放弃提案");
    }
  }

  @FXML
  private void onReshuffle() {
    Path repo = state.currentRepo();
    if (repo == null) {
      Fx.alert("先打开仓库");
      return;
    }
    progress.setProgress(javafx.scene.control.ProgressIndicator.INDETERMINATE_PROGRESS);
    java.util.concurrent.atomic.AtomicBoolean cancelledFlag =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    ReshuffleTask.Progress listener = new ReshuffleTask.Progress() {
      @Override
      public void onProgress(int done, int total, String file) {
        javafx.application.Platform.runLater(() -> {
          progress.setProgress(total == 0 ? 1 : (double) done / total);
          status.setText(done + "/" + total + " " + file);
        });
      }

      @Override
      public boolean cancelled() {
        return cancelledFlag.get();
      }
    };
    this.activeCancelFlag = cancelledFlag;
    Fx.runAsync(
        () -> new ReshuffleTask(repo, state.config(), state.llm(), state.extractor()).run(listener),
        batch -> {
          progress.setProgress(1);
          status.setText("重排完成，批次：" + batch + "（可回滚）");
          reload();
        },
        t -> {
          progress.setProgress(0);
          status.setText("");
          Fx.error("重排失败", t);
        });
  }

  private java.util.concurrent.atomic.AtomicBoolean activeCancelFlag;

  @FXML
  private void onCancelReshuffle() {
    if (activeCancelFlag != null) {
      activeCancelFlag.set(true);
      status.setText("正在取消，已完成的文件保留（批次仍可回滚）…");
    }
  }

  private void onAddChild() {
    TreeItem<String> sel = tree.getSelectionModel().getSelectedItem();
    if (sel == null) {
      return;
    }
    var dialog = new TextInputDialog();
    dialog.setHeaderText("新类别名称（2-6 字）");
    dialog.showAndWait().ifPresent(name -> {
      if (name.isBlank()) {
        return;
      }
      sel.getChildren().add(new TreeItem<>(name));
      sel.setExpanded(true);
      markTreeEdited();
    });
  }

  private void onRename() {
    TreeItem<String> sel = tree.getSelectionModel().getSelectedItem();
    if (sel == null || sel.getParent() == null) {
      return;
    }
    var dialog = new TextInputDialog(sel.getValue());
    dialog.setHeaderText("重命名类别");
    dialog.showAndWait().ifPresent(name -> {
      sel.setValue(name);
      markTreeEdited();
    });
  }

  private void onRemove() {
    TreeItem<String> sel = tree.getSelectionModel().getSelectedItem();
    Path repo = state.currentRepo();
    if (sel == null || sel.getParent() == null || repo == null) {
      return;
    }
    if ("其他".equals(sel.getValue())) {
      Fx.alert("「其他」是兜底类别，不可删除");
      return;
    }
    String path = pathOf(sel);
    var confirm = new Alert(Alert.AlertType.CONFIRMATION, "删除类别「" + path + "」？其下文件将移回待分类并重新分类。");
    confirm.showAndWait().ifPresent(bt -> {
      if (bt != ButtonType.OK) {
        return;
      }
      Fx.runAsync(
          () -> new ApprovalService(repo, state.config(), state.llm(), state.extractor())
              .removeCategory(CategoryTree.load(RepoPaths.categoriesYaml(repo)), path),
          n -> {
            reload();
            Fx.alert("已删除，重分类 " + n + " 个文件");
          },
          t -> Fx.error("删除失败", t));
    });
  }

  private void markTreeEdited() {
    status.setText("树已编辑（直接编辑模式：改动即时生效需保存）");
  }

  @FXML
  private void onRollback() {
    Path repo = state.currentRepo();
    if (repo == null) {
      return;
    }
    var dialog = new TextInputDialog();
    dialog.setHeaderText("输入要回滚的批次号");
    dialog.showAndWait().ifPresent(batch -> Fx.runAsync(
        () -> {
          new OperationLog(RepoPaths.operationsJsonl(repo)).rollback(batch.strip());
          return null;
        },
        v -> Fx.alert("已回滚批次 " + batch),
        t -> Fx.error("回滚失败", t)));
  }

  private static String pathOf(TreeItem<String> item) {
    List<String> parts = new ArrayList<>();
    for (TreeItem<String> n = item; n.getParent() != null; n = n.getParent()) {
      parts.add(0, n.getValue());
    }
    return String.join("/", parts);
  }
}
```

实现说明（执行者务必照做）：
1. **直接编辑树与提案二选一**——为避免两套保存路径，简化：树上的增/改名只作用于**提案态**（`proposal != null` 时允许，生效态提示「先点生成提案」）；「批准入树」才 save。`onCancelReshuffle` 的取消用 `java.util.concurrent.atomic.AtomicBoolean`：`ReshuffleTask.Progress` 实现改为持有 `AtomicBoolean cancelledFlag`，按钮 `set(true)`。
2. `pathOf` 与提案编辑的组合已足够 v1；`Category` 的 description 编辑不做（YAML 手改兜底，spec 非目标）。

- [ ] **Step 3: 手工验证**

准备一个 10+ 个 txt 的文件夹（内容覆盖 2-3 个明显主题）→ 仓库页打开 → 类别树页「生成提案」→ 树出现提案 → 右键微调 → 「批准入树」→「重排仓库」进度条推进 → 完成后文件已按类别归位 → 「回滚批次」输入批次号 → 文件回到原位。

- [ ] **Step 4: 格式化 + 验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: category tree view with cold start proposal, reshuffle progress and batch rollback"
```

---

### Task B7: 待审队列页（审批归位 + 提议审批）

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/ui/PendingController.java`（替换占位）
- Create: `src/main/resources/cn/guoxy/xunwen/ui/pending-view.fxml`

**Interfaces:**
- Consumes: `RepoIndex.byStatus("PENDING"/"UNKNOWN")`(B1) · `ApprovalService.approve/approveProposal`(B2) · `CategoryProposer.aggregate`(T12) · `Classifier`（提议来自入库时已算好的 proposedCategory——**存储问题**：`StoredDoc` 没有 proposed 字段。方案：待审页打开时实时重算提议——取 UNKNOWN 文件名列表 → `CategoryProposer.aggregate` 需要的是 `List<Outcome>`；构造 `new Outcome(UNKNOWN,"",0,proposed,reason)` 需要 proposed 值，但索引里没有。**改法（本任务内做）**：`StoredDoc` 增加 `proposed`/`reason` 字段成本高；最懒正确方案：待审页对 UNKNOWN 文件**重新 classify**（后台，数量少）拿到 proposal，再 aggregate。无 schema 变更，每次打开重算，本地 LLM 成本可忽略）
- Produces:
  - 待审表 `TableView<Row>`：列 = 文件名 / 状态（PENDING|UNKNOWN）/ 类别选择 `ComboBox<String>`（选项 = 当前树 flattenPaths）/ 按钮「归位」；「按所选全部归位」按钮批量执行
  - 提议区 `ListView<CategoryProposer.Proposal>` + 「批准为新类别」（批量归位相关文件）「忽略」
  - 数据装载：后台——PENDING/UNKNOWN 文件列表（`byStatus`）+ UNKNOWN 文件重新 classify → proposals

- [ ] **Step 1: FXML**

`pending-view.fxml`：`VBox`[HBox[Button「按所选全部归位」fx:id/onAction=onApproveAll], Label「待审文件」, TableView fx:id=table, Separator, HBox[ListView fx:id=proposals, VBox[Button 批准为新类别, Button 忽略提议]]]。

- [ ] **Step 2: PendingController**

```java
package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.classify.CategoryProposer;
import cn.guoxy.xunwen.classify.Classifier;
import cn.guoxy.xunwen.classify.TreeProposer;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.index.StoredDoc;
import cn.guoxy.xunwen.pipeline.ApprovalService;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;

public class PendingController {
  public record Row(StoredDoc doc, ComboBox<String> categoryBox) {}

  private final AppState state;
  private List<String> categoryOptions = List.of();

  @FXML private TableView<Row> table;
  @FXML private TableColumn<Row, String> nameCol;
  @FXML private TableColumn<Row, String> statusCol;
  @FXML private TableColumn<Row, ComboBox<String>> categoryCol;
  @FXML private TableColumn<Row, Void> actionCol;
  @FXML private ListView<CategoryProposer.Proposal> proposals;

  public PendingController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    nameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().doc().name()));
    statusCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().doc().status()));
    categoryCol.setCellValueFactory(
        d -> new javafx.beans.property.SimpleObjectProperty<>(d.getValue().categoryBox()));
    categoryCol.setCellFactory(col -> new TableCell<>() {
      @Override
      protected void updateItem(ComboBox<String> box, boolean empty) {
        super.updateItem(box, empty);
        setGraphic(empty || box == null ? null : box);
      }
    });
    actionCol.setCellFactory(col -> new TableCell<>() {
      private final Button btn = new Button("归位");

      @Override
      protected void updateItem(Row row, boolean empty) {
        super.updateItem(row, empty);
        if (empty || row == null) {
          setGraphic(null);
          return;
        }
        btn.setOnAction(e -> onApprove(row));
        setGraphic(btn);
      }
    });
    proposals.setCellFactory(lv -> new ListCell<>() {
      @Override
      protected void updateItem(CategoryProposer.Proposal p, boolean empty) {
        super.updateItem(p, empty);
        setText(empty || p == null ? null : p.name() + "（" + p.fileCount() + " 个文件）：" + p.reason());
      }
    });
    state.currentRepoProperty().addListener((obs, o, r) -> reload());
    reload();
  }

  private void reload() {
    Path repo = state.currentRepo();
    if (repo == null) {
      table.getItems().clear();
      proposals.getItems().clear();
      return;
    }
    Fx.runAsync(
        () -> {
          List<StoredDoc> pending;
          List<StoredDoc> unknown;
          try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(repo), state.config().vectorDim())) {
            pending = idx.byStatus("PENDING", 10_000);
            unknown = idx.byStatus("UNKNOWN", 10_000);
            CategoryTree tree = CategoryTree.load(RepoPaths.categoriesYaml(repo));
            // UNKNOWN 重新分类以产出提议（数量少，本地成本可忽略）
            Classifier classifier = new Classifier(state.llm(), state.config());
            List<Classifier.Outcome> outcomes = new ArrayList<>();
            for (StoredDoc d : unknown) {
              var ex = state.extractor().extract(repo.resolve(d.path()));
              outcomes.add(classifier.classify(d.name(), ex.text(), ex.hasTextLayer(), tree));
            }
            // 占位 embedding 使提议聚合退化为同名合并（语义聚合依赖真实 embedding，注入即可升级）
            List<CategoryProposer.Proposal> ps =
                new CategoryProposer(text -> new float[state.config().vectorDim()])
                    .aggregate(outcomes, tree);
            return new LoadResult(pending, unknown, tree.flattenPaths(), ps);
          }
        },
        r -> {
          categoryOptions = r.options();
          List<Row> rows = new ArrayList<>();
          for (StoredDoc d : r.pending()) {
            rows.add(newRow(d));
          }
          for (StoredDoc d : r.unknown()) {
            rows.add(newRow(d));
          }
          table.setItems(FXCollections.observableList(rows));
          proposals.getItems().setAll(r.proposals());
        },
        t -> Fx.error("加载待审队列失败", t));
  }

  private Row newRow(StoredDoc d) {
    ComboBox<String> box = new ComboBox<>(FXCollections.observableList(categoryOptions));
    return new Row(d, box);
  }

  private record LoadResult(
      List<StoredDoc> pending, List<StoredDoc> unknown, List<String> options, List<CategoryProposer.Proposal> proposals) {}

  private void onApprove(Row row) {
    Path repo = requireRepo();
    if (repo == null) {
      return;
    }
    String category = row.categoryBox().getValue();
    if (category == null || category.isBlank()) {
      Fx.alert("先为该文件选择类别");
      return;
    }
    Fx.runAsync(
        () -> new ApprovalService(repo, state.config(), state.llm(), state.extractor())
            .approve(repo.resolve(row.doc().path()), category),
        batch -> reload(),
        t -> Fx.error("归位失败", t));
  }

  @FXML
  private void onApproveAll() {
    Path repo = requireRepo();
    if (repo == null) {
      return;
    }
    List<Row> ready = table.getItems().stream()
        .filter(r -> r.categoryBox().getValue() != null && !r.categoryBox().getValue().isBlank())
        .toList();
    if (ready.isEmpty()) {
      Fx.alert("没有已选择类别的待审文件");
      return;
    }
    Fx.runAsync(
        () -> {
          var service = new ApprovalService(repo, state.config(), state.llm(), state.extractor());
          for (Row r : ready) {
            service.approve(repo.resolve(r.doc().path()), r.categoryBox().getValue());
          }
          return ready.size();
        },
        n -> {
          reload();
          Fx.alert("已批量归位 " + n + " 个文件");
        },
        t -> Fx.error("批量归位失败", t));
  }

  @FXML
  private void onApproveProposal() {
    Path repo = requireRepo();
    CategoryProposer.Proposal p = proposals.getSelectionModel().getSelectedItem();
    if (repo == null || p == null) {
      return;
    }
    Fx.runAsync(
        () -> new ApprovalService(repo, state.config(), state.llm(), state.extractor())
            .approveProposal(
                CategoryTree.load(RepoPaths.categoriesYaml(repo)), p.name(), p.reason(), true, null),
        tree -> {
          reload();
          Fx.alert("新类别「" + p.name() + "」已入树，相关文件正在重新归位");
        },
        t -> Fx.error("批准失败", t));
  }

  @FXML
  private void onIgnoreProposal() {
    proposals.getItems().remove(proposals.getSelectionModel().getSelectedItem());
  }

  private Path requireRepo() {
    Path repo = state.currentRepo();
    if (repo == null) {
      Fx.alert("先在「仓库」页打开仓库");
    }
    return repo;
  }
}
```

实现说明：每行 `ComboBox` 在数据装载时用当前树 `flattenPaths` 装配（`newRow`）；「按所选全部归位」串行调 `ApprovalService.approve`（一个文件一个批次，均可独立回滚）。`CategoryProposer` 构造处的占位 embedding（`text -> new float[dim]`）使聚合退化为同名合并——**接受**：完整语义聚合依赖真实 embedding，注入 `state.llm()` 的 `EmbeddingClient` 即可升级；实现注释标注。

- [ ] **Step 3: 手工验证**

重排后打开待审页 → PENDING/UNKNOWN 文件列出；选类别点「归位」→ 文件移动进类别目录、表刷新；提议列表出现 → 「批准为新类别」→ 类别树页可见新类别、相关文件移出待分类。

- [ ] **Step 4: 格式化 + 验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: pending review view with approval routing and proposal adoption"
```

---

### Task B8: 搜索页

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/ui/SearchController.java`（替换占位）
- Create: `src/main/resources/cn/guoxy/xunwen/ui/search-view.fxml`

**Interfaces:**
- Consumes: `HybridSearcher.search`(T7) · `Highlighter.parse`(B3) · `RepoIndex`(T6) · `RepoRegistry`（全局搜索=对 `state.repos()` 全部仓库建 index map）
- Produces:
  - 搜索框 + `ComboBox` 范围（当前仓库 / 全部仓库）+ 结果 `ListView<Hit>`（自定义 Cell：第一行 名称 · 类别 · 仓库名，第二行高亮 TextFlow）
  - 后台搜索：每仓库 open `RepoIndex`（try-with-resources 在任务内）→ `HybridSearcher.search`；查询向量 `state.llm().embed(query)`（embedding 失败 → 零向量，退关键词路）
  - 双击结果 → `java.awt.Desktop.open(仓库根/path)`（后台线程）

- [ ] **Step 1: FXML**

`search-view.fxml`：`BorderPane`[top=HBox[TextField fx:id=query, ComboBox fx:id=scope, Button 搜索], center=ListView fx:id=results]。

- [ ] **Step 2: SearchController**

```java
package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.index.HybridSearcher;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

public class SearchController {
  private final AppState state;
  private final Map<String, Path> repoRootByName = new HashMap<>(); // repoName → 仓库根

  @FXML private TextField query;
  @FXML private ComboBox<String> scope;
  @FXML private ListView<HybridSearcher.Hit> results;

  public SearchController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    scope.getItems().setAll("当前仓库", "全部仓库");
    scope.getSelectionModel().selectFirst();
    results.setCellFactory(lv -> new ListCell<>() {
      @Override
      protected void updateItem(HybridSearcher.Hit hit, boolean empty) {
        super.updateItem(hit, empty);
        if (empty || hit == null) {
          setGraphic(null);
          return;
        }
        setGraphic(buildCell(hit));
      }
    });
    results.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2) {
        open(results.getSelectionModel().getSelectedItem());
      }
    });
  }

  private TextFlow buildCell(HybridSearcher.Hit hit) {
    TextFlow flow = new TextFlow();
    Text title = new Text(hit.name());
    title.setFont(Font.font(null, FontWeight.BOLD, 13));
    flow.getChildren().add(title);
    flow.getChildren().add(new Text("   " + hit.categoryPath() + " · " + hit.repoName() + "\n"));
    for (Highlighter.Segment seg : Highlighter.parse(hit.highlight())) {
      Text t = new Text(seg.text());
      if (seg.emphasized()) {
        t.setFont(Font.font(null, FontWeight.BOLD, 12));
        t.setFill(Color.DARKRED);
      } else {
        t.setFont(Font.font(12));
        t.setFill(Color.DIMGRAY);
      }
      flow.getChildren().add(t);
    }
    return flow;
  }

  private void open(HybridSearcher.Hit hit) {
    if (hit == null) {
      return;
    }
    Path root = repoRootByName.get(hit.repoName());
    if (root == null) {
      return;
    }
    Path file = root.resolve(hit.path());
    Fx.runAsync(() -> {
      java.awt.Desktop.getDesktop().open(file.toFile());
      return null;
    }, v -> {}, t -> Fx.error("无法打开文件", t));
  }

  @FXML
  private void onSearch() {
    String q = query.getText().strip();
    if (q.isEmpty()) {
      return;
    }
    boolean all = "全部仓库".equals(scope.getValue());
    List<Path> repos = all ? List.copyOf(state.reposProperty()) : onlyCurrent();
    if (repos.isEmpty()) {
      Fx.alert("没有可搜索的仓库");
      return;
    }
    Fx.runAsync(
        () -> doSearch(repos, q),
        hits -> results.getItems().setAll(FXCollections.observableList(hits)),
        t -> Fx.error("搜索失败", t));
  }

  private List<Path> onlyCurrent() {
    Path cur = state.currentRepo();
    return cur == null ? List.of() : List.of(cur);
  }

  private List<HybridSearcher.Hit> doSearch(List<Path> repos, String q) throws Exception {
    float[] vec;
    try {
      vec = state.llm().embed(q);
    } catch (RuntimeException e) {
      vec = new float[state.config().vectorDim()]; // embedding 不可用退关键词路
    }
    repoRootByName.clear();
    List<RepoIndex> opened = new ArrayList<>();
    try {
      var indices = new LinkedHashMap<String, RepoIndex>();
      for (Path repo : repos) {
        Path idxDir = RepoPaths.indexDir(repo);
        if (Files.exists(idxDir)) {
          RepoIndex idx = new RepoIndex(idxDir, state.config().vectorDim());
          indices.put(repo.getFileName().toString(), idx);
          opened.add(idx);
          repoRootByName.put(repo.getFileName().toString(), repo);
        }
      }
      return HybridSearcher.search(indices, q, vec, 50);
    } finally {
      for (RepoIndex idx : opened) {
        idx.close();
      }
    }
  }
}
```

- [ ] **Step 3: 手工验证**

重排过的仓库 → 搜索页输入关键词（如「通知」）→ 结果出现且高亮词加粗；切「全部仓库」多仓库联合返回；双击文件系统默认程序打开。

- [ ] **Step 4: 格式化 + 验证 + Commit**

```bash
mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: hybrid search view with highlight rendering and cross-repo scope"
```

---

### Task B9: Watch 生命周期装配 + 全流程冒烟

**Files:**
- Create: `src/main/java/cn/guoxy/xunwen/ui/WatchServiceBinder.java`
- Modify: `src/main/java/cn/guoxy/xunwen/ui/XunwenApp.java`（start/stop 接入）
- Modify: `src/main/java/cn/guoxy/xunwen/ui/IngestTask` 调用点（B5 的 sync 已有；watch 的增量走 ingest）

**Interfaces:**
- Consumes: `RepoWatcher`(T13) · `IngestTask.ingest`(B1 返回 MoveRecord) · `Fx.runAsync`
- Produces:
  - `WatchServiceBinder(AppState state)`：
    - `void bind(Path repo)`：关旧 watcher → `new RepoWatcher(repo, Duration.ofSeconds(2)).start()` → `Timeline(2000ms)` tick：`watcher.pollEvents()` → `List<Path> stable = watcher.drainStable()`（非空则后台 `IngestTask.ingest(stable)`，完成后对每个 `MoveRecord.to()` 调 `watcher.ignorePrefix(to)` 防回环 + 刷新待审/树视图提示）
    - `void unbind()`：timeline stop + watcher close
    - 监听 `state.currentRepoProperty`：变化即 bind/unbind
  - `XunwenApp.stop()` → `binder.unbind()`

- [ ] **Step 1: 实现 WatchServiceBinder**

```java
package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.pipeline.IngestTask;
import cn.guoxy.xunwen.pipeline.MoveRecord;
import cn.guoxy.xunwen.watch.RepoWatcher;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;

/** UI 驱动的仓库监视：Timeline 周期 tick，处理新文件并防移动回环（FR-11）。 */
public final class WatchServiceBinder implements AutoCloseable {
  private final AppState state;
  private RepoWatcher watcher;
  private Timeline timeline;

  public WatchServiceBinder(AppState state) {
    this.state = state;
    state.currentRepoProperty().addListener((obs, old, repo) -> {
      unbind();
      if (repo != null) {
        bind(repo);
      }
    });
  }

  private void bind(Path repo) {
    try {
      watcher = new RepoWatcher(repo, Duration.ofSeconds(2));
      watcher.start();
      timeline = new Timeline(new KeyFrame(javafx.util.Duration.seconds(2), e -> tick(repo)));
      timeline.setCycleCount(Timeline.INDEFINITE);
      timeline.play();
    } catch (Exception ex) {
      Fx.error("启动监视失败", ex);
    }
  }

  private void tick(Path repo) {
    try {
      watcher.pollEvents();
      List<Path> stable = watcher.drainStable();
      if (stable.isEmpty()) {
        return;
      }
      Fx.runAsync(
          () -> new IngestTask(repo, state.config(), state.llm(), state.extractor()).ingest(stable),
          records -> {
            for (MoveRecord r : records) {
              watcher.ignorePrefix(r.to()); // 防回环：自己移动产生的 CREATE 不再处理
            }
            // 不弹窗打扰：结果去类别树/待审页查看
          },
          t -> Fx.error("新文件处理失败", t));
    } catch (Exception e) {
      // tick 内异常不中断 timeline
    }
  }

  @Override
  public void close() {
    unbind();
  }

  private void unbind() {
    if (timeline != null) {
      timeline.stop();
      timeline = null;
    }
    if (watcher != null) {
      try {
        watcher.close();
      } catch (Exception ignored) {
        // 关闭失败无需处理
      }
      watcher = null;
    }
  }
}
```

`XunwenApp`：字段 `private WatchServiceBinder binder;`，`start(...)` 末尾 `binder = new WatchServiceBinder(state);`，`@Override public void stop() { if (binder != null) binder.close(); }`。

自动归位**不弹窗**（避免打扰），结果在类别树/待审页可见。

- [ ] **Step 2: 手工冒烟清单（全流程验收）**

1. `mvn javafx:run` → 设置页填 Ollama 地址（如 `http://localhost:11434/v1`、模型名）→ 测试连接通过
2. 添加真实资料文件夹 → 打开（sync 积压）
3. 类别树页「生成提案」→ 审阅修改 → 批准入树
4. 「重排仓库」→ 进度推进 → 完成后检查目录结构与待分类
5. 待审页归位几个文件、批准一个提议
6. 往仓库里复制一个新文件 → 约 4 秒内被自动归位（不弹窗，去对应类别目录确认）
7. 搜索页搜索关键词 → 高亮结果 → 双击打开
8. 「回滚批次」输入重排批次 → 目录结构还原
9. 关闭应用 → 重开 → 仓库列表、类别树、索引、搜索全部还在

- [ ] **Step 3: 全量回归 + 格式化 + Commit**

```bash
mvn -q test && mvn -q spotless:apply && mvn -q verify
git add -A && git commit -m "feat: watch lifecycle binding and full manual smoke checklist"
```

---

## 依赖顺序与验收

B1 → B2 →（B3 → B4/B5/B6/B7/B8 可并行）→ B9。引擎回归（Plan A 测试）在每任务 verify 中保持绿色。总验收 = B9 的 9 步手工冒烟清单。
