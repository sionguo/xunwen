package cn.guoxy.xunwen.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.store.FSDirectory;

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
  public static final String F_PROPOSED = "proposed";
  public static final String F_REASON = "reason";
  public static final String F_CONFIDENCE = "confidence";
  public static final String F_CANDIDATE = "candidate";

  private final FSDirectory dir;
  private final IndexWriter writer; // 只读模式为 null
  private final SmartChineseAnalyzer analyzer;
  private IndexReader view; // 共享读视图：可写=writer 的 NRT reader（可见未提交变更），只读=目录快照
  private boolean dirty; // 有未提交写入；批次边界（close）统一提交

  /** 可写打开：持有 IndexWriter（Lucene 写锁）。调用方必须持有 AppState.engineLock（单写者互斥）。 */
  public static RepoIndex openWritable(Path indexDir) throws IOException {
    return new RepoIndex(indexDir, true);
  }

  /** 只读打开：不持有 IndexWriter（不抢 Lucene 写锁），可与批量写任务并发；读的是已提交快照。 零副作用：目录不存在时不创建，直接返回空视图（读方法全空结果）。 */
  public static RepoIndex openReadOnly(Path indexDir) throws IOException {
    return Files.exists(indexDir) ? new RepoIndex(indexDir, false) : emptyView();
  }

  private static final RepoIndex EMPTY_VIEW = new RepoIndex();

  private static RepoIndex emptyView() {
    return EMPTY_VIEW; // 无状态可安全共享：reader 为 null，读方法全空结果，close 零操作
  }

  private RepoIndex() { // 空视图实例，仅供 openReadOnly 在目录不存在时返回
    this.dir = null;
    this.analyzer = null;
    this.writer = null;
  }

  private RepoIndex(Path indexDir, boolean writable) throws IOException {
    this.dir = FSDirectory.open(indexDir);
    this.analyzer = new SmartChineseAnalyzer();
    this.writer = writable ? new IndexWriter(dir, new IndexWriterConfig(analyzer)) : null;
  }

  /**
   * 共享读视图，调用方不得关闭（close() 统一释放）：可写实例经 NRT 立即可见自身未提交的写入； 索引尚无 segments 文件（全新未建）时返回 null，读方法按空结果处理。
   */
  public IndexReader reader() throws IOException {
    if (dir == null) {
      return null; // 空视图（目录不存在的只读打开）
    }
    if (view == null) {
      if (writer != null) {
        view = DirectoryReader.open(writer);
      } else {
        try {
          view = DirectoryReader.open(dir);
        } catch (IndexNotFoundException emptyIndex) {
          return null; // 全新索引尚无 segments 文件
        }
      }
    } else if (writer != null) {
      DirectoryReader refreshed = DirectoryReader.openIfChanged((DirectoryReader) view);
      if (refreshed != null) {
        view.close();
        view = refreshed;
      }
    }
    return view;
  }

  public void upsert(Doc d) throws IOException {
    if (writer == null) {
      throw new IllegalStateException("只读索引不可写入");
    }
    writer.deleteDocuments(new Term(F_PATH, d.path()));
    Document doc = new Document();
    doc.add(new StringField(F_PATH, d.path(), Store.YES));
    doc.add(new TextField(F_NAME, d.name(), Store.YES));
    doc.add(new StringField(F_EXT, d.ext(), Store.YES));
    doc.add(new StringField(F_HASH, d.hash(), Store.YES));
    doc.add(new StoredField(F_MTIME, d.mtime()));
    doc.add(new StoredField(F_SIZE, d.size()));
    doc.add(new StringField(F_STATUS, d.status().name(), Store.YES));
    doc.add(new StringField(F_CATEGORY, d.categoryPath(), Store.YES));
    doc.add(new TextField(F_CONTENT, d.content(), Store.YES));
    doc.add(new StringField(F_PROPOSED, d.proposed(), Store.YES));
    doc.add(new StringField(F_REASON, d.reason(), Store.YES));
    doc.add(new StoredField(F_CONFIDENCE, d.confidence()));
    doc.add(new StringField(F_CANDIDATE, d.candidatePath(), Store.YES));
    doc.add(
        new KnnFloatVectorField(
            F_VEC, d.vector(), org.apache.lucene.index.VectorSimilarityFunction.COSINE));
    writer.addDocument(doc);
    dirty = true; // 批次边界（close）统一 commit，入库 N 文件不再 N 次 fsync
  }

  public void deleteByPath(String path) throws IOException {
    if (writer == null) {
      throw new IllegalStateException("只读索引不可写入");
    }
    writer.deleteDocuments(new Term(F_PATH, path));
    dirty = true;
  }

  /**
   * 归位重写：已入库条目物理移动到 dest 后同步索引。保留 content/语义向量/提议/理由/置信度； path/name/ext/mtime/size 从 dest
   * 现算，hash/status/categoryPath 由调用方给定，候选类别即最终归类。 条目不存在时抛出——归位对象必须已入库，静默造空条目会掩盖不一致。
   */
  public void relocate(
      String oldRel, String newRel, Path dest, String hash, FileStatus status, String categoryPath)
      throws IOException {
    TopDocs hit = search(new TermQuery(new Term(F_PATH, oldRel)), 1);
    if (hit.scoreDocs.length == 0) {
      throw new IllegalStateException("归位条目不在索引中: " + oldRel);
    }
    int docId = hit.scoreDocs[0].doc;
    float[] vec = vectorOf(docId);
    if (vec == null) {
      throw new IllegalStateException("归位条目缺少向量字段: " + oldRel);
    }
    Document d = reader().storedFields().document(docId);
    var conf = d.getField(F_CONFIDENCE);
    deleteByPath(oldRel);
    String name = dest.getFileName().toString();
    int dot = name.lastIndexOf('.');
    upsert(
        new Doc(
            newRel,
            name,
            dot > 0 ? name.substring(dot + 1) : "",
            hash,
            Files.getLastModifiedTime(dest).toMillis(),
            Files.size(dest),
            status,
            categoryPath,
            d.get(F_CONTENT),
            vec,
            d.get(F_PROPOSED),
            d.get(F_REASON),
            conf == null ? 0.0 : conf.numericValue().doubleValue(), // 老条目无该字段
            categoryPath));
  }

  /** 读回第 docId 号文档的存储向量（按 leaf 定位后查向量值表）；无向量字段返回 null。 */
  private float[] vectorOf(int docId) throws IOException {
    for (LeafReaderContext ctx : reader().leaves()) {
      if (docId < ctx.docBase || docId >= ctx.docBase + ctx.reader().maxDoc()) {
        continue;
      }
      FloatVectorValues values = ctx.reader().getFloatVectorValues(F_VEC);
      if (values == null) {
        return null;
      }
      var it = values.iterator();
      int leafDoc = docId - ctx.docBase;
      while (it.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
        if (it.docID() == leafDoc) {
          return values.vectorValue(it.index());
        }
      }
      return null;
    }
    return null;
  }

  /** 中性向量：Lucene COSINE 拒绝全零，入库无真实向量时以单位向量兜底（维度由调用方配置给定）。 */
  public static float[] neutralVector(int dim) {
    float[] v = new float[dim];
    v[0] = 1f;
    return v;
  }

  /** 跳过向量：全零表达「本次检索不走 KNN 路」（与 HybridSearcher.isAllZero 的约定一致）。 */
  public static float[] skipVector(int dim) {
    return new float[dim];
  }

  public Optional<StoredDoc> findByPath(String path) throws IOException {
    Query q = new TermQuery(new Term(F_PATH, path));
    TopDocs docs = search(q, 1);
    List<StoredDoc> out = storedOf(docs.scoreDocs);
    return out.isEmpty() ? Optional.empty() : Optional.of(out.getFirst());
  }

  public List<FileInfo> allFiles() throws IOException {
    IndexReader reader = reader();
    if (reader == null) {
      return List.of(); // 全新索引尚无 segments 文件：空结果
    }
    // MatchAllDocsQuery 只命中 live docs，deleted doc 自然排除
    TopDocs all =
        new IndexSearcher(reader).search(MatchAllDocsQuery.INSTANCE, Math.max(1, reader.numDocs()));
    List<FileInfo> out = new ArrayList<>();
    for (org.apache.lucene.search.ScoreDoc hit : all.scoreDocs) {
      Document d = reader.storedFields().document(hit.doc);
      out.add(
          new FileInfo(
              d.get(F_PATH),
              d.get(F_HASH),
              d.getField(F_MTIME).numericValue().longValue(),
              d.getField(F_SIZE).numericValue().longValue()));
    }
    return out;
  }

  /** 按状态检索待审列表（PENDING/UNKNOWN），返回存储字段。 */
  public List<StoredDoc> byStatus(FileStatus status, int limit) throws IOException {
    return storedOf(search(new TermQuery(new Term(F_STATUS, status.name())), limit).scoreDocs);
  }

  /** 全量存储文档（单 reader 内检索 + 读存储字段）。 */
  public List<StoredDoc> all() throws IOException {
    IndexReader reader = reader();
    if (reader == null) {
      return List.of(); // 全新索引尚无 segments 文件：空结果
    }
    TopDocs all =
        new IndexSearcher(reader).search(MatchAllDocsQuery.INSTANCE, Math.max(1, reader.numDocs()));
    List<StoredDoc> out = new ArrayList<>();
    for (ScoreDoc hit : all.scoreDocs) {
      out.add(toStored(reader.storedFields().document(hit.doc)));
    }
    return out;
  }

  /** 状态分布统计（总览页）：status → 数量，保持首次出现顺序。 */
  public Map<FileStatus, Long> countByStatus() throws IOException {
    IndexReader reader = reader();
    if (reader == null) {
      return new LinkedHashMap<>(); // 全新索引尚无 segments 文件：空统计
    }
    TopDocs all =
        new IndexSearcher(reader).search(MatchAllDocsQuery.INSTANCE, Math.max(1, reader.numDocs()));
    Map<FileStatus, Long> out = new LinkedHashMap<>();
    for (ScoreDoc hit : all.scoreDocs) {
      String raw = reader.storedFields().document(hit.doc, Set.of(F_STATUS)).get(F_STATUS);
      out.merge(FileStatus.fromLucene(raw), 1L, Long::sum);
    }
    return out;
  }

  /** 以现成 Query 检索（调用方自管解析，避免同一查询重复 parse）。 */
  public ScoreDoc[] searchDocs(Query q, int k) throws IOException {
    return search(q, k).scoreDocs;
  }

  /** 一条 kNN 邻居证据：已归档邻居的类别路径与相似度（分层分类/降级分类用）。 */
  public record Neighbor(String categoryPath, float similarity) {}

  /** 在已归档（INDEXED 且有类别）文档中查 vec 的最近邻，按相似度降序；超采样 2 倍补足过滤损耗。 */
  public List<Neighbor> knnNeighbors(float[] vec, int k) throws IOException {
    IndexReader reader = reader();
    if (reader == null) {
      return List.of();
    }
    TopDocs top =
        new IndexSearcher(reader).search(new KnnFloatVectorQuery(F_VEC, vec, k * 2), k * 2);
    List<Neighbor> out = new ArrayList<>();
    for (ScoreDoc hit : top.scoreDocs) {
      Document d = reader.storedFields().document(hit.doc, Set.of(F_STATUS, F_CATEGORY));
      if (FileStatus.INDEXED.name().equals(d.get(F_STATUS)) && !d.get(F_CATEGORY).isBlank()) {
        out.add(new Neighbor(d.get(F_CATEGORY), hit.score));
      }
    }
    return out;
  }

  public TopDocs searchKnn(float[] vector, int k) throws IOException {
    return search(new KnnFloatVectorQuery(F_VEC, vector, k), k);
  }

  public List<StoredDoc> storedOf(ScoreDoc[] hits) throws IOException {
    IndexReader reader = reader();
    if (reader == null) {
      return List.of(); // 全新索引尚无 segments 文件：空结果
    }
    List<StoredDoc> out = new ArrayList<>();
    for (ScoreDoc hit : hits) {
      out.add(toStored(reader.storedFields().document(hit.doc)));
    }
    return out;
  }

  private static StoredDoc toStored(Document d) {
    var conf = d.getField(F_CONFIDENCE);
    return new StoredDoc(
        d.get(F_PATH),
        d.get(F_NAME),
        d.get(F_CATEGORY),
        FileStatus.fromLucene(d.get(F_STATUS)),
        d.get(F_CONTENT),
        d.get(F_PROPOSED),
        d.get(F_REASON),
        conf == null ? 0.0 : conf.numericValue().doubleValue(), // 老索引无该字段
        d.get(F_CANDIDATE));
  }

  private TopDocs search(Query q, int k) throws IOException {
    IndexReader reader = reader();
    if (reader == null) {
      // 全新索引尚无 segments 文件：空结果（findByPath/searchDocs/searchKnn 共用此路径）
      return new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[0]);
    }
    return new IndexSearcher(reader).search(q, k);
  }

  @Override
  public void close() throws IOException {
    if (writer != null) {
      if (dirty) {
        writer.commit(); // 批次落盘：整个任务一次 commit（close 即批次边界）
      }
      writer.close();
    }
    if (view != null) {
      view.close();
    }
    if (dir != null) {
      dir.close();
    }
    if (analyzer != null) {
      analyzer.close();
    }
  }
}
