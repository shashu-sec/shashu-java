package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatCompletionResponse;
import ai.shashu.sdk.chat.ChatMessage;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The contract version this SDK declares on every request, and what a caller sees because of it.
 * Parity with the Python SDK's {@code DEFAULT_CONTRACT_VERSION} ({@code trustgate/context.py}).
 *
 * <p>The gateway resolves an ABSENT {@code trustgate-version} header to its legacy contract ({@code
 * core/app/contract/version.py}), which flattens every refusal into a 200 and withholds the body
 * envelope. Declaring {@code 2026-09-01} is what puts the memory and inspection blocks in the body
 * and turns a refusal into an error status.
 */
class ContractVersionHeaderTest {

  private static final String SENTENCE =
      "This request looks like an attempt to override the assistant's instructions, so it was not sent.";

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

  // (a) ------------------------------------------------------------------

  @Test
  void default_is_python_value() {
    assertEquals("2026-09-01", ShashuClient.DEFAULT_CONTRACT_VERSION);
  }

  @Test
  void create_sends_the_default_contract_version() throws Exception {
    enqueue(200, FileMemoryRouteTest.ok("allow", null, null));
    try (ShashuClient client = client()) {
      client.chat().completions().create(req());
    }
    RecordedRequest seen = server.takeRequest();
    assertEquals("2026-09-01", seen.getHeader("trustgate-version"));
    assertNull(seen.getHeader("trustgate-block-encoding"));
  }

