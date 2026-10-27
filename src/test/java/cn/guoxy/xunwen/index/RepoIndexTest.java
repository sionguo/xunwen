package cn.guoxy.xunwen.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.LockObtainFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoIndexTest {

  private Doc doc(String path, String name, String content, float[] vec) {
    return new Doc(path, name, "pdf", "hash-" + path, 1L, 2L, "INDEXED", "工作文档", content, vec);
  }

  @Test
  void upsertThenFindByPath(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openWritable(dir.resolve("index"))) {
      idx.upsert(doc("a/b.pdf", "b.pdf", "季度销售总结报告内容", new float[] {1, 0, 0, 0}));
      idx.upsert(doc("a/b.pdf", "b.pdf", "内容已更新版本二", new float[] {0, 1, 0, 0}));
      Optional<StoredDoc> hit = idx.findByPath("a/b.pdf");
      assertTrue(hit.isPresent());
      assertEquals("内容已更新版本二", hit.get().content()); // upsert 覆盖旧文档
      assertEquals(1, idx.reader().numDocs());
      List<FileInfo> files = idx.allFiles();
      assertEquals(1, files.size());
      assertEquals("a/b.pdf", files.getFirst().path());
      assertEquals("hash-a/b.pdf", files.getFirst().hash());
    }
  }

  @Test
  void deleteByPathRemoves(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openWritable(dir.resolve("index"))) {
      idx.upsert(doc("a.pdf", "a.pdf", "内容", new float[] {1, 0, 0, 0}));
      idx.deleteByPath("a.pdf");
      assertTrue(idx.findByPath("a.pdf").isEmpty());
    }
  }

  @Test
  void keywordSearchHitsChineseContent(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openWritable(dir.resolve("index"))) {
      idx.upsert(doc("1.pdf", "发票.pdf", "餐饮发票 报销凭证", new float[] {0, 0, 0, 1}));
      idx.upsert(doc("2.pdf", "合同.pdf", "劳动合同 条款", new float[] {0, 0, 1, 0}));
      ScoreDoc[] hits = idx.searchKeyword("发票", 10);
      assertEquals(1, hits.length);
      assertEquals("1.pdf", idx.storedOf(hits).getFirst().path());
    }
  }

  @Test
  void knnSearchReturnsNearest(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openWritable(dir.resolve("index"))) {
      idx.upsert(doc("1.pdf", "1.pdf", "c1", new float[] {1, 0, 0, 0}));
      idx.upsert(doc("2.pdf", "2.pdf", "c2", new float[] {0, 1, 0, 0}));
      ScoreDoc[] hits = idx.searchKnn(new float[] {1, 0, 0, 0}, 1).scoreDocs;
      assertEquals("1.pdf", idx.storedOf(hits).getFirst().path());
    }
  }

  @Test
  void byStatusFiltersDocuments(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openWritable(dir.resolve("index"))) {
      idx.upsert(
          new Doc(
              "p.pdf", "p.pdf", "pdf", "h", 1L, 2L, "PENDING", "", "c", new float[] {1, 0, 0, 0}));
      idx.upsert(
          new Doc(
              "q.pdf",
              "q.pdf",
              "pdf",
              "h",
              1L,
              2L,
              "INDEXED",
              "工作文档",
              "c",
              new float[] {0, 1, 0, 0}));
      assertEquals(1, idx.byStatus("PENDING", 10).size());
      assertEquals("p.pdf", idx.byStatus("PENDING", 10).get(0).path());
      assertEquals(2, idx.all().size());
    }
  }

  @Test
  void classificationMetadataRoundTrips(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openWritable(dir.resolve("index"))) {
      idx.upsert(
          new Doc(
              "p.pdf",
              "p.pdf",
              "pdf",
              "h",
              1L,
              2L,
              "UNKNOWN",
              "",
              "内容",
              new float[] {1, 0, 0, 0},
              "发票凭证",
              "餐饮发票无法归入现有类别",
              0.42,
              "财务税务"));
      StoredDoc d = idx.findByPath("p.pdf").orElseThrow();
      assertEquals("发票凭证", d.proposed());
      assertEquals("餐饮发票无法归入现有类别", d.reason());
      assertEquals(0.42, d.confidence(), 1e-9);
      assertEquals("财务税务", d.candidatePath()); // 候选类别往返（待审页预选）
      // 老条目（无元数据字段）读出时取默认值
      idx.upsert(
          new Doc(
              "q.pdf", "q.pdf", "pdf", "h", 1L, 2L, "PENDING", "", "c", new float[] {0, 1, 0, 0}));
      StoredDoc old = idx.findByPath("q.pdf").orElseThrow();
      assertEquals("", old.proposed());
      assertEquals(0.0, old.confidence());
      assertEquals("", old.candidatePath());
    }
  }

  @Test
  void countByStatusAggregates(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openWritable(dir.resolve("index"))) {
      idx.upsert(
          new Doc(
              "p.pdf", "p.pdf", "pdf", "h", 1L, 2L, "PENDING", "", "c", new float[] {1, 0, 0, 0}));
      idx.upsert(
          new Doc(
              "q.pdf",
              "q.pdf",
              "pdf",
              "h",
              1L,
              2L,
              "INDEXED",
              "工作文档",
              "c",
              new float[] {0, 1, 0, 0}));
      idx.upsert(
          new Doc(
              "r.pdf",
              "r.pdf",
              "pdf",
              "h",
              1L,
              2L,
              "INDEXED",
              "其他",
              "c",
              new float[] {0, 0, 1, 0}));
      var counts = idx.countByStatus();
      assertEquals(2L, counts.get("INDEXED"));
      assertEquals(1L, counts.get("PENDING"));
    }
  }

  @Test
  void uncommittedWritesVisibleToSameInstance(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openWritable(dir.resolve("index"))) {
      idx.upsert(doc("a.pdf", "a.pdf", "内容", new float[] {1, 0, 0, 0}));
      idx.deleteByPath("a.pdf");
      assertTrue(idx.findByPath("a.pdf").isEmpty()); // 同实例写后立即可读（NRT）
    }
  }

  @Test
  void closePersistsBatchCommits(@TempDir Path dir) throws Exception {
    Path idxDir = dir.resolve("index");
    try (RepoIndex idx = RepoIndex.openWritable(idxDir)) {
      idx.upsert(doc("a.pdf", "a.pdf", "内容", new float[] {1, 0, 0, 0}));
    } // close 即批次边界：整批一次 commit
    try (RepoIndex reopen = RepoIndex.openWritable(idxDir)) {
      assertTrue(reopen.findByPath("a.pdf").isPresent());
    }
  }

  @Test
  void readOnlyModeRejectsWrites(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openReadOnly(dir.resolve("index"))) {
      assertThrows(
          IllegalStateException.class,
          () -> idx.upsert(doc("a.pdf", "a.pdf", "c", new float[] {1, 0, 0, 0})));
      assertThrows(IllegalStateException.class, () -> idx.deleteByPath("a.pdf"));
    }
  }

  @Test
  void readOnlySearchWorksWhileWritableHoldsLock(@TempDir Path dir) throws Exception {
    // 可写实例持有 Lucene 写锁且未提交变更（模拟批量任务进行中）；
    // 只读实例可并发打开检索，看到的是已提交状态（此处为空索引）
    try (RepoIndex writable = RepoIndex.openWritable(dir.resolve("index"))) {
      writable.upsert(doc("1.pdf", "发票.pdf", "餐饮发票内容", new float[] {1, 0, 0, 0}));
      try (RepoIndex ro = RepoIndex.openReadOnly(dir.resolve("index"))) {
        assertTrue(ro.all().isEmpty());
      }
      // 单写者互斥的对照面：持锁期间第二个可写打开必须失败（证明上面的只读并发不是假象）
      assertThrows(
          LockObtainFailedException.class, () -> RepoIndex.openWritable(dir.resolve("index")));
    }
  }

  @Test
  void readOnlyDoesNotCreateMissingDirectory(@TempDir Path dir) throws Exception {
    Path missing = dir.resolve("nope");
    try (RepoIndex idx = RepoIndex.openReadOnly(missing)) {
      assertFalse(Files.exists(missing)); // 只读零副作用：不创建目录
      assertTrue(idx.all().isEmpty());
      assertTrue(idx.byStatus("PENDING", 10).isEmpty());
      assertTrue(idx.countByStatus().isEmpty());
      assertTrue(idx.findByPath("x.pdf").isEmpty());
    }
  }

  @Test
  void knnNeighborsOnlyCountsIndexedWithCategory(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openWritable(dir.resolve("index"))) {
      idx.upsert(
          new Doc(
              "a.txt",
              "a.txt",
              "txt",
              "h",
              1L,
              2L,
              "INDEXED",
              "工作文档",
              "内容",
              new float[] {1, 0, 0, 0}));
      idx.upsert(
          new Doc(
              "b.txt",
              "b.txt",
              "txt",
              "h",
              1L,
              2L,
              "INDEXED",
              "工作文档",
              "内容",
              new float[] {0.9f, 0.1f, 0, 0}));
      idx.upsert(
          new Doc(
              "c.txt", "c.txt", "txt", "h", 1L, 2L, "PENDING", "", "内容", new float[] {1, 0, 0, 0}));
      var neighbors = idx.knnNeighbors(new float[] {1, 0, 0, 0}, 8);
      assertEquals(2, neighbors.size()); // PENDING/无类别邻居被过滤
      assertEquals("工作文档", neighbors.getFirst().categoryPath());
    }
  }

  @Test
  void relocatePreservesVectorAndMetadata(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openWritable(dir.resolve("index"))) {
      idx.upsert(
          new Doc(
              "待分类/p.pdf",
              "p.pdf",
              "pdf",
              "h",
              1L,
              2L,
              "PENDING",
              "",
              "原始内容",
              new float[] {0, 1, 0, 0},
              "发票凭证",
              "无法归类",
              0.42,
              "财务税务"));
      Path dest = dir.resolve("归档/p.pdf");
      Files.createDirectories(dest.getParent());
      Files.writeString(dest, "文件本体");
      idx.relocate("待分类/p.pdf", "归档/p.pdf", dest, "hash2", "INDEXED", "工作文档");

      // 处置与落点更新；候选即最终归类
      StoredDoc d = idx.findByPath("归档/p.pdf").orElseThrow();
      assertEquals("INDEXED", d.status());
      assertEquals("工作文档", d.categoryPath());
      assertEquals("工作文档", d.candidatePath());
      // 保留：内容与分类元数据
      assertEquals("原始内容", d.content());
      assertEquals("发票凭证", d.proposed());
      assertEquals("无法归类", d.reason());
      assertEquals(0.42, d.confidence(), 1e-9);
      assertTrue(idx.findByPath("待分类/p.pdf").isEmpty()); // 旧条目已删
      // 语义向量保留（黑盒）：原向量 {0,1,0,0} 的最近邻是归位后的它且相似度为 1
      // （若写成中性向量 {1,0,0,0}，cos=0 过不了门槛——knnNeighbors 本身不过滤相似度）
      var neighbors = idx.knnNeighbors(new float[] {0, 1, 0, 0}, 1);
      assertEquals(1, neighbors.size());
      assertEquals("工作文档", neighbors.getFirst().categoryPath());
      assertTrue(neighbors.getFirst().similarity() > 0.5f);
    }
  }
}
