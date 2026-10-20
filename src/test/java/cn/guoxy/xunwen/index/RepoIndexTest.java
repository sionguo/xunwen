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
      ScoreDoc[] hits = idx.searchKnn(new float[] {1, 0, 0, 0}, 1).scoreDocs;
      assertEquals("1.pdf", idx.storedOf(hits).get(0).path());
    }
  }
}
