package cn.guoxy.xunwen;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.classify.ColdStartSampler;
import cn.guoxy.xunwen.classify.TreeProposer;
import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.TikaExtractor;
import cn.guoxy.xunwen.index.FileStatus;
import cn.guoxy.xunwen.index.HybridSearcher;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.index.StoredDoc;
import cn.guoxy.xunwen.llm.OpenAiCompatClient;
import cn.guoxy.xunwen.pipeline.ApprovalService;
import cn.guoxy.xunwen.pipeline.BatchRunner;
import cn.guoxy.xunwen.pipeline.IngestTask;
import cn.guoxy.xunwen.pipeline.ReshuffleTask;
import cn.guoxy.xunwen.repo.CategoryTree;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真实 LLM 全流程集成冒烟（手动）：冷启动采样 → 一级树提案 → 重排 → 增量入库 → 人工归位 → 混合搜索。
 *
 * <p>类名不带 Test 后缀，{@code mvn verify} 自动跳过；需要本地 Ollama（chat + bge-m3 嵌入）。
 *
 * <p>运行：{@code mvn test -Dtest=RealLlmPipelineIT}
 */
@Tag("manual")
class RealLlmPipelineIT {

  private static final String BASE = "http://localhost:11434/v1";
  private static final String CHAT_MODEL = "qwen3.5:4b-mlx";
  private static final String EMBED_MODEL = "qwen3-embedding:0.6b";

  private static final GlobalConfig CFG =
      new GlobalConfig(BASE, BASE, "", CHAT_MODEL, EMBED_MODEL, 1024, 0.85, 0.5, 2);

  private static OpenAiCompatClient llm;

  @BeforeAll
  static void requireOllama() {
    // Ollama 未启动则跳过而非失败：日常 verify 不依赖本地服务
    Assumptions.assumeTrue(reachable(), "本地 Ollama 不可达，跳过真实 LLM 全流程冒烟");
    llm = new OpenAiCompatClient(BASE, BASE, "", CHAT_MODEL, EMBED_MODEL);
  }

