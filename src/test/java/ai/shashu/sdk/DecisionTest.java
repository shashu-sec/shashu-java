package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatCompletionResponse;
import ai.shashu.sdk.chat.ChatMessage;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Decision reading. Absence is today's behaviour. An unrecognised {@code decision} is a block. No
 * code list.
 */
class DecisionTest {

  @ParameterizedTest
  @ValueSource(strings = {"allow", "allow_altered", "not_checked", "block", "refuse", "fail"})
  void decisionFrom_body_surfaces_each_of_the_six(String decision) {
    String body =
        "{\"shashu\":{\"decision\":\"" + decision + "\",\"retryable\":false,\"id\":\"i\"}}";
    ShashuDecision read = Shashu.decisionFrom(body, null);
    assertNotNull(read);
    assertEquals(decision, read.decision());
  }

  @ParameterizedTest
  @ValueSource(strings = {"allow", "allow_altered", "not_checked", "block", "refuse", "fail"})
  void decisionFrom_headers_surfaces_each_of_the_six(String decision) {
    Map<String, String> headers = Map.of("x-shashu-decision", decision);
    ShashuDecision read = Shashu.decisionFrom(null, headers);
    assertNotNull(read);
    assertEquals(decision, read.decision());
  }

  @Test
  void absence_returns_nothing() {
    assertNull(Shashu.decisionFrom(null, null));
    assertNull(Shashu.decisionFrom("{\"id\":\"c1\"}", null));
    assertNull(Shashu.decisionOf(null));
    assertNull(Shashu.decisionOf(emptyResponse()));
  }

  @Test
  void absence_isBlock_is_false() {
    // isBlock(false) alone cannot distinguish absence from a
    // synthesised allow — both are not-a-block. decisionOf must
    // also be empty, or the absence branch is unproven.
    assertFalse(Shashu.isBlock(null));
    assertNull(Shashu.decisionOf(null));
    assertFalse(Shashu.isBlock(new RuntimeException("unrelated")));
    assertNull(Shashu.decisionOf(new RuntimeException("unrelated")));
    assertFalse(Shashu.isBlock(emptyResponse()));
    assertNull(Shashu.decisionOf(emptyResponse()));
  }

  @ParameterizedTest
  @CsvSource({
    "allow, false",
    "allow_altered, false",
    "not_checked, false",
    "block, true",
    "refuse, false",
    "fail, false",
    "brand_new_this_week, true"
  })
  void isBlock_reads_decision_only_and_fails_closed(String decision, boolean blocked) {
    String body = "{\"shashu\":{\"decision\":\"" + decision + "\"}}";
    assertEquals(blocked, Shashu.isBlock(body));
  }

  /**
   * THE BLOCK HELPER MEANS {@code block}, AND NOTHING ELSE.
   *
   * <p>{@code isBlock} was {@code !DELIVERED.contains(decision)}, which truthfully answers a
   * DIFFERENT question — "did I get content at all" — and answering it under this name tells a
   * customer refused on policy that a guardrail fired on their content. A missing API key ({@code
   * refuse} / {@code auth_missing}) is not a content block, and a gateway that broke ({@code fail})
   * decided nothing at all.
   *
   * <p>Fail-closed is unchanged and is asserted beside these: an UNRECOGNISED decision is still a
   * block, because it is coerced to one before the comparison — the same shape as Python's {@code
   * _coerce_decision}. Narrowing to a bare string equality on the raw wire value would lose that,
   * which is mutation M-N below.
   */
  @ParameterizedTest
  @CsvSource({"refuse, false", "fail, false"})
  void refuse_and_fail_are_not_content_blocks(String decision, boolean blocked) {
    String body = "{\"shashu\":{\"decision\":\"" + decision + "\"}}";
    assertEquals(blocked, Shashu.isBlock(body));
  }

  /**
   * An unknown {@code code} must not flip fail-closed. Named mutation M2b (unknown code → false)
   * stayed green elsewhere when no case asserted a block while carrying an unknown code.
   */
  @ParameterizedTest
  @CsvSource({"brand_new_this_week, also_brand_new, true", "allow, also_brand_new, false"})
  void unknown_code_does_not_override_decision_fail_closed(
      String decision, String code, boolean blocked) {
    String body = "{\"shashu\":{\"decision\":\"" + decision + "\",\"code\":\"" + code + "\"}}";
    assertEquals(blocked, Shashu.isBlock(body));
  }

  @Test
  void nested_error_shashu_is_the_error_carrier() {
    String body =
        "{\"error\":{\"type\":\"content_filter\",\"shashu\":{\"decision\":\"block\",\"code\":\"jailbreak\"}}}";
    ShashuDecision read = Shashu.decisionFrom(body, null);
    assertEquals("block", read.decision());
    assertEquals("jailbreak", read.code());
    assertTrue(Shashu.isBlock(body));
  }

  @Test
  void envelope_without_a_readable_decision_fails_closed() {
    String body = "{\"shashu\":{\"retryable\":false}}";
    assertNull(Shashu.decisionFrom(body, null));
    assertTrue(Shashu.isBlock(body));
  }

  @Test
  void contentFilterException_without_decision_fails_closed() {
    ShashuContentFilterException ex = ShashuContentFilterException.fromStreamChunk();
    assertNull(Shashu.decisionOf(ex));
    assertTrue(Shashu.isBlock(ex));
  }

  @Test
  void decisionOf_reads_extension_data_on_a_response() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody(
                  "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
                      + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                      + "\"finish_reason\":\"stop\"}],"
                      + "\"shashu\":{\"decision\":\"allow_altered\",\"code\":\"dlp\"}}"));
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
        ShashuDecision read = Shashu.decisionOf(resp);
        assertEquals("allow_altered", read.decision());
        assertFalse(Shashu.isBlock(resp));
      }
    } finally {
      server.shutdown();
    }
  }

  @Test
  void decisionOf_reads_headers_when_the_body_has_none() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setHeader("x-shashu-decision", "refuse")
              .setBody(
                  "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
                      + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                      + "\"finish_reason\":\"stop\"}]}"));
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
        ShashuDecision read = Shashu.decisionOf(resp);
        assertEquals("refuse", read.decision());
        // The header carrier is read; the decision is a REFUSE, so it
        // is not a content block. This line asserted true until
        // 2026-09-17 and was one of the places the old
        // !DELIVERED.contains semantics were written down.
        assertFalse(Shashu.isBlock(resp));
      }
    } finally {
      server.shutdown();
    }
  }

  private static ChatCompletionResponse emptyResponse() {
    return new ChatCompletionResponse(
        "c1", "chat.completion", 1L, "gpt-4o", java.util.List.of(), null);
  }
}
