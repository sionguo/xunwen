package cn.guoxy.xunwen.index;

import java.io.IOException;
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
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
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

  public RepoIndex(Path indexDir) throws IOException {
    this(indexDir, true);
  }

  /** 只读打开：不持有 IndexWriter（不抢 Lucene 写锁），可与批量写任务并发。搜索/浏览路径专用。 */
  public static RepoIndex openReadOnly(Path indexDir) throws IOException {
    return new RepoIndex(indexDir, false);
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
    doc.add(new StringField(F_STATUS, d.status(), Store.YES));
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
  public List<StoredDoc> byStatus(String status, int limit) throws IOException {
    return storedOf(search(new TermQuery(new Term(F_STATUS, status)), limit).scoreDocs);
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
  public Map<String, Long> countByStatus() throws IOException {
    IndexReader reader = reader();
    if (reader == null) {
      return new LinkedHashMap<>(); // 全新索引尚无 segments 文件：空统计
    }
    TopDocs all =
        new IndexSearcher(reader).search(MatchAllDocsQuery.INSTANCE, Math.max(1, reader.numDocs()));
    Map<String, Long> out = new LinkedHashMap<>();
    for (ScoreDoc hit : all.scoreDocs) {
      String s = reader.storedFields().document(hit.doc, Set.of(F_STATUS)).get(F_STATUS);
      out.merge(s == null ? "" : s, 1L, Long::sum);
    }
    return out;
  }

  public ScoreDoc[] searchKeyword(String query, int k) throws IOException {
    var parser = new MultiFieldQueryParser(new String[] {F_NAME, F_CONTENT}, analyzer);
    try {
      return search(parser.parse(query), k).scoreDocs;
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      throw new IOException("Bad query: " + query, e);
    }
  }

  public TopDocs searchKnn(float[] vector, int k) throws IOException {
    return search(new KnnFloatVectorQuery(F_VEC, vector, k), k);
  }

  public List<StoredDoc> storedOf(ScoreDoc[] hits) {
    try {
      IndexReader reader = reader();
      if (reader == null) {
        return List.of(); // 全新索引尚无 segments 文件：空结果
      }
      List<StoredDoc> out = new ArrayList<>();
      for (ScoreDoc hit : hits) {
        out.add(toStored(reader.storedFields().document(hit.doc)));
      }
      return out;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static StoredDoc toStored(Document d) {
    var conf = d.getField(F_CONFIDENCE);
    return new StoredDoc(
        d.get(F_PATH),
        d.get(F_NAME),
        d.get(F_CATEGORY),
        d.get(F_STATUS),
        d.get(F_CONTENT),
        d.get(F_PROPOSED),
        d.get(F_REASON),
        conf == null ? 0.0 : conf.numericValue().doubleValue(), // 老索引无该字段
        d.get(F_CANDIDATE));
  }

  private TopDocs search(Query q, int k) throws IOException {
    IndexReader reader = reader();
    if (reader == null) {
      // 全新索引尚无 segments 文件：空结果（findByPath/searchKeyword/searchKnn 共用此路径）
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
    dir.close();
    analyzer.close();
  }
}
