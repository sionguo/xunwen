package cn.guoxy.xunwen.config;

public record GlobalConfig(
    String baseUrl,
    String embedBaseUrl,
    String apiKey,
    String chatModel,
    String embedModel,
    int vectorDim,
    double autoThreshold,
    double pendingThreshold,
    int concurrency) {

  public GlobalConfig {
    if (embedBaseUrl == null || embedBaseUrl.isBlank()) {
      embedBaseUrl = baseUrl; // 旧配置/未单独配置：向量与对话同址
    }
  }

  /** 兼容旧调用点：向量服务未单独配置时与对话同址。 */
  public GlobalConfig(
      String baseUrl,
      String apiKey,
      String chatModel,
      String embedModel,
      int vectorDim,
      double autoThreshold,
      double pendingThreshold,
      int concurrency) {
    this(
        baseUrl,
        null,
        apiKey,
        chatModel,
        embedModel,
        vectorDim,
        autoThreshold,
        pendingThreshold,
        concurrency);
  }

  public static GlobalConfig defaults() {
    return new GlobalConfig(
        "http://localhost:11434/v1", null, "", "qwen3.5:4b-mlx", "bge-m3", 1024, 0.85, 0.5, 2);
  }
}
