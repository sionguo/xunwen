package cn.guoxy.xunwen.index;

/** 索引读出的文档：含状态与分类元数据（提议/理由/置信度/候选类别），供 UI 直接展示与预选。 */
public record StoredDoc(
    String path,
    String name,
    String categoryPath,
    String status,
    String content,
    String proposed,
    String reason,
    double confidence,
    String candidatePath) {

  public StoredDoc {
    proposed = proposed == null ? "" : proposed;
    reason = reason == null ? "" : reason;
    candidatePath = candidatePath == null ? "" : candidatePath;
  }

  /** 兼容只关心基础字段的调用点。 */
  public StoredDoc(String path, String name, String categoryPath, String status, String content) {
    this(path, name, categoryPath, status, content, "", "", 0.0, "");
  }

  /** 兼容无候选类别字段的调用点。 */
  public StoredDoc(
      String path,
      String name,
      String categoryPath,
      String status,
      String content,
      String proposed,
      String reason,
      double confidence) {
    this(path, name, categoryPath, status, content, proposed, reason, confidence, "");
  }
}
