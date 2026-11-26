package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatCompletionResponse;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.chat.RagContext;
import ai.shashu.sdk.chat.RagDoc;
import ai.shashu.sdk.internal.CredentialResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Sdk11WireTest {

  private static final String BUSY_SENTENCE =
      "The gateway is busy. Retry after the advertised wait.";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  private ShashuClient.Builder builder() {
    return ShashuClient.builder().apiKey("shashu_sk_test").baseUrl(server.url("/").toString());
  }

  private static ChatCompletionRequest req(RagContext rag) {
    ChatCompletionRequest.Builder b =
        ChatCompletionRequest.builder().model("m").addMessage(ChatMessage.user("hi"));
    if (rag != null) {
      b.ragContext(rag);
    }
    return b.build();
  }

  private static String okBody(String extra) {
    String base =
        "{\"id\":\"chatcmpl-mock\",\"object\":\"chat.completion\",\"choices\":[{"
            + "\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
    if (extra == null) {
      return base;
    }
    return base.substring(0, base.length() - 1) + "," + extra + "}";
  }

  @Test
  void a_only_shashu_names_are_sent() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(okBody(null)));
    try (ShashuClient client = builder().maxRetries(0).build()) {
      client.chat().completions().create(req(null));
    }
    RecordedRequest seen = server.takeRequest();
    boolean sentOld = false;
    boolean sentShashu = false;
    for (int i = 0; i < seen.getHeaders().size(); i++) {
      String name = seen.getHeaders().name(i).toLowerCase(java.util.Locale.ROOT);
      if (name.startsWith("x-trustgate-") || name.equals("trustgate-version")) {
        sentOld = true;
      }
      if (name.equals("x-shashu-trace-id") || name.equals("x-shashu-session-id")) {
        sentShashu = true;
      }
    }
    assertFalse(sentOld);
    assertTrue(sentShashu);
    assertEquals("2026-09-01", seen.getHeader("Shashu-Version"));
  }

  @Test
  void b_shashu_response_headers_and_body_are_read() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setHeader("x-shashu-decision", "allow")
            .setHeader("x-shashu-code", "ok")
            .setHeader("Shashu-Version", "2026-09-01")
            .setBody(okBody("\"shashu\":{\"decision\":\"allow\",\"code\":\"ok\"}")));
    try (ShashuClient client = builder().maxRetries(0).build()) {
      ChatCompletionResponse out = client.chat().completions().create(req(null));
      ShashuDecision d = Shashu.decisionOf(out);
      assertNotNull(d);
      assertEquals("allow", d.decision());
    }
  }

  @Test
  void c_old_response_names_are_ignored() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setHeader("x-trustgate-decision", "block")
            .setHeader("TrustGate-Version", "2026-09-01")
            .setBody(okBody("\"trustgate\":{\"decision\":\"block\",\"code\":\"jailbreak\"}")));
    try (ShashuClient client = builder().maxRetries(0).build()) {
      ChatCompletionResponse out = client.chat().completions().create(req(null));
      assertNull(Shashu.decisionOf(out));
    }
  }

  @Test
  void d_shashu_metadata_is_sent_for_rag() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(okBody(null)));
    try (ShashuClient client = builder().maxRetries(0).build()) {
      client
          .chat()
          .completions()
          .create(
              req(
                  RagContext.builder()
                      .corpus("handbook")
                      .addDoc(RagDoc.of("D", 1.0, "p"))
                      .build()));
    }
    JsonNode body = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
    assertEquals(
        "handbook", body.path("shashu_metadata").path("rag_context").path("corpus").asText());
    assertTrue(body.path("trustgate_metadata").isMissingNode());
  }

  @Test
  void e_shashu_sk_and_token_provider_error_text() {
    assertEquals(
        "Give either a SHASHU key or a token provider, not both.",
        CredentialResolver.BOTH_OR_NEITHER);
    ShashuConfigException ex =
        assertThrows(
            ShashuConfigException.class,
            () -> new CredentialResolver("shashu_sk_x", () -> "p", true));
    assertEquals(CredentialResolver.BOTH_OR_NEITHER, ex.getMessage());
    assertEquals("shashu_sk_x", new CredentialResolver("shashu_sk_x", null, true).get());
  }

  @Test
  void busy_retry_resends_the_same_built_request_including_declaration() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setHeader("Retry-After", "0")
            .setHeader("x-should-retry", "true")
            .setBody(
                "{\"error\":{\"message\":\"" + BUSY_SENTENCE + "\",\"code\":\"gateway_busy\"}}"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(okBody(null)));
    Retrieval.declareRetrieval(java.util.List.of("CANARY-retry"), "policy_docs_v2", "backups");
    try (ShashuClient client = builder().maxRetries(2).build()) {
      client.chat().completions().create(req(null));
    }
    String firstBody = server.takeRequest().getBody().readUtf8();
    String retryBody = server.takeRequest().getBody().readUtf8();
    assertEquals(firstBody, retryBody);
    assertTrue(firstBody.contains("CANARY-retry"));
    assertTrue(firstBody.contains("policy_docs_v2"));
  }

  @Test
  void f_busy_retry_waits_then_succeeds() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setHeader("Retry-After", "0")
            .setHeader("x-should-retry", "true")
            .setBody(
                "{\"error\":{\"message\":\""
                    + BUSY_SENTENCE
                    + "\",\"code\":\"gateway_busy\",\"shashu\":{\"decision\":\"fail\",\"code\":\"gateway_busy\"}}}"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(okBody(null)));
    try (ShashuClient client = builder().maxRetries(2).build()) {
      client.chat().completions().create(req(null));
    }
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void f_busy_gives_up_after_max_and_keeps_gateway_sentence() throws Exception {
    for (int i = 0; i < 8; i++) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(503)
              .setHeader("Retry-After", "0")
              .setHeader("x-should-retry", "true")
              .setBody(
                  "{\"error\":{\"message\":\""
                      + BUSY_SENTENCE
                      + "\",\"code\":\"gateway_busy\",\"shashu\":{\"decision\":\"fail\",\"code\":\"gateway_busy\"}}}"));
    }
    try (ShashuClient client = builder().maxRetries(2).build()) {
      ShashuException ex =
          assertThrows(ShashuException.class, () -> client.chat().completions().create(req(null)));
      assertEquals(BUSY_SENTENCE, ex.getMessage());
    }
    assertEquals(3, server.getRequestCount());
  }

  @Test
  void f_no_retry_without_invitation() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setBody(
                "{\"error\":{\"message\":\"" + BUSY_SENTENCE + "\",\"code\":\"gateway_busy\"}}"));
    try (ShashuClient client = builder().maxRetries(2).build()) {
      assertThrows(ShashuException.class, () -> client.chat().completions().create(req(null)));
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void f_max_retries_zero_disables() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setHeader("Retry-After", "0")
            .setHeader("x-should-retry", "true")
            .setBody(
                "{\"error\":{\"message\":\""
                    + BUSY_SENTENCE
                    + "\",\"code\":\"gateway_busy\",\"shashu\":{\"decision\":\"fail\",\"code\":\"gateway_busy\"}}}"));
    try (ShashuClient client = builder().maxRetries(0).build()) {
      assertThrows(ShashuException.class, () -> client.chat().completions().create(req(null)));
    }
    assertEquals(1, server.getRequestCount());
  }
}
