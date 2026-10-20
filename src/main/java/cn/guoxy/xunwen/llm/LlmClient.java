package cn.guoxy.xunwen.llm;

import java.util.List;

public interface LlmClient {
  String chat(List<Msg> messages);

  record Msg(String role, String content) {
    public static Msg system(String content) {
      return new Msg("system", content);
    }

    public static Msg user(String content) {
      return new Msg("user", content);
    }
  }
}
