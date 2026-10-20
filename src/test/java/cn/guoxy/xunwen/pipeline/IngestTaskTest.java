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
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
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

    int processed = task.sync();
    assertTrue(processed >= 2); // 积压入库 + 删除清理（修改的文件被重处理也算）
    assertTrue(Files.exists(root.resolve("工作文档/积压文件.txt")));
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
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
    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
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

    try (RepoIndex idx = new RepoIndex(RepoPaths.indexDir(root))) {
      assertEquals("PENDING", idx.findByPath("待分类/a.txt").orElseThrow().status());
    }
  }
}