  private static boolean reachable() {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create(BASE + "/models")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      return resp.statusCode() == 200;
    } catch (Exception e) {
      return false;
    }
  }

  /** 三类主题各两份 + 一份主题外文件，内容足够长保证文本层与分类依据。 */
  private static void writeSamples(Path root) throws Exception {
    tech(root, "微服务网关设计方案.txt", "本文给出统一 API 网关的设计方案。网关负责路由转发、鉴权、限流与灰度发布，".repeat(6));
    tech(root, "数据库分库分表方案.txt", "订单库按用户维度水平拆分，分片键取用户ID哈希，跨片查询走异构索引，".repeat(6));
    meeting(root, "季度经营会议纪要.txt", "会议确认三季度营收目标与产品路线图，市场部牵头竞品分析，研发部承诺交付节点，".repeat(6));
    meeting(root, "项目启动会纪要.txt", "项目启动会明确干系人分工、里程碑计划与沟通机制，风险登记册每周评审，".repeat(6));
    finance(root, "差旅报销制度说明.txt", "员工差旅报销须提供发票原件，住宿标准按职级上限执行，交通费实报实销，".repeat(6));
    finance(root, "采购付款流程说明.txt", "采购付款需三单匹配：订单、验收单与发票一致后进入付款审批流，超限额需总监签批，".repeat(6));
  }

  private static void tech(Path root, String name, String body) throws Exception {
    Files.writeString(root.resolve(name), body + "本方案经架构评审后实施，相关技术选型与接口规范见附件。");
  }

  private static void meeting(Path root, String name, String body) throws Exception {
    Files.writeString(root.resolve(name), body + "与会人员：总经理、各部门负责人。纪要由行政部归档。");
  }

  private static void finance(Path root, String name, String body) throws Exception {
    Files.writeString(root.resolve(name), body + "本制度自发布之日起执行，财务部负责解释。");
  }

  @Test
  void fullPipelineColdStartReshuffleIngestApproveSearch(@TempDir Path root) throws Exception {
    writeSamples(root);
    var extractor = new TikaExtractor();

    // 1. 冷启动：采样 → 一级扁平树提案
    var samples = ColdStartSampler.sample(root, extractor, 20);
    assertFalse(samples.isEmpty(), "采样不应为空");
    CategoryTree tree = new TreeProposer(llm).propose(samples);
    List<String> paths = tree.flattenPaths();
    assertTrue(paths.size() >= 3, "一级类别数至少 3（软指导无硬上限），实际 " + paths);
    assertTrue(paths.stream().noneMatch(p -> p.contains("/")), "应为一级扁平类别，实际 " + paths);
    assertTrue(paths.contains("其他"), "必须含兜底类别「其他」");
    tree.save(RepoPaths.categoriesYaml(root));

    // 2. 重排：文件进一级类别目录或待分类
    var stats = new ReshuffleTask(root, CFG, llm, extractor).run(BatchRunner.Progress.noop());
    assertEquals(6, stats.touched(), "重排统计应覆盖全部文件：" + stats.summary());
    List<Path> after;
    try (var walk = Files.walk(root)) {
      after =
          walk.filter(Files::isRegularFile)
              .filter(p -> !p.startsWith(RepoPaths.xunwenDir(root)))
              .toList();
    }
    assertEquals(6, after.size(), "重排不丢文件：" + after);
    for (Path p : after) {
      Path rel = root.relativize(p);
      assertTrue(rel.getNameCount() == 1 || rel.getNameCount() == 2, "只允许一级类别目录或待分类：" + rel);
    }

    // 3. 索引状态完备：每个文件在索引中有条目
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      for (Path p : after) {
        String rel = root.relativize(p).toString(); // macOS 上即 POSIX 形式
        assertTrue(idx.findByPath(rel).isPresent(), "索引缺失：" + rel);
      }
    }

    // 4. 增量入库：新文件被处理（归位或待分类），索引有条目
    Path newcomer = root.resolve("数据中台建设规划.txt");
    Files.writeString(newcomer, "数据中台统一元数据与指标口径，沉淀数仓分层模型，向业务提供自助分析服务。".repeat(8));
    new IngestTask(root, CFG, llm, extractor).ingest(List.of(newcomer));
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      var all = new java.util.ArrayList<StoredDoc>();
      all.addAll(idx.byStatus(FileStatus.INDEXED, 100));
      all.addAll(idx.byStatus(FileStatus.UNKNOWN, 100));
      all.addAll(idx.byStatus(FileStatus.PENDING, 100));
      assertTrue(all.stream().anyMatch(d -> d.name().equals("数据中台建设规划.txt")), "新文件应入库");
    }

    // 5. 若有待分类文件，人工归位后应为 INDEXED
    List<StoredDoc> unknown;
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      unknown = idx.byStatus(FileStatus.UNKNOWN, 100);
    }
    if (!unknown.isEmpty()) {
      String fname = unknown.getFirst().name();
      new ApprovalService(root, CFG, llm, extractor)
          .approve(
              root.resolve(unknown.getFirst().path()),
              paths.stream().filter(p -> !"其他".equals(p)).findFirst().orElse("其他"));
      try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
        // approve 移动后索引路径更新为新落点,按文件名查
        assertEquals(
            FileStatus.INDEXED,
            idx.byStatus(FileStatus.INDEXED, 100).stream()
                .filter(d -> d.name().equals(fname))
                .findFirst()
                .orElseThrow()
                .status());
      }
    }

    // 6. 混合搜索：真实嵌入 + 关键词双路
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      var indices = new LinkedHashMap<String, RepoIndex>();
      indices.put("it-repo", idx);
      float[] qvec = llm.embed("网关限流方案");
      var hits = HybridSearcher.search(indices, "网关", qvec, 10);
      assertFalse(hits.isEmpty(), "搜索应命中网关方案");
      assertTrue(hits.stream().anyMatch(h -> h.name().contains("网关")), "命中应含网关文档");
    }
  }
}
