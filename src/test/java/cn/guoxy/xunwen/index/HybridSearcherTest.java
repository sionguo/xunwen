package cn.guoxy.xunwen.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HybridSearcherTest {

  private void seed(RepoIndex idx, String path, String name, String content, float[] vec)
      throws Exception {
    idx.upsert(new Doc(path, name, "txt", "h", 1L, 2L, FileStatus.INDEXED, "工作文档", content, vec));
  }

  @Test
  void keywordAndVectorFuseAcrossRepos(@TempDir Path dir) throws Exception {
    try (RepoIndex a = RepoIndex.openWritable(dir.resolve("a"));
        RepoIndex b = RepoIndex.openWritable(dir.resolve("b"))) {
      seed(a, "x.txt", "销售总结.txt", "本季度销售业绩总结：营收增长百分之二十。", new float[] {1, 0, 0, 0});
      seed(b, "y.txt", "合同.txt", "劳动合同条款一览。", new float[] {0, 0, 1, 0});

      var indices = new LinkedHashMap<String, RepoIndex>();
      indices.put("仓库A", a);
      indices.put("仓库B", b);

      var hits = HybridSearcher.search(indices, "销售", new float[] {1, 0, 0, 0}, 10);
      assertFalse(hits.isEmpty());
      assertEquals("仓库A", hits.getFirst().repoName());
      assertEquals("x.txt", hits.getFirst().path());
      assertEquals(FileStatus.INDEXED, hits.getFirst().status()); // 结果带状态（UI 展示用）
      assertTrue(hits.getFirst().highlight().contains("<"));
      assertTrue(hits.stream().anyMatch(h -> h.repoName().equals("仓库B"))); // 两仓都返回
    }
  }

  @Test
  void rrfCombinesWhenDocHitsBothPaths(@TempDir Path dir) throws Exception {
    try (RepoIndex idx = RepoIndex.openWritable(dir.resolve("a"))) {
      // 内容同时匹配关键词且向量最近
      seed(idx, "both.txt", "both.txt", "机器学习入门笔记", new float[] {1, 0, 0, 0});
      seed(idx, "kw.txt", "kw.txt", "机器学习进阶路线", new float[] {0, 1, 0, 0});
      var indices = new LinkedHashMap<String, RepoIndex>();
      indices.put("R", idx);
      var hits = HybridSearcher.search(indices, "机器学习", new float[] {1, 0, 0, 0}, 5);
      assertEquals("both.txt", hits.getFirst().path()); // 双路命中排第一
    }
  }
}
