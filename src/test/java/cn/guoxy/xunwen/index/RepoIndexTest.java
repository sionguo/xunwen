package cn.guoxy.xunwen.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
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
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"))) {
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
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"))) {
      idx.upsert(doc("a.pdf", "a.pdf", "内容", new float[] {1, 0, 0, 0}));
      idx.deleteByPath("a.pdf");
      assertTrue(idx.findByPath("a.pdf").isEmpty());
    }
  }

  @Test
  void keywordSearchHitsChineseContent(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"))) {
      idx.upsert(doc("1.pdf", "发票.pdf", "餐饮发票 报销凭证", new float[] {0, 0, 0, 1}));
      idx.upsert(doc("2.pdf", "合同.pdf", "劳动合同 条款", new float[] {0, 0, 1, 0}));
      ScoreDoc[] hits = idx.searchKeyword("发票", 10);
      assertEquals(1, hits.length);
      assertEquals("1.pdf", idx.storedOf(hits).getFirst().path());
    }
  }

  @Test
  void knnSearchReturnsNearest(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"))) {
      idx.upsert(doc("1.pdf", "1.pdf", "c1", new float[] {1, 0, 0, 0}));
      idx.upsert(doc("2.pdf", "2.pdf", "c2", new float[] {0, 1, 0, 0}));
      ScoreDoc[] hits = idx.searchKnn(new float[] {1, 0, 0, 0}, 1).scoreDocs;
      assertEquals("1.pdf", idx.storedOf(hits).getFirst().path());
    }
  }

  @Test
  void byStatusFiltersDocuments(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"))) {
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
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"))) {
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
    try (RepoIndex idx = new RepoIndex(dir.resolve("index"))) {
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
}
