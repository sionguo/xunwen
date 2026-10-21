package cn.guoxy.xunwen.index;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

  private final FSDirectory dir;
  private final IndexWriter writer;
  private final SmartChineseAnalyzer analyzer;

  public RepoIndex(Path indexDir) throws IOException {
    this.dir = FSDirectory.open(indexDir);
    this.analyzer = new SmartChineseAnalyzer();
    this.writer = new IndexWriter(dir, new IndexWriterConfig(analyzer));
  }

  public IndexReader reader() throws IOException {
    return DirectoryReader.open(dir);
  }

  public void upsert(Doc d) throws IOException {
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
    doc.add(
        new KnnFloatVectorField(
            F_VEC, d.vector(), org.apache.lucene.index.VectorSimilarityFunction.COSINE));
    writer.addDocument(doc);
    writer.commit();
  }

  public void deleteByPath(String path) throws IOException {
    writer.deleteDocuments(new Term(F_PATH, path));
    writer.commit();
  }

  public Optional<StoredDoc> findByPath(String path) throws IOException {
    Query q = new TermQuery(new Term(F_PATH, path));
    TopDocs docs = search(q, 1);
    List<StoredDoc> out = storedOf(docs.scoreDocs);
    return out.isEmpty() ? Optional.empty() : Optional.of(out.getFirst());
  }

  public List<FileInfo> allFiles() throws IOException {
    try (IndexReader reader = reader()) {
      // MatchAllDocsQuery 只命中 live docs，deleted doc 自然排除
      TopDocs all = search(MatchAllDocsQuery.INSTANCE, Math.max(1, reader.numDocs()));
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
    } catch (IndexNotFoundException emptyIndex) {
      return List.of(); // 全新索引尚无 segments 文件：空结果
    }
  }

  /** 按状态检索待审列表（PENDING/UNKNOWN），返回存储字段。 */
  public List<StoredDoc> byStatus(String status, int limit) throws IOException {
    return storedOf(search(new TermQuery(new Term(F_STATUS, status)), limit).scoreDocs);
  }

  /** 全量存储文档（单 reader 内检索 + 读存储字段）。 */
  public List<StoredDoc> all() throws IOException {
    try (IndexReader reader = reader()) {
      TopDocs all =
          new IndexSearcher(reader)
              .search(MatchAllDocsQuery.INSTANCE, Math.max(1, reader.numDocs()));
      List<StoredDoc> out = new ArrayList<>();
      for (ScoreDoc hit : all.scoreDocs) {
        Document d = reader.storedFields().document(hit.doc);
        out.add(
            new StoredDoc(
                d.get(F_PATH),
                d.get(F_NAME),
                d.get(F_CATEGORY),
                d.get(F_STATUS),
                d.get(F_CONTENT)));
      }
      return out;
    } catch (IndexNotFoundException emptyIndex) {
      return List.of(); // 全新索引尚无 segments 文件：空结果
    }
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
    try (IndexReader reader = reader()) {
      List<StoredDoc> out = new ArrayList<>();
      for (ScoreDoc hit : hits) {
        Document d = reader.storedFields().document(hit.doc);
        out.add(
            new StoredDoc(
                d.get(F_PATH),
                d.get(F_NAME),
                d.get(F_CATEGORY),
                d.get(F_STATUS),
                d.get(F_CONTENT)));
      }
      return out;
    } catch (IndexNotFoundException emptyIndex) {
      return List.of(); // 全新索引尚无 segments 文件：空结果
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private TopDocs search(Query q, int k) throws IOException {
    try (IndexReader reader = reader()) {
      return new IndexSearcher(reader).search(q, k);
    } catch (IndexNotFoundException emptyIndex) {
      // 全新索引尚无 segments 文件：空结果（findByPath/searchKeyword/searchKnn 共用此路径）
      return new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[0]);
    }
  }

  @Override
  public void close() throws IOException {
    writer.close();
    dir.close();
    analyzer.close();
  }
}
