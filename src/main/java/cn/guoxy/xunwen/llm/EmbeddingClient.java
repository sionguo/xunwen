package cn.guoxy.xunwen.llm;

import java.util.List;

public interface EmbeddingClient {
  float[] embed(String text);

  List<float[]> embedAll(List<String> texts);
}
