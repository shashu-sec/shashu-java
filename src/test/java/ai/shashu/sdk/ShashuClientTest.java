package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatCompletionResponse;
import ai.shashu.sdk.chat.ChatMessage;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests against an in-process OkHttp MockWebServer — hermetic, no live gateway required.
 * Live-gateway integration tests live in a separate test source set (queued).
 */
class ShashuClientTest {

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

  @Test
  void builder_withoutApiKey_throws() {
    // Make sure env var doesn't accidentally satisfy the key check.
    // (We can't unset env vars from inside the JVM, but a non-empty
    // value would still be a valid key from the SDK's POV; the test
    // is only meaningful when the env is empty.)
    if ((System.getenv("TRUSTGATE_API_KEY") != null
            && !System.getenv("TRUSTGATE_API_KEY").isBlank())
        || (System.getenv("SHASHU_API_KEY") != null
            && !System.getenv("SHASHU_API_KEY").isBlank())) {
      return; // skip in environments where the env is set
    }
    ShashuConfigException ex =
        assertThrows(
            ShashuConfigException.class,
            () -> ShashuClient.builder().baseUrl("http://127.0.0.1:9").build());
    assertTrue(
        ex.getMessage().contains("SHASHU key") || ex.getMessage().contains("token provider"));
  }

  @Test
  void chatCompletions_happyPath_roundtripsResponse() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{"
                    + "\"id\":\"chatcmpl-fake-1\","
                    + "\"object\":\"chat.completion\","
                    + "\"created\":1234567890,"
                    + "\"model\":\"gpt-4o\","
                    + "\"choices\":[{"
                    + "\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"hi back\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}"
                    + "}"));

    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .build()) {

      ChatCompletionResponse resp =
          client
              .chat()
              .completions()
              .create(
                  ChatCompletionRequest.builder()
                      .model("gpt-4o")
                      .addMessage(ChatMessage.user("hi"))
                      .build());

      assertEquals("chatcmpl-fake-1", resp.id());
      assertEquals(1, resp.choices().size());
      assertEquals("hi back", resp.choices().get(0).message().content());
      assertEquals(7, resp.usage().totalTokens());
    }

    RecordedRequest req = server.takeRequest();
    assertEquals("POST", req.getMethod());
    assertEquals("/v1/chat/completions", req.getPath());
    assertEquals("Bearer sk-trustgate-test", req.getHeader("Authorization"));
    assertTrue(req.getHeader("User-Agent").startsWith("shashu-java/"));
  }

  // Note: the legacy `streamingNotYetSupported` test was removed when
  // streaming landed in v0.2 work. The replacement assertion lives in
  // StreamingTest#create_with_stream_true_throws_routing_caller_to_streaming_method
  // which checks that create() now throws IllegalStateException pointing
  // at createStream() instead of UnsupportedOperationException.

  @Test
  void chatCompletions_5xx_notRetried_byDefault() throws Exception {
    // Only one 5xx enqueued: if the SDK retried, the second call would
    // hit an empty queue and hang/fail — proving it does NOT retry.
    server.enqueue(
        new MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"boom\"}}"));

    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .maxRetries(2)
            .build()) {

      ShashuException ex =
          assertThrows(
              ShashuException.class,
              () ->
                  client
                      .chat()
                      .completions()
                      .create(
                          ChatCompletionRequest.builder()
                              .model("gpt-4o")
                              .addMessage(ChatMessage.user("hi"))
                              .build()));
      assertEquals(500, ex.statusCode());
    }

    // 5xx is no longer retried by default — the gateway already retries providers.
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void chatCompletions_5xx_retried_underLegacyPolicy() throws Exception {
    server.enqueue(
        new MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"boom\"}}"));
    server.enqueue(
        new MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"boom\"}}"));

    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .maxRetries(1)
            .legacyRetryPolicy(true)
            .build()) {

      ShashuException ex =
          assertThrows(
              ShashuException.class,
              () ->
                  client
                      .chat()
                      .completions()
                      .create(
                          ChatCompletionRequest.builder()
                              .model("gpt-4o")
                              .addMessage(ChatMessage.user("hi"))
                              .build()));
      assertEquals(500, ex.statusCode());
    }

    // 1 initial + 1 retry under the legacy policy.
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void chatCompletions_429_retried_thenSucceeds() throws Exception {
    // Retry-After: 0 → honored, retries instantly.
    server.enqueue(
        new MockResponse()
            .setResponseCode(429)
            .setHeader("Retry-After", "0")
            .setBody("{\"error\":{\"type\":\"rate_limit_exceeded\",\"message\":\"slow\"}}"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(okBody()));

    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .maxRetries(2)
            .build()) {

      ChatCompletionResponse resp =
          client
              .chat()
              .completions()
              .create(
                  ChatCompletionRequest.builder()
                      .model("gpt-4o")
                      .addMessage(ChatMessage.user("hi"))
                      .build());
      assertEquals("chatcmpl-fake-1", resp.id());
    }

    assertEquals(2, server.getRequestCount()); // 429 is retried by default
  }

  @Test
  void chatCompletions_connectionError_retried_thenSucceeds() throws Exception {
    // Force the first connection to drop before responding → IOException;
    // the SDK should retry and succeed on the second (real) response.
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(okBody()));

    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .maxRetries(2)
            .build()) {

      // Succeeding at all proves the connection error was retried —
      // without a retry the IOException would have propagated.
      ChatCompletionResponse resp =
          client
              .chat()
              .completions()
              .create(
                  ChatCompletionRequest.builder()
                      .model("gpt-4o")
                      .addMessage(ChatMessage.user("hi"))
                      .build());
      assertEquals("chatcmpl-fake-1", resp.id());
    }
  }

  @Test
  void chatCompletions_403_notRetried() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(403)
            .setBody(
                "{\"error\":{\"message\":\"blocked by policy\",\"type\":\"invalid_request_error\"}}"));

    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .maxRetries(2)
            .build()) {

      assertThrows(
          ShashuPolicyViolation.class,
          () ->
              client
                  .chat()
                  .completions()
                  .create(
                      ChatCompletionRequest.builder()
                          .model("gpt-4o")
                          .addMessage(ChatMessage.user("hi"))
                          .build()));
    }

    assertEquals(1, server.getRequestCount()); // 403 is never retried
  }

  private static String okBody() {
    return "{"
        + "\"id\":\"chatcmpl-fake-1\","
        + "\"object\":\"chat.completion\","
        + "\"created\":1234567890,"
        + "\"model\":\"gpt-4o\","
        + "\"choices\":[{"
        + "\"index\":0,"
        + "\"message\":{\"role\":\"assistant\",\"content\":\"hi back\"},"
        + "\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}"
        + "}";
  }
}
