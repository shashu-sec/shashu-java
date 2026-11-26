package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.*;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.chat.RagContext;
import ai.shashu.sdk.chat.RagDoc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the typed {@link RagContext} setter + free-form {@code shashuMetadata} extras serialise
 * correctly under {@code shashu_metadata.rag_context} in the request body.
 */
class RagContextTest {

  private MockWebServer server;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  private ShashuClient newClient() {
    return ShashuClient.builder()
        .apiKey("sk-shashu-test")
        .baseUrl(server.url("/").toString())
        .build();
  }

  private void enqueueOk() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"fake-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"));
  }

  @Test
  void rag_context_serialises_under_shashu_metadata_rag_context() throws Exception {
    enqueueOk();
    try (ShashuClient client = newClient()) {
      client
          .chat()
          .completions()
          .create(
              ChatCompletionRequest.builder()
                  .model("gpt-4o")
                  .addMessage(ChatMessage.user("what's the refinance rate?"))
                  .ragContext(
                      RagContext.builder()
                          .corpus("policy_docs_v2")
                          .query("refinance")
                          .addDoc(RagDoc.of("POL-1", 0.92, "Refinance must occur within..."))
                          .addDoc(RagDoc.of("POL-2", 0.81, "Eligibility requires..."))
                          .build())
                  .build());
    }

    RecordedRequest req = server.takeRequest();
    JsonNode body = mapper.readTree(req.getBody().readUtf8());
    JsonNode rag = body.path("shashu_metadata").path("rag_context");
    assertEquals("policy_docs_v2", rag.path("corpus").asText());
    assertEquals("refinance", rag.path("query").asText());
    assertEquals(2, rag.path("docs").size());
    assertEquals("POL-1", rag.path("docs").get(0).path("id").asText());
    assertEquals(0.92, rag.path("docs").get(0).path("score").asDouble(), 0.0001);
    assertTrue(rag.path("docs").get(0).path("preview").asText().contains("Refinance"));
  }

  @Test
  void freeform_metadata_serialises_alongside_rag_context() throws Exception {
    enqueueOk();
    try (ShashuClient client = newClient()) {
      client
          .chat()
          .completions()
          .create(
              ChatCompletionRequest.builder()
                  .model("gpt-4o")
                  .addMessage(ChatMessage.user("hi"))
                  .ragContext(RagContext.builder().corpus("c").query("q").build())
                  .shashuMetadata("workflow_step", "Final_Summary")
                  .shashuMetadata("custom_field", "value")
                  .build());
    }

    RecordedRequest req = server.takeRequest();
    JsonNode meta = mapper.readTree(req.getBody().readUtf8()).path("shashu_metadata");
    assertEquals("Final_Summary", meta.path("workflow_step").asText());
    assertEquals("value", meta.path("custom_field").asText());
    assertEquals("c", meta.path("rag_context").path("corpus").asText());
  }

  @Test
  void no_rag_or_metadata_omits_the_field_entirely() throws Exception {
    enqueueOk();
    try (ShashuClient client = newClient()) {
      client
          .chat()
          .completions()
          .create(
              ChatCompletionRequest.builder()
                  .model("gpt-4o")
                  .addMessage(ChatMessage.user("hi"))
                  .build());
    }

    RecordedRequest req = server.takeRequest();
    JsonNode body = mapper.readTree(req.getBody().readUtf8());
    // Critical: when nothing is set, the field is omitted entirely
    // (not emitted as `"shashu_metadata": null`). Otherwise the
    // gateway would treat it as an explicit empty metadata block.
    assertTrue(
        body.path("shashu_metadata").isMissingNode(),
        "shashu_metadata field should be absent when neither RagContext nor extras set");
  }

  @Test
  void caller_explicit_rag_context_in_metadata_wins_over_typed_setter() throws Exception {
    // Escape hatch: caller stashes their own rag_context inside the
    // free-form metadata map. The typed RagContext setter must NOT
    // clobber it.
    enqueueOk();
    try (ShashuClient client = newClient()) {
      Map<String, Object> customRag = new HashMap<>();
      customRag.put("corpus", "from-caller-explicit");

      client
          .chat()
          .completions()
          .create(
              ChatCompletionRequest.builder()
                  .model("gpt-4o")
                  .addMessage(ChatMessage.user("hi"))
                  .ragContext(RagContext.builder().corpus("should-be-ignored").build())
                  .shashuMetadata("rag_context", customRag)
                  .build());
    }

    RecordedRequest req = server.takeRequest();
    JsonNode rag =
        mapper.readTree(req.getBody().readUtf8()).path("shashu_metadata").path("rag_context");
    assertEquals("from-caller-explicit", rag.path("corpus").asText());
  }

  @Test
  void rag_context_carries_through_to_streaming_path() throws Exception {
    // The streaming method internally rebuilds the request to force
    // stream=true. RagContext must be preserved through that rebuild
    // — losing it on streaming requests would silently bypass the
    // ingress-rag scanner for any streamed RAG app (a major gap).
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: [DONE]\n\n"));

    try (ShashuClient client = newClient()) {
      client
          .chat()
          .completions()
          .createStream(
              ChatCompletionRequest.builder()
                  .model("gpt-4o")
                  .addMessage(ChatMessage.user("hi"))
                  .ragContext(RagContext.builder().corpus("streamed-corpus").build())
                  .build())
          .close();
    }

    RecordedRequest req = server.takeRequest();
    JsonNode body = mapper.readTree(req.getBody().readUtf8());
    assertTrue(body.path("stream").asBoolean(), "stream forced to true");
    assertEquals(
        "streamed-corpus",
        body.path("shashu_metadata").path("rag_context").path("corpus").asText());
  }
}
