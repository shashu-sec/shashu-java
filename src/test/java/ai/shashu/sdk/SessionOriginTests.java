package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.tracing.Tracer;
import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One client is one session. A captured inbound session overrides the construction-time default. A
 * per-request sessionId is an instruction that wins, and applies to that request only.
 */
class SessionOriginTests {

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
  void origin_sends_a_minted_session_and_reuses_it() throws Exception {
    enqueueOk(2);
    try (ShashuClient client = client(null)) {
      call(client);
      call(client);
      String first = takeSession();
      String second = takeSession();
      assertNotNull(first);
      assertFalse(first.isBlank());
      assertEquals(first, second);
      assertEquals(client.sessionId(), first);
    }
  }

  @Test
  void two_clients_are_two_sessions() throws Exception {
    enqueueOk(2);
    try (ShashuClient a = client(null);
        ShashuClient b = client(null)) {
      call(a);
      call(b);
      assertNotEquals(takeSession(), takeSession());
    }
  }

  @Test
  void construction_session_is_a_default() throws Exception {
    enqueueOk(1);
    try (ShashuClient client = client("constructed")) {
      call(client);
      assertEquals("constructed", takeSession());
    }
  }

  @Test
  void per_request_session_is_an_instruction_that_wins_and_does_not_stick() throws Exception {
    enqueueOk(2);
    try (ShashuClient client = client("constructed")) {
      client.chat().completions().create(req(), "per-request");
      call(client);
      assertEquals("per-request", takeSession());
      assertEquals("constructed", takeSession());
    }
  }

  @Test
  void captured_inbound_session_overrides_construction_default() throws Exception {
    enqueueOk(1);
    try (ShashuClient client = client("constructed");
        Tracer.A2aScope ignored =
            Tracer.incoming(
                Map.of(
                    "x-shashu-trace-id", "flow",
                    "x-shashu-session-id", "captured"))) {
      call(client);
      assertEquals("captured", takeSession());
    }
  }

  @Test
  void per_request_wins_over_captured_inbound() throws Exception {
    enqueueOk(1);
    try (ShashuClient client = client("constructed");
        Tracer.A2aScope ignored =
            Tracer.incoming(
                Map.of(
                    "x-shashu-trace-id", "flow",
                    "x-shashu-session-id", "captured"))) {
      client.chat().completions().create(req(), "per-request");
      assertEquals("per-request", takeSession());
    }
  }

  private ShashuClient client(String sessionId) {
    var b = ShashuClient.builder().apiKey("sk-trustgate-test").baseUrl(server.url("/").toString());
    if (sessionId != null) {
      b.sessionId(sessionId);
    }
    return b.build();
  }

  private void call(ShashuClient client) {
    client.chat().completions().create(req());
  }

  private static ChatCompletionRequest req() {
    return ChatCompletionRequest.builder()
        .model("gpt-4o")
        .addMessage(ChatMessage.user("hi"))
        .build();
  }

  private void enqueueOk(int n) {
    for (int i = 0; i < n; i++) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody(okBody()));
    }
  }

  private String takeSession() throws InterruptedException {
    RecordedRequest req = server.takeRequest();
    return req.getHeader("x-shashu-session-id");
  }

  private static String okBody() {
    return "{"
        + "\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
        + "\"finish_reason\":\"stop\"}]}";
  }
}
