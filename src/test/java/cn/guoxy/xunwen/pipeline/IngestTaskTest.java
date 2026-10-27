package cn.guoxy.xunwen.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.index.Doc;
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
    return _ ->
        """
        {"category":"工作文档","candidates":[
          {"name":"工作文档","score":0.95},{"name":"其他","score":0.1}],"self":0.95}
        """;
  }

  private static ContentExtractor fakeExtractor() {
    return _ -> new ContentExtractor.Extracted("测试内容足够长的一些文字用于通过文本层判断逻辑", true);
  }

  private static void prepareRepo(@TempDir Path root) throws Exception {
    new CategoryTree(List.of(new Category("工作文档", "", List.of())))
        .save(RepoPaths.categoriesYaml(root));
  }

  @Test
  void ingestMovesAndIndexesNewFile(@TempDir Path root) throws Exception {
    prepareRepo(root);
    Path f = root.resolve("新通知.txt");
    Files.writeString(f, "又一份通知");
    new IngestTask(root, CFG, fakeLlm(), fakeExtractor()).ingest(List.of(f));
    assertTrue(Files.exists(root.resolve("工作文档/新通知.txt")));
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
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
    Files.delete(root.resolve("工作文档/将删除.txt")); // ingest 已移动，删移动后的已索引文件

    var stats = task.sync();
    assertTrue(stats.touched() >= 2); // 积压入库 + 删除清理（修改的文件被重处理也算）
    assertTrue(Files.exists(root.resolve("工作文档/积压文件.txt")));
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      assertTrue(idx.findByPath("工作文档/积压文件.txt").isPresent());
      assertTrue(idx.findByPath("工作文档/已入库.txt").isEmpty()); // 外部删除 → 索引清理
      assertTrue(idx.findByPath("工作文档/将删除.txt").isEmpty());
    }
  }

  @Test
  void syncPreservesPendingIndexEntries(@TempDir Path root) throws Exception {
    prepareRepo(root);
    Path pending = RepoPaths.pendingDir(root).resolve("a.txt");
    Files.createDirectories(RepoPaths.pendingDir(root));
    Files.writeString(pending, "低置信内容");
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      // 模拟 Reshuffle 后已入库的低置信文件（指纹与磁盘一致）
      idx.upsert(
          new Doc(
              "待分类/a.txt",
              "a.txt",
              "txt",
              "hash-x",
              Files.getLastModifiedTime(pending).toMillis(),
              Files.size(pending),
              "PENDING",
              "",
              "低置信内容",
              new float[] {1, 0, 0, 0}));
    }

    new IngestTask(root, CFG, fakeLlm(), fakeExtractor()).sync();

    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      assertEquals("PENDING", idx.findByPath("待分类/a.txt").orElseThrow().status());
    }
  }

  @Test
  void syncAndIngestWithoutTreeAreNoOps(@TempDir Path root) throws Exception {
    // 未冷启动的仓库（无 categories.yaml）：打开/watch 都不应抛异常，等冷启动后 sync 再处理
    Path f = root.resolve("未分类.txt");
    Files.writeString(f, "内容");
    var task = new IngestTask(root, CFG, fakeLlm(), fakeExtractor());
    assertEquals(0, task.sync().touched());
    assertTrue(task.ingest(List.of(f)).moved().isEmpty());
    assertTrue(Files.exists(f)); // 文件留在原地未被移动
  }

  @Test
  void unindexedFileInsidePendingDirIsIndexedInPlace(@TempDir Path root) throws Exception {
    prepareRepo(root);
    Path pending = Files.createDirectories(RepoPaths.pendingDir(root));
    Path f = pending.resolve("遗留文件.txt");
    Files.writeString(f, "低置信内容");

    int[] llmCalls = {0};
    LlmClient counting =
        _ -> {
          llmCalls[0]++;
          return "{\"category\":\"\",\"candidates\":[],\"self\":0.1}"; // UNKNOWN
        };
    var task = new IngestTask(root, CFG, counting, fakeExtractor());

    task.sync();
    assertTrue(Files.exists(pending.resolve("遗留文件.txt"))); // 未被改名/移动
    try (RepoIndex idx = RepoIndex.openWritable(RepoPaths.indexDir(root))) {
      assertEquals("UNKNOWN", idx.findByPath("待分类/遗留文件.txt").orElseThrow().status());
    }

    assertEquals(0, task.sync().touched()); // 第二轮：已入库，不再重复处理
    assertEquals(1, llmCalls[0]); // 只调用过一次 LLM，无自重复循环
  }

  @Test
  void syncReportsProgress(@TempDir Path root) throws Exception {
    prepareRepo(root);
    Path a = root.resolve("甲.txt");
    Files.writeString(a, "内容甲");
    Path b = root.resolve("乙.txt");
    Files.writeString(b, "内容乙");
    List<String> seen = new java.util.ArrayList<>();
    BatchRunner.Progress progress =
        (done, total, file) -> seen.add(done + "/" + total + ":" + file);
    new IngestTask(root, CFG, fakeLlm(), fakeExtractor()).sync(progress);
    assertEquals(2, seen.size()); // 每个入库文件一次进度回调
    assertTrue(seen.get(seen.size() - 1).startsWith("2/2:"));
  }
}
