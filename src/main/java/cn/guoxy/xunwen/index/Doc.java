package cn.guoxy.xunwen.index;

/**
 * 入库文档的完整快照。proposed/reason/confidence/candidatePath 是分类时的 LLM 原始判定 （FR-12 状态可见）：待审页直接读索引预选候选，无需重新调
 * LLM。
 */
public record Doc(
    String path,
    String name,
    String ext,
    String hash,
    long mtime,
    long size,
    String status,
    String categoryPath,
    String content,
    float[] vector,
    String proposed,
    String reason,
    double confidence,
    String candidatePath) {

  /** 兼容无分类元数据的调用点（测试造数据/人工归位重写）。 */
  public Doc(
      String path,
      String name,
      String ext,
      String hash,
      long mtime,
      long size,
      String status,
      String categoryPath,
      String content,
      float[] vector) {
    this(
        path, name, ext, hash, mtime, size, status, categoryPath, content, vector, "", "", 0.0, "");
  }

  /** 兼容无候选类别字段的调用点。 */
  public Doc(
      String path,
      String name,
      String ext,
      String hash,
      long mtime,
      long size,
      String status,
      String categoryPath,
      String content,
      float[] vector,
      String proposed,
      String reason,
      double confidence) {
    this(
        path,
        name,
        ext,
        hash,
        mtime,
        size,
        status,
        categoryPath,
        content,
        vector,
        proposed,
        reason,
        confidence,
        "");
  }
}
