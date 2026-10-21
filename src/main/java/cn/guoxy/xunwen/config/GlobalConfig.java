package cn.guoxy.xunwen.config;

public record GlobalConfig(
    String baseUrl,
    String apiKey,
    String chatModel,
    String embedModel,
    int vectorDim,
    double autoThreshold,
    double pendingThreshold,
    int concurrency) {

  public static GlobalConfig defaults() {
    return new GlobalConfig(
        "http://localhost:11434/v1", "", "qwen3.55:4b-mlx", "bge-m3", 1024, 0.85, 0.5, 2);
  }
}
