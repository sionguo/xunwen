package cn.guoxy.xunwen.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.guoxy.xunwen.config.GlobalConfig;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 查询向量缓存：同一查询词只打一次 embedding 端点；保存配置后缓存整体失效。 */
class AppStateTest {

  @Test
  void queryVectorCachesAndInvalidatesOnConfigSave(@TempDir Path dir) throws Exception {
    AtomicInteger hits = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          hits.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] resp =
              "{\"data\":[{\"index\":0,\"embedding\":[0.5,0.5,0.5,0.5]}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          exchange.getResponseBody().write(resp);
          exchange.close();
        });
    server.start();
    try {
      String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
      Path configFile = dir.resolve("config.json");
      Path reposFile = dir.resolve("repos.json");
      AppState state = new AppState(configFile, reposFile);
      state.saveConfig(new GlobalConfig(baseUrl, "", "m", "e", 4, 0.85, 0.6, 2));

      float[] first = state.queryVector("季度销售报告");
      assertNotNull(first);
      assertEquals(1, hits.get());
      float[] second = state.queryVector("季度销售报告");
      assertArrayEquals(first, second);
      assertEquals(1, hits.get()); // 第二次命中缓存，服务端只被打一次
      // 保存新配置：模型/维度可能已变，缓存整体失效
      state.saveConfig(new GlobalConfig(baseUrl, "", "m", "e", 4, 0.85, 0.6, 2));
      float[] third = state.queryVector("季度销售报告");
      assertArrayEquals(first, third);
      assertEquals(2, hits.get()); // 缓存已失效，重新请求端点
    } finally {
      server.stop(0);
    }
  }
}
