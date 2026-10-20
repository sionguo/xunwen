package cn.guoxy.xunwen.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class OpenAiCompatClient implements LlmClient, EmbeddingClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  private final String baseUrl;
  private final String apiKey;
  private final String chatModel;
  private final String embedModel;

  public OpenAiCompatClient(
      String baseUrl, String apiKey, String chatModel, String embedModel, int vectorDim) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey == null ? "" : apiKey;
    this.chatModel = chatModel;
    this.embedModel = embedModel;
  }

  @Override
  public String chat(List<Msg> messages) {
    try {
      ObjectNode body = JSON.createObjectNode();
      body.put("model", chatModel);
      body.put("temperature", 0.1);
      body.putObject("response_format").put("type", "json_object");
      ArrayNode msgs = body.putArray("messages");
      for (Msg m : messages) {
        msgs.addObject().put("role", m.role()).put("content", m.content());
      }
      JsonNode resp = post(baseUrl + "/chat/completions", body);
      return resp.path("choices").path(0).path("message").path("content").asText();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("LLM chat failed: " + e.getMessage(), e);
    }
  }

  @Override
  public float[] embed(String text) {
    return embedAll(List.of(text)).get(0);
  }

  @Override
  public List<float[]> embedAll(List<String> texts) {
    try {
      ObjectNode body = JSON.createObjectNode();
      body.put("model", embedModel);
      body.putPOJO("input", texts);
      JsonNode data = post(baseUrl + "/embeddings", body).path("data");
      List<JsonNode> sorted = new ArrayList<>();
      data.forEach(sorted::add);
      sorted.sort(Comparator.comparingInt(n -> n.path("index").asInt()));
      return sorted.stream().map(n -> toArray(n.path("embedding"))).toList();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("Embedding failed: " + e.getMessage(), e);
    }
  }

  private static float[] toArray(JsonNode arr) {
    float[] out = new float[arr.size()];
    for (int i = 0; i < arr.size(); i++) {
      out[i] = (float) arr.get(i).asDouble();
    }
    return out;
  }

  private JsonNode post(String url, ObjectNode body) throws IOException, InterruptedException {
    var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5));
    req.header("Content-Type", "application/json");
    if (!apiKey.isBlank()) {
      req.header("Authorization", "Bearer " + apiKey);
    }
    req.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
    }
    return JSON.readTree(resp.body());
  }
}
