package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.shashu.sdk.harness.MockGateway;
import ai.shashu.sdk.internal.RetrievalTestAccess;
import ai.shashu.sdk.routing.ShashuRoutingInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Memory harness: what the gateway actually receives")
class MemoryHarnessTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private MockGateway gateway;

  @BeforeEach
  void setUp() {
    RetrievalTestAccess.reset();
    gateway = new MockGateway();
  }

  @AfterEach
  void tearDown() {
    RetrievalTestAccess.reset();
  }

  @Test
  @DisplayName("declareMemory attaches memory_context.v1")
  void declare_memory_arrives() throws Exception {
    Memory.declareMemory(
        List.of(Map.of("id", "note-1", "preview", "User prefers UTC.")),
        "agent_notes",
        "agent",
        "sdk");
    callModel();
    JsonNode ctx = gateway.single().memoryContext();
    assertEquals(1, ctx.get("v").asInt());
    assertEquals("agent_notes", ctx.get("store").asText());
    assertEquals("agent", ctx.get("scope").asText());
    assertEquals("sdk", ctx.get("source").asText());
    assertEquals("note-1", ctx.get("items").get(0).get("id").asText());
    assertEquals("User prefers UTC.", ctx.get("items").get(0).get("preview").asText());
    assertNull(gateway.single().ragContext());
  }

  @Test
  @DisplayName("memoryScope binds the declaration to the unit of work")
  void memory_scope_arrives() throws Exception {
    try (var ignored =
        Memory.memoryScope(
            List.of(Map.of("id", "n2", "preview", "crew note")), "crew_store", "crew", "tool")) {
      callModel();
    }
    JsonNode ctx = gateway.single().memoryContext();
    assertEquals("crew_store", ctx.get("store").asText());
    assertEquals("crew", ctx.get("scope").asText());
    assertEquals("tool", ctx.get("source").asText());
  }

  @Test
  @DisplayName("empty items send no memory_context")
  void empty_items_are_absent() throws Exception {
    Memory.declareMemory(List.of(), "agent_notes");
    callModel();
    assertFalse(gateway.single().hasShashuMetadata());
    assertNull(gateway.single().memoryContext());
  }

  @Test
  @DisplayName("explicit memory_context on the body wins")
  void explicit_wins() throws Exception {
    Memory.declareMemory(List.of(Map.of("id", "ambient", "preview", "ambient")), "ambient_store");
    Map<String, Object> body = chatBody();
    body.put(
        "shashu_metadata",
        Map.of(
            "memory_context",
            Map.of(
                "v", 1,
                "store", "explicit_store",
                "scope", "agent",
                "source", "manual",
                "items", List.of(Map.of("id", "E", "preview", "explicit")))));
    post(body);
    JsonNode ctx = gateway.single().memoryContext();
    assertEquals("explicit_store", ctx.get("store").asText());
    assertEquals("E", ctx.get("items").get(0).get("id").asText());
  }

  @Test
  @DisplayName("SHASHU_DISABLE=1 is a no-op")
  void disable_is_a_noop() throws Exception {
    RetrievalTestAccess.setEnv(Map.of("SHASHU_DISABLE", "1"));
    Memory.declareMemory(List.of(Map.of("id", "m1", "preview", "hidden")), "agent_notes");
    callModel();
    assertNull(gateway.single().memoryContext());
    assertFalse(gateway.single().hasShashuMetadata());
  }

  private OkHttpClient client() {
    return new OkHttpClient.Builder()
        .addInterceptor(new ShashuRoutingInterceptor("sk-shashu-test", MockGateway.BASE_URL, null))
        .addInterceptor(gateway)
        .build();
  }

  private static Map<String, Object> chatBody() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", "gpt-4o-mini");
    body.put("messages", List.of(Map.of("role", "user", "content", "hello")));
    return body;
  }

  private void callModel() {
    post(chatBody());
  }

  private void post(Map<String, Object> body) {
    try {
      byte[] encoded = MAPPER.writeValueAsBytes(body);
      Request request =
          new Request.Builder()
              .url("https://api.openai.com/v1/chat/completions")
              .header("Authorization", "Bearer sk-vendor-key")
              .post(RequestBody.create(encoded, MediaType.get("application/json")))
              .build();
      try (Response response = client().newCall(request).execute()) {
        assertEquals(200, response.code());
      }
    } catch (IOException e) {
      throw new AssertionError("the mock gateway never does IO", e);
    }
  }
}