  @Test
  void create_stream_sends_the_default_contract_version() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: [DONE]\n\n"));
    try (ShashuClient client = client();
        var stream = client.chat().completions().createStream(req())) {
      for (var ignored : stream) {
        /* drain */
      }
    }
    assertEquals("2026-09-01", server.takeRequest().getHeader("trustgate-version"));
  }

  @Test
  void a_pinned_version_is_sent_verbatim() throws Exception {
    enqueue(200, FileMemoryRouteTest.ok("allow", null, null));
    try (ShashuClient client = builder().contractVersion("2026-10-01").build()) {
      client.chat().completions().create(req());
    }
    assertEquals("2026-10-01", server.takeRequest().getHeader("trustgate-version"));
  }

  // (b) ------------------------------------------------------------------

  @Test
  void monitor_flagged_save_is_listed_and_not_blocked() throws Exception {
    String memory =
        "{\"coverage\":\"gateway\",\"writes\":1,\"writes_blocked\":0,\"recalls\":0,"
            + "\"recalls_flagged\":0,\"items\":[{\"kind\":\"write\",\"tool\":\"save_memory\",\"store\":\"agent\","
            + "\"scope\":\"session\",\"source\":\"model\",\"enforcement\":\"monitor\",\"memory_ref\":\"m1\","
            + "\"seen_before\":false,\"state\":\"flagged\",\"reason\":\"memory_sink_injection\"}]}";
    enqueue(
        200,
        FileMemoryRouteTest.ok("allow", null, "\"memory\":" + memory),
        "x-trustgate-memory",
        "writes=1;blocked=0;recalls=0;flagged=0");
    try (ShashuClient client = client()) {
      ChatCompletionResponse resp = client.chat().completions().create(req());
      ShashuDecision d = Shashu.decisionOf(resp);
      assertNotNull(d);
      assertNotNull(d.memory());
      assertEquals("body", d.memory().carrier());
      assertEquals(1, d.memory().writes());
      assertEquals(0, d.memoryBlocked());
      assertEquals(1, d.memory().items().size());
      MemoryItem item = d.memory().items().get(0);
      assertEquals("write", item.kind());
      assertEquals("save_memory", item.tool());
      assertEquals("agent", item.store());
      assertEquals("flagged", item.state());
      assertEquals("memory_sink_injection", item.reason());
      assertFalse(Shashu.isBlock(resp));
    }
  }

  // (c) ------------------------------------------------------------------

  @Test
  void block_mode_stripped_save_is_a_delivered_reply_with_one_blocked() throws Exception {
    enqueue(
        200,
        FileMemoryRouteTest.ok("allow", null, "\"memory\":" + FileMemoryRouteTest.MEMORY_BLOCKED),
        "x-trustgate-memory",
        "writes=1;blocked=1;recalls=0;flagged=0");
    try (ShashuClient client = client()) {
      ChatCompletionResponse resp = client.chat().completions().create(req());
      assertEquals("ok", resp.choices().get(0).message().content());
      ShashuDecision d = Shashu.decisionOf(resp);
      assertNotNull(d);
      assertEquals(1, d.memoryBlocked());
      assertEquals("blocked", d.memory().items().get(0).state());
      assertFalse(Shashu.isBlock(resp));
    }
  }

  // (d) ------------------------------------------------------------------

  @Test
  void content_block_400_raises_content_filter_with_the_sentence() throws Exception {
    enqueue(400, explicitBlock());
    try (ShashuClient client = client()) {
      ShashuContentFilterException ex =
          assertThrows(
              ShashuContentFilterException.class, () -> client.chat().completions().create(req()));
      assertEquals(400, ex.statusCode());
      assertEquals(SENTENCE, ex.getMessage());
      assertEquals("jailbreak", ex.code());
    }
  }

  @Test
  void memory_refusal_403_raises_memory_write_blocked_with_the_sentence() throws Exception {
    var s = FileMemoryRouteTest.SCENARIOS.get("memory_blocked");
    enqueue(s.status(), s.body());
    try (ShashuClient client = client()) {
      MemoryWriteBlocked ex =
          assertThrows(MemoryWriteBlocked.class, () -> client.chat().completions().create(req()));
      assertEquals(403, ex.statusCode());
      assertEquals(
          "A save through memory.write contained hidden instructions, so it was not stored.",
          ex.getMessage());
      assertEquals(1, ex.memory().writesBlocked());
    }
  }

  @Test
  void policy_refusal_403_without_memory_raises_policy_violation() throws Exception {
    var s = FileMemoryRouteTest.SCENARIOS.get("route_blocked");
    enqueue(s.status(), s.body());
    try (ShashuClient client = client()) {
      ShashuPolicyViolation ex =
          assertThrows(
              ShashuPolicyViolation.class, () -> client.chat().completions().create(req()));
      assertFalse(ex instanceof MemoryWriteBlocked);
      assertEquals("permission_error", ex.errorType());
    }
  }

  @Test
  void fail_closed_503_carries_the_sentence_not_the_raw_body() throws Exception {
    String sentence = "The safety check did not finish in time, so the request was not sent.";
    String body =
        "{\"error\":{\"message\":\""
            + sentence
            + "\",\"type\":\"api_error\",\"param\":null,"
            + "\"code\":\"inspector_timeout\",\"trustgate\":{\"decision\":\"fail\",\"retryable\":false,"
            + "\"id\":\"b510c203\",\"inspection\":{\"input\":\"incomplete\",\"output\":\"skipped\"},"
            + "\"versions\":{\"contract\":\"2026-09-01\"},\"message\":\""
            + sentence
            + "\","
            + "\"code\":\"inspector_timeout\"}},\"choices\":[]}";
    enqueue(503, body);
    try (ShashuClient client = builder().maxRetries(0).build()) {
      ShashuException ex =
          assertThrows(ShashuException.class, () -> client.chat().completions().create(req()));
      assertEquals(503, ex.statusCode());
      assertEquals(sentence, ex.getMessage());
      assertEquals(body, ex.responseBody());
    }
  }

  @Test
  void a_503_without_an_envelope_keeps_the_snippet_message() {
    ShashuException ex = ShashuException.fromResponse(503, "upstream went away");
    assertEquals("Shashu request failed with HTTP 503: upstream went away", ex.getMessage());
  }

  // (e) ------------------------------------------------------------------

  @Test
  void streaming_refusal_before_the_stream_raises_content_filter() throws Exception {
    enqueue(400, explicitBlock());
    try (ShashuClient client = client()) {
      ShashuContentFilterException ex =
          assertThrows(
              ShashuContentFilterException.class,
              () -> {
                try (var stream = client.chat().completions().createStream(req())) {
                  for (var ignored : stream) {
                    /* drain */
                  }
                }
              });
      assertEquals(400, ex.statusCode());
      assertEquals(SENTENCE, ex.getMessage());
    }
  }

  // (f) ------------------------------------------------------------------

  @Test
  void opt_out_sends_no_header_and_legacy_200_still_throws() throws Exception {
    enqueue(200, CompatibleBlockTestFixtures.BLOCKED_200);
    try (ShashuClient client = builder().contractVersion(null).build()) {
      ShashuContentFilterException ex =
          assertThrows(
              ShashuContentFilterException.class, () -> client.chat().completions().create(req()));
      assertEquals(200, ex.statusCode());
      assertEquals("content_filter", ex.errorType());
    }
    RecordedRequest seen = server.takeRequest();
    assertNull(seen.getHeader("trustgate-version"));
    assertNull(seen.getHeader("trustgate-block-encoding"));
  }

  @Test
  void blank_opts_out_too() throws Exception {
    enqueue(200, FileMemoryRouteTest.ok("allow", null, null));
    try (ShashuClient client = builder().contractVersion("  ").build()) {
      client.chat().completions().create(req());
    }
    assertNull(server.takeRequest().getHeader("trustgate-version"));
  }

  @Test
  void the_legacy_options_constructor_still_defaults_the_version() {
    ShashuClientOptions legacy =
        new ShashuClientOptions("k", "http://gw", 1000, 0, null, "ua", false, null, null);
    assertEquals("2026-09-01", legacy.contractVersion());
    assertInstanceOf(String.class, legacy.contractVersion());
  }

  // ---------------------------------------------------------------------

  private static String explicitBlock() {
    return "{\"error\":{\"message\":\""
        + SENTENCE
        + "\",\"type\":\"content_filter\",\"param\":null,"
        + "\"code\":\"jailbreak\",\"trustgate\":{\"decision\":\"block\",\"retryable\":false,"
        + "\"id\":\"b510c203\",\"inspection\":{\"input\":\"checked\",\"output\":\"skipped\"},"
        + "\"versions\":{\"contract\":\"2026-09-01\"},\"message\":\""
        + SENTENCE
        + "\","
        + "\"code\":\"jailbreak\"}},\"choices\":[]}";
  }

  private ShashuClient.Builder builder() {
    return ShashuClient.builder().apiKey("sk-trustgate-test").baseUrl(server.url("/").toString());
  }

  private ShashuClient client() {
    return builder().build();
  }

  private static ChatCompletionRequest req() {
    return ChatCompletionRequest.builder()
        .model("gpt-4o")
        .addMessage(ChatMessage.user("hi"))
        .build();
  }

  private void enqueue(int status, String body, String... headers) {
    MockResponse r =
        new MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(body);
    for (int i = 0; i < headers.length; i += 2) {
      r.setHeader(headers[i], headers[i + 1]);
    }
    server.enqueue(r);
  }

  /** Kept here so this file does not reach into another test's private constant. */
  static final class CompatibleBlockTestFixtures {
    static final String BLOCKED_200 =
        "{\"id\":\"chatcmpl-deadbeefcafe4321aabbccdd\",\"object\":\"chat.completion\","
            + "\"created\":1750000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"I cannot fulfill this request "
            + "because it violates the organization's security policy.\"},"
            + "\"finish_reason\":\"content_filter\"}],"
            + "\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}";

    private CompatibleBlockTestFixtures() {}
  }
}
