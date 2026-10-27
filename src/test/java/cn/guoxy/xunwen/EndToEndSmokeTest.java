package cn.guoxy.xunwen;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.classify.TreeProposer;
import cn.guoxy.xunwen.classify.TreeProposer.Sample;
import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.HybridSearcher;
import cn.guoxy.xunwen.index.RepoIndex;
import cn.guoxy.xunwen.llm.LlmClient;
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
  void fullPipelineColdStartReshuffleIngestSearch(@TempDir Path root) throws Exception {
    // 1. 造仓库：3 个文件
    Files.writeString(root.resolve("季度报告.txt"), "销售报告内容");
    Files.writeString(root.resolve("年会通知.txt"), "通知内容");
    Files.writeString(Files.createDirectories(root.resolve("旧结构")).resolve("发票.txt"), "发票");

    GlobalConfig cfg =
        new GlobalConfig("http://localhost:9/v1", "", "m", "e", 4, 0.85, 0.5, 2); // 4 维便于测试
    LlmClient llm = fakeLlm();

    // 2. 冷启动
    CategoryTree tree = new TreeProposer(llm).propose(List.of(new Sample("x.txt", "内容")));
    tree.save(RepoPaths.categoriesYaml(root));
    assertEquals(List.of("工作文档", "工作文档/报告", "其他"), tree.flattenPaths());

    // 3. 首次重排
    var stats = new ReshuffleTask(root, cfg, llm, EXTRACTOR).run(ReshuffleTask.Progress.noop());
    assertTrue(Files.exists(root.resolve("工作文档/季度报告.txt")));
    assertTrue(Files.exists(RepoPaths.pendingDir(root).resolve("发票.txt"))); // UNKNOWN → 待分类
    assertEquals(2, stats.indexed()); // 统计可见：两个高置信文件归档
    assertEquals(1, stats.unknown()); // 发票走 UNKNOWN → 待分类

    // 4. 增量入库
    Path newFile = root.resolve("新通知.txt");
    Files.writeString(newFile, "又一份通知");
    new IngestTask(root, cfg, llm, EXTRACTOR).ingest(List.of(newFile));
    assertTrue(Files.exists(root.resolve("工作文档/新通知.txt")));

    // 5. 混合搜索（fake LLM 无嵌入能力，文档向量由单位向量兜底；查询侧零向量故仅关键词路生效）
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      var indices = new LinkedHashMap<String, RepoIndex>();
      indices.put("测试仓库", idx);
      var hits = HybridSearcher.search(indices, "通知", new float[] {0, 0, 0, 0}, 10);
      assertTrue(hits.size() >= 2);
      assertTrue(hits.stream().allMatch(h -> h.path().contains("通知")));
    }
  }
}
