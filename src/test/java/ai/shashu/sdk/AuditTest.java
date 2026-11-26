package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.*;

import ai.shashu.sdk.audit.AuditConversation;
import ai.shashu.sdk.audit.AuditEventRequest;
import ai.shashu.sdk.audit.AuditEventResponse;
import ai.shashu.sdk.audit.AuditMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code client.audit().events(...)} serialises the request to {@code POST
 * /v1/audit/events} in the gateway-expected shape and deserialises the verdict response.
 */
class AuditTest {

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
        .maxRetries(0)
        .build();
  }

  /**
   * A canned verdict body. The tests below assert the REQUEST (path, headers, typed body) and
   * {@code isThreat()}; nothing here asserts {@code threats}, so this string is a placeholder that
   * must not be read as a contract.
   *
   * <p>{@code threats} carries a value from the §3 code vocabulary ({@code
   * app/contract/envelope.py::CODES}); {@code dlp} is one, chosen because it cannot be mistaken for
   * the pre-contract pipeline {@code threat_type} names ({@code INJECTION}, {@code
   * Shashu/ingress-text}, …) that the wire stopped carrying on 2026-09-03.
   */
  private void enqueueVerdict(String verdict, boolean isThreat) {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{"
                    + "\"event_id\":\"audit-evt-1\","
                    + "\"verdict\":\""
                    + verdict
                    + "\","
                    + "\"is_threat\":"
                    + isThreat
                    + ","
                    + "\"threats\":[\"dlp\"],"
                    + "\"pillars\":[\"content\"],"
                    + "\"severity\":\"MEDIUM\""
                    + "}"));
  }

  @Test
  void events_posts_to_correct_path_with_typed_request() throws Exception {
    enqueueVerdict("WOULD_BLOCK", true);

    try (ShashuClient client = newClient()) {
      AuditEventResponse resp =
          client
              .audit()
              .events(
                  AuditEventRequest.builder()
                      .source("ms_copilot")
                      .externalEventId("evt_abc123")
                      .occurredAt("2026-05-02T14:00:00Z")
                      .conversation(
                          AuditConversation.builder()
                              .addMessage(new AuditMessage("user", "summarize Q3 minutes"))
                              .response("Q3 minutes: ...")
                              .build())
                      .userContext(Map.of("user_id", "alice@example.com"))
                      .model("gpt-4o")
                      .build());

      assertEquals("WOULD_BLOCK", resp.verdict());
      assertTrue(resp.isThreat());
    }

    RecordedRequest req = server.takeRequest();
    assertEquals("/v1/audit/events", req.getPath());
    JsonNode body = mapper.readTree(req.getBody().readUtf8());
    assertEquals("ms_copilot", body.path("source").asText());
    assertEquals("evt_abc123", body.path("external_event_id").asText());
    assertEquals("gpt-4o", body.path("model").asText());
    assertEquals("alice@example.com", body.path("user_context").path("user_id").asText());
    assertEquals("user", body.path("conversation").path("messages").get(0).path("role").asText());
  }

  @Test
  void events_omits_optional_fields_when_unset() throws Exception {
    enqueueVerdict("CLEAN", false);

    try (ShashuClient client = newClient()) {
      client
          .audit()
          .events(
              AuditEventRequest.builder()
                  .source("audit_other")
                  .conversation(
                      AuditConversation.builder()
                          .addMessage(new AuditMessage("user", "hi"))
                          .build())
                  .build());
    }

    RecordedRequest req = server.takeRequest();
    JsonNode body = mapper.readTree(req.getBody().readUtf8());
    assertTrue(body.path("external_event_id").isMissingNode());
    assertTrue(body.path("occurred_at").isMissingNode());
    assertTrue(body.path("user_context").isMissingNode());
    assertTrue(body.path("model").isMissingNode());
  }

  @Test
  void events_403_throws_ShashuPolicyViolation_with_parsed_message() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(403)
            .setBody("{\"error\":{\"message\":\"Vendor source not configured for this tenant\"}}"));

    try (ShashuClient client = newClient()) {
      ShashuPolicyViolation ex =
          assertThrows(
              ShashuPolicyViolation.class,
              () ->
                  client
                      .audit()
                      .events(
                          AuditEventRequest.builder()
                              .source("ms_copilot")
                              .conversation(
                                  AuditConversation.builder()
                                      .addMessage(new AuditMessage("user", "x"))
                                      .build())
                              .build()));

      assertEquals("Vendor source not configured for this tenant", ex.getMessage());
    }
  }
}
