package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatCompletionResponse;
import ai.shashu.sdk.chat.ChatMessage;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * Unknown values the gateway may send tomorrow are read today, never thrown on.
 *
 * <p>Written BEFORE the FILE-06 accessors, against the surface that already existed, so it records
 * whether this SDK tolerated a made-up {@code code}, a made-up reason, a made-up field and a
 * made-up attachment state before anything changed — the question for Java being whether any of
 * those is bound to an enum or a strict Jackson type. It uses no FILE-06 name, so it runs on either
 * side of that change.
 */
class UnknownValuesToleratedTest {

  static final String ENVELOPE =
      "{\"decision\":\"refuse\",\"code\":\"made_up_code_zz\",\"retryable\":false,"
          + "\"id\":\"rec-1\",\"inspection\":{\"input\":\"checked\",\"output\":\"checked\","
          + "\"detectors\":{\"made_up_detector\":\"zz\"}},\"versions\":{\"contract\":\"2099-01-01\"},"
          + "\"message\":\"m\",\"made_up_top_level_field\":{\"nested\":[1,2]},"
          + "\"attachments\":{\"total\":1,\"blocked\":1,\"made_up_count\":3,\"items\":[{\"state\":\"quarantined_zz\","
          + "\"reason\":\"made_up_reason_zz\",\"made_up_field\":true}]},"
          + "\"memory\":{\"coverage\":\"made_up_zz\",\"items\":[{\"kind\":\"zz\",\"reason\":\"made_up_reason_zz\"}]},"
          + "\"route_policy\":{\"route\":\"r\",\"category\":\"zz\",\"reason\":\"made_up_reason_zz\",\"extra\":1}}";

  static final Map<String, String> HEADERS =
      Map.of(
          "x-trustgate-decision", "refuse",
          "x-trustgate-code", "made_up_code_zz",
          "x-trustgate-attachments", "total=1;blocked=1;made_up=2",
          "x-trustgate-memory", "writes=1;made_up=2",
          "x-trustgate-made-up-header", "zz");

  @Test
  void decisionFrom_reads_made_up_values_without_throwing() {
    ShashuDecision d =
        Shashu.decisionFrom(
            "{\"error\":{\"message\":\"m\",\"trustgate\":" + ENVELOPE + "}}", HEADERS);
    assertNotNull(d);
    assertEquals("refuse", d.decision());
    assertEquals("made_up_code_zz", d.code());
  }

  @Test
  void a_made_up_decision_is_still_a_block() {
    String body =
        "{\"trustgate\":" + ENVELOPE.replace("\"refuse\"", "\"made_up_decision_zz\"") + "}";
    assertEquals("made_up_decision_zz", Shashu.decisionFrom(body, null).decision());
    assertEquals(true, Shashu.isBlock(body));
  }

  @Test
  void the_client_throws_the_type_it_always_did() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      MockResponse r =
          new MockResponse()
              .setResponseCode(403)
              .setHeader("Content-Type", "application/json")
              .setBody(
                  "{\"error\":{\"message\":\"m\",\"type\":\"permission_error\",\"code\":\"made_up_code_zz\","
                      + "\"trustgate\":"
                      + ENVELOPE
                      + "}}");
      HEADERS.forEach(r::setHeader);
      server.enqueue(r);
      try (ShashuClient client = client(server)) {
        ShashuException ex =
            assertThrows(
                ShashuException.class, () -> client.chat().completions().create(request()));
        assertInstanceOf(ShashuPolicyViolation.class, ex);
        assertEquals("made_up_code_zz", Shashu.decisionOf(ex).code());
      }
    } finally {
      server.shutdown();
    }
  }

  @Test
  void a_200_with_made_up_values_is_delivered() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setHeader("x-trustgate-decision", "allow")
              .setBody(
                  "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
                      + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                      + "\"finish_reason\":\"stop\"}],\"made_up_body_field\":{\"x\":1},"
                      + "\"trustgate\":"
                      + ENVELOPE.replace("\"refuse\"", "\"allow\"")
                      + "}"));
      try (ShashuClient client = client(server)) {
        ChatCompletionResponse resp = client.chat().completions().create(request());
        assertEquals("ok", resp.choices().get(0).message().content());
        assertEquals("made_up_code_zz", Shashu.decisionOf(resp).code());
      }
    } finally {
      server.shutdown();
    }
  }

  static ShashuClient client(MockWebServer server) {
    return ShashuClient.builder()
        .apiKey("sk-trustgate-test")
        .baseUrl(server.url("/").toString())
        .build();
  }

  static ChatCompletionRequest request() {
    return ChatCompletionRequest.builder()
        .model("gpt-4o")
        .addMessage(ChatMessage.user("hi"))
        .build();
  }
}
