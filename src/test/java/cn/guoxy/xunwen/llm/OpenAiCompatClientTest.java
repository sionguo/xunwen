package cn.guoxy.xunwen.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiCompatClientTest {
  private HttpServer server;
  private String baseUrl;
  private final ObjectMapper json = new ObjectMapper();
  private final AtomicReference<String> lastChatBody = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          lastChatBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] resp =
              ("{\"choices\":[{\"message\":{\"content\":"
                      + json.writeValueAsString("{\"category\":\"工作文档\"}")
                      + "}}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          exchange.getResponseBody().write(resp);
          exchange.close();
        });
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] resp =
              "{\"data\":[{\"index\":1,\"embedding\":[0.4,0.5]},{\"index\":0,\"embedding\":[0.1,0.2]}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          exchange.getResponseBody().write(resp);
          exchange.close();
        });
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void chatSendsModelAndJsonFormatAndParsesContent() {
    var client = new OpenAiCompatClient(baseUrl, "sk-test", "m1", "e1");
    String out = client.chat(List.of(LlmClient.Msg.system("sys"), LlmClient.Msg.user("usr")));
    assertEquals("{\"category\":\"工作文档\"}", out);
    JsonNode body = json.readTree(lastChatBody.get());
    assertEquals("m1", body.get("model").asString());
    assertEquals("json_object", body.get("response_format").get("type").asString());
    assertEquals(2, body.get("messages").size());
  }

  @Test
  void embedAllSortsByIndex() {
    var client = new OpenAiCompatClient(baseUrl, "", "m1", "e1");
    List<float[]> vecs = client.embedAll(List.of("a", "b"));
    assertArrayEquals(new float[] {0.1f, 0.2f}, vecs.get(0));
    assertArrayEquals(new float[] {0.4f, 0.5f}, vecs.get(1));
  }

  @Test
  void embedSingleDelegates() {
    var client = new OpenAiCompatClient(baseUrl, "", "m1", "e1");
    assertArrayEquals(new float[] {0.1f, 0.2f}, client.embed("hello"));
  }
}
