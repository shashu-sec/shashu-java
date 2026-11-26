package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Server-side attempt counts (MockWebServer). max_retries=0 → 1; recover after one busy → 2;
 * default retries give-up → 3.
 */
class RetryExactCountTest {

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
    return ShashuClient.builder().apiKey("shashu_sk_count").baseUrl(server.url("/").toString());
  }

  private static ChatCompletionRequest req() {
    return ChatCompletionRequest.builder().model("m").addMessage(ChatMessage.user("hi")).build();
  }

  private static MockResponse ok() {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"id\":\"chatcmpl-mock\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
  }

  private static MockResponse busy() {
    return new MockResponse()
        .setResponseCode(503)
        .setHeader("Retry-After", "0")
        .setHeader("x-should-retry", "true")
        .setBody("{\"error\":{\"message\":\"The gateway is busy.\",\"code\":\"gateway_busy\"}}");
  }

  @Test
  void max_retries_zero_reaches_the_server_once() throws Exception {
    server.enqueue(busy());
    try (ShashuClient client = builder().maxRetries(0).build()) {
      assertThrows(ShashuException.class, () -> client.chat().completions().create(req()));
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void recover_after_one_busy_reaches_the_server_twice() throws Exception {
    server.enqueue(busy());
    server.enqueue(ok());
    try (ShashuClient client = builder().maxRetries(2).build()) {
      client.chat().completions().create(req());
    }
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void give_up_with_default_retries_reaches_the_server_three_times() throws Exception {
    for (int i = 0; i < 8; i++) {
      server.enqueue(busy());
    }
    try (ShashuClient client = builder().maxRetries(2).build()) {
      assertThrows(ShashuException.class, () -> client.chat().completions().create(req()));
    }
    assertEquals(3, server.getRequestCount());
  }
}
