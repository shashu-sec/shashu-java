package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatCompletionResponse;
import ai.shashu.sdk.chat.ChatMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FILE-06: reading the file, memory and route-policy blocks off a decision.
 *
 * <p>Sibling of the Python SDK's {@code tests/test_file_memory_route.py}: the same six wire
 * responses and the same assertions, in Java.
 */
class FileMemoryRouteTest {

  static final String CHAT_200 =
      "\"id\":\"chatcmpl-mock\",\"object\":\"chat.completion\",\"created\":0,"
          + "\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
          + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}]";

  static final String ATTACHMENTS_FLAGGED =
      "{\"total\":2,\"scanned\":1,\"scanned_partial\":0,\"not_inspected\":1,"
          + "\"blocked\":0,\"items\":[{\"index\":0,\"path\":\"messages[0].content[1]\",\"filename\":\"q3.pdf\","
          + "\"declared_type\":\"application/pdf\",\"sniffed_type\":\"application/pdf\",\"sha256\":\"aa11\","
          + "\"bytes\":1834021,\"source\":\"inline\",\"state\":\"scanned\",\"reason\":null,\"findings\":[]},"
          + "{\"index\":1,\"path\":\"messages[0].content[2]\",\"filename\":\"notes.one\","
          + "\"declared_type\":\"application/onenote\",\"sniffed_type\":\"application/onenote\",\"sha256\":\"bb22\","
          + "\"bytes\":92110,\"source\":\"inline\",\"state\":\"not_inspected\",\"reason\":\"unsupported_type\","
          + "\"findings\":[]}]}";

  static final String ATTACHMENTS_BLOCKED =
      "{\"total\":1,\"scanned\":0,\"scanned_partial\":0,\"not_inspected\":0,"
          + "\"blocked\":1,\"items\":[{\"index\":0,\"path\":\"messages[0].content[1]\",\"filename\":\"a.pdf\","
          + "\"declared_type\":\"application/pdf\",\"sniffed_type\":\"application/x-msdownload\",\"sha256\":\"cc33\","
          + "\"bytes\":12,\"source\":\"inline\",\"state\":\"blocked\",\"reason\":\"type_mismatch\","
          + "\"findings\":[{\"detector\":\"file\",\"category\":\"fair_lending.disparate_treatment\","
          + "\"code\":\"file_policy\",\"severity\":\"high\",\"locator\":\"page[1]\",\"snippet_redacted\":\"[redacted]\"}]}]}";

  static final String MEMORY_BLOCKED =
      "{\"coverage\":\"gateway\",\"writes\":1,\"writes_blocked\":1,\"recalls\":0,"
          + "\"recalls_flagged\":0,\"items\":[{\"kind\":\"write\",\"tool\":\"memory.write\",\"store\":\"agent\","
          + "\"scope\":\"session\",\"source\":\"user\",\"enforcement\":\"block\",\"memory_ref\":\"m1\","
          + "\"seen_before\":false,\"state\":\"blocked\",\"reason\":\"memory_sink_injection\"}]}";

  static final String ROUTE_POLICY =
      "{\"route\":\"general-chat\",\"category\":\"phi\","
          + "\"reason\":\"route_not_authorized_for_phi\",\"requested_route\":\"general-chat\"}";

  static String envelope(String decision, String code, String blocks) {
    return "{\"decision\":\""
        + decision
        + "\",\"retryable\":false,\"id\":\"b510c203\","
        + "\"inspection\":{\"input\":\"checked\",\"output\":\"checked\"},\"versions\":{\"contract\":\"2026-10-01\"},"
        + "\"message\":\"a sentence a client must not parse\""
        + (code == null ? "" : ",\"code\":\"" + code + "\"")
        + (blocks == null ? "" : "," + blocks)
        + "}";
  }

  static Map<String, String> headers(String decision, String code, String... extra) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("x-request-id", "b510c203");
    h.put("x-shashu-decision", decision);
    h.put("x-shashu-inspection", "input=checked;output=checked");
    h.put("x-should-retry", "false");
    if (code != null) {
      h.put("x-shashu-code", code);
    }
    for (int i = 0; i < extra.length; i += 2) {
      h.put(extra[i], extra[i + 1]);
    }
    return h;
  }

  static String ok(String decision, String code, String blocks) {
    return "{" + CHAT_200 + ",\"shashu\":" + envelope(decision, code, blocks) + "}";
  }

  static String refused(String code, String message, String blocks) {
    return "{\"error\":{\"message\":\""
        + message
        + "\",\"type\":\"permission_error\",\"param\":null,"
        + "\"code\":\""
        + code
        + "\",\"shashu\":"
        + envelope("refuse", code, blocks)
        + "},\"choices\":[]}";
  }

  record Scenario(int status, String body, Map<String, String> headers) {}

  /** The six wire responses. The unit and e2e tests read the same bytes. */
  static final Map<String, Scenario> SCENARIOS =
      Map.of(
          "no_file", new Scenario(200, ok("allow", null, null), headers("allow", null)),
          "file_flagged",
              new Scenario(
                  200,
                  ok(
                      "not_checked",
                      "attachment_uninspected",
                      "\"attachments\":" + ATTACHMENTS_FLAGGED),
                  headers(
                      "not_checked",
                      "attachment_uninspected",
                      "x-shashu-attachments",
                      "total=2;scanned=1;not_inspected=1;blocked=0")),
          "file_blocked",
              new Scenario(
                  403,
                  refused(
                      "file_policy",
                      "The file a.pdf declared one type and its contents matched another, so it was not forwarded.",
                      "\"attachments\":" + ATTACHMENTS_BLOCKED),
                  headers(
                      "refuse",
                      "file_policy",
                      "x-shashu-attachments",
                      "total=1;scanned=0;not_inspected=0;blocked=1")),
          "memory_blocked",
              new Scenario(
                  403,
                  refused(
                      "tool_policy",
                      "A save through memory.write contained hidden instructions, so it was not stored.",
                      "\"memory\":" + MEMORY_BLOCKED),
                  headers(
                      "refuse",
                      "tool_policy",
                      "x-shashu-memory",
                      "writes=1;blocked=1;recalls=0;flagged=0")),
          "route_blocked",
              new Scenario(
                  403,
                  refused(
                      "route_data_policy",
                      "This request contains patient health information and route general-chat "
                          + "is not approved for it. Use a BAA-covered route.",
                      "\"route_policy\":" + ROUTE_POLICY),
                  headers("refuse", "route_data_policy")),
          // A streaming / header-only reader: no envelope in the body, counts in headers.
          "header_only",
              new Scenario(
                  200,
                  "{" + CHAT_200 + "}",
                  headers(
                      "not_checked",
                      "attachment_uninspected",
                      "x-shashu-attachments",
                      "total=3;scanned=1;not_inspected=1;blocked=0",
                      "x-shashu-memory",
                      "writes=2;blocked=0;recalls=4;flagged=1")));

  static ShashuDecision read(String name) {
    Scenario s = SCENARIOS.get(name);
    ShashuDecision d = Shashu.decisionFrom(s.body(), s.headers());
    assertNotNull(d, name);
    return d;
  }

  @Nested
  @DisplayName("attachments")
  class AttachmentsTests {
    @Test
    void no_file_has_none_and_zero_counts() {
      ShashuDecision d = read("no_file");
      assertNull(d.attachments());
      assertEquals(0, d.filesUnverified());
      assertEquals(0, d.filesBlocked());
    }

    @Test
    void restore_key_is_readable_from_shashu_extra_fields() {
      String attachments =
          "{\"total\":1,\"scanned\":1,\"scanned_partial\":0,\"not_inspected\":0,\"blocked\":0,"
              + "\"items\":[{\"filename\":\"a.pdf\",\"note\":\"This PDF contains placeholders such as <PERSON_1> "
              + "where protected data was hidden from the model. Trustgate AI can't put the real values back "
              + "inside this PDF, so they are listed with this file.\","
              + "\"key\":[{\"placeholder\":\"<email_redacted_1>\",\"value\":\"alice@corp.example\"}]}]}";
      String body = ok("allow", null, "\"attachments\":" + attachments);
      ShashuDecision d = Shashu.decisionFrom(body, Map.of());
      assertNotNull(d.attachments());
      assertEquals("a.pdf", d.attachments().items().get(0).filename());
      com.fasterxml.jackson.databind.JsonNode root;
      try {
        root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
      com.fasterxml.jackson.databind.JsonNode item =
          root.path("shashu").path("attachments").path("items").get(0);
      assertTrue(item.path("note").asText().contains("This PDF contains placeholders"));
      assertEquals("alice@corp.example", item.path("key").get(0).path("value").asText());
    }

    @Test
    void flagged_file_is_read_off_the_body() {
      ShashuDecision d = read("file_flagged");
      Attachments att = d.attachments();
      assertEquals("body", att.carrier());
      assertEquals(
          List.of(2, 1, 0, 1, 0),
          List.of(
              att.total(), att.scanned(), att.scannedPartial(), att.notInspected(), att.blocked()));
      assertEquals(1, d.filesUnverified());
      Attachment first = att.items().get(0);
      Attachment second = att.items().get(1);
      assertEquals("q3.pdf", first.filename());
      assertEquals(AttachmentState.SCANNED, first.state());
      assertNull(first.reason());
      assertEquals(1834021L, first.bytes());
      assertEquals("aa11", first.sha256());
      assertEquals("inline", first.source());
      assertEquals(AttachmentState.NOT_INSPECTED, second.state());
      assertEquals("not_inspected", second.rawState());
      assertEquals("unsupported_type", second.reason());
      assertEquals("application/onenote", second.declaredType());
      assertEquals("application/onenote", second.sniffedType());
    }

    @Test
    void blocked_file_and_its_findings() {
      ShashuDecision d = read("file_blocked");
      assertEquals(1, d.filesBlocked());
      Attachment item = d.attachments().items().get(0);
      assertEquals(AttachmentState.BLOCKED, item.state());
      assertEquals("type_mismatch", item.reason());
      AttachmentFinding f = item.findings().get(0);
      assertEquals("file", f.detector());
      assertEquals("fair_lending.disparate_treatment", f.category());
      assertEquals("file_policy", f.code());
      assertEquals("high", f.severity());
      assertEquals("page[1]", f.locator());
    }

    @Test
    void header_only_falls_back_to_the_counts_and_says_so() {
      ShashuDecision d = read("header_only");
      Attachments att = d.attachments();
      assertEquals("header", att.carrier());
      assertTrue(att.items().isEmpty());
      assertEquals(
          List.of(3, 1, 1, 0),
          List.of(att.total(), att.scanned(), att.notInspected(), att.blocked()));
      assertEquals(1, att.scannedPartial());
      assertEquals(2, d.filesUnverified());
    }

    @Test
    void the_body_wins_over_the_header() {
      Scenario s = SCENARIOS.get("file_flagged");
      Map<String, String> h = new LinkedHashMap<>(s.headers());
      h.put("x-shashu-attachments", "total=9;scanned=9;not_inspected=0;blocked=0");
      ShashuDecision d = Shashu.decisionFrom(s.body(), h);
      assertEquals("body", d.attachments().carrier());
      assertEquals(2, d.attachments().total());
    }

    @Test
    void the_lists_are_read_only() {
      Attachments att = read("file_flagged").attachments();
      assertThrows(UnsupportedOperationException.class, () -> att.items().clear());
    }
  }

  @Nested
  @DisplayName("memory")
  class MemoryTests {
    @Test
    void none_on_a_plain_response() {
      ShashuDecision d = read("no_file");
      assertNull(d.memory());
      assertEquals(0, d.memoryBlocked());
      assertEquals(0, d.memoryRecallsFlagged());
      assertNull(d.memoryCoverage());
    }

    @Test
    void blocked_write_is_read_off_the_body() {
      ShashuDecision d = read("memory_blocked");
      MemoryActivity mem = d.memory();
      assertEquals("body", mem.carrier());
      assertEquals(
          List.of(1, 1, 0, 0),
          List.of(mem.writes(), mem.writesBlocked(), mem.recalls(), mem.recallsFlagged()));
      assertEquals(1, d.memoryBlocked());
      assertEquals("gateway", d.memoryCoverage());
      MemoryItem item = mem.items().get(0);
      assertEquals("write", item.kind());
      assertEquals("memory.write", item.tool());
      assertEquals("agent", item.store());
      assertEquals("session", item.scope());
      assertEquals("user", item.source());
      assertEquals("block", item.enforcement());
      assertEquals("m1", item.memoryRef());
      assertEquals(Boolean.FALSE, item.seenBefore());
      assertEquals("blocked", item.state());
      assertEquals("memory_sink_injection", item.reason());
    }

    @Test
    void header_only_counts() {
      ShashuDecision d = read("header_only");
      MemoryActivity mem = d.memory();
      assertEquals("header", mem.carrier());
      assertTrue(mem.items().isEmpty());
      assertEquals(
          List.of(2, 0, 4, 1),
          List.of(mem.writes(), mem.writesBlocked(), mem.recalls(), mem.recallsFlagged()));
      assertEquals(1, d.memoryRecallsFlagged());
      assertNull(d.memoryCoverage());
    }
  }

  @Nested
  @DisplayName("route policy")
  class RoutePolicyTests {
    @Test
    void absent_on_a_plain_response() {
      assertNull(read("no_file").routePolicy());
    }

    @Test
    void read_off_the_body() {
      RoutePolicy rp = read("route_blocked").routePolicy();
      assertEquals("general-chat", rp.route());
      assertEquals("phi", rp.category());
      assertEquals("route_not_authorized_for_phi", rp.reason());
      assertEquals("general-chat", rp.requestedRoute());
      assertNull(rp.degradedRoute());
    }
  }

  @Nested
  @DisplayName("tolerance")
  class ToleranceTests {
    @Test
    void unknown_code_reason_field_and_state_are_read_not_thrown() {
      String att =
          ATTACHMENTS_FLAGGED
              .replace(
                  "\"state\":\"scanned\"",
                  "\"state\":\"quarantined_zz\",\"made_up_item_field\":[1,2]")
              .replace(
                  "\"reason\":null,\"findings\":[]",
                  "\"reason\":\"made_up_reason_zz\",\"findings\":[{\"detector\":\"zz\",\"category\":\"zz.new\",\"made_up\":true}]")
              .replace("\"total\":2", "\"made_up_block_field\":{\"x\":1},\"total\":2");
      String mem =
          MEMORY_BLOCKED
              .replace("\"gateway\"", "\"made_up_coverage_zz\"")
              .replace(
                  "\"memory_sink_injection\"",
                  "\"made_up_memory_reason_zz\",\"made_up_item_field\":\"x\"")
              .replace("\"kind\":\"write\"", "\"kind\":\"made_up_kind_zz\"");
      String route =
          ROUTE_POLICY
              .replace("route_not_authorized_for_phi", "made_up_route_reason_zz")
              .replace("{", "{\"made_up_field\":7,");
      String env =
          envelope(
              "refuse",
              "made_up_code_zz",
              "\"attachments\":"
                  + att
                  + ",\"memory\":"
                  + mem
                  + ",\"route_policy\":"
                  + route
                  + ",\"made_up_top_level_field\":{\"nested\":[\"x\"]}");
      ShashuDecision d =
          Shashu.decisionFrom(
              "{\"error\":{\"message\":\"m\",\"shashu\":" + env + "}}",
              headers(
                  "refuse",
                  "made_up_code_zz",
                  "x-shashu-attachments",
                  "total=2;scanned=1;not_inspected=1;blocked=0;made_up=4",
                  "x-shashu-memory",
                  "writes=1;blocked=1;made_up=9"));
      assertEquals("made_up_code_zz", d.code());
      Attachment item = d.attachments().items().get(0);
      assertEquals(AttachmentState.UNKNOWN, item.state());
      assertEquals("quarantined_zz", item.rawState());
      assertEquals("made_up_reason_zz", item.reason());
      assertEquals("zz.new", item.findings().get(0).category());
      assertEquals("made_up_memory_reason_zz", d.memory().items().get(0).reason());
      assertEquals("made_up_kind_zz", d.memory().items().get(0).kind());
      assertEquals("made_up_coverage_zz", d.memoryCoverage());
      assertEquals("made_up_route_reason_zz", d.routePolicy().reason());
    }

    @Test
    void wrongly_typed_blocks_are_ignored_not_thrown() {
      ShashuDecision d =
          Shashu.decisionFrom(
              "{\"shashu\":"
                  + envelope(
                      "allow", null, "\"attachments\":\"x\",\"memory\":[1],\"route_policy\":5")
                  + "}",
              null);
      assertNull(d.attachments());
      assertNull(d.memory());
      assertNull(d.routePolicy());
      d =
          Shashu.decisionFrom(
              "{\"shashu\":"
                  + envelope(
                      "allow",
                      null,
                      "\"attachments\":{\"total\":\"two\",\"items\":[\"x\",null,{\"state\":3}]}")
                  + "}",
              null);
      assertEquals(0, d.attachments().total());
      assertEquals(1, d.attachments().items().size());
      assertEquals(AttachmentState.UNKNOWN, d.attachments().items().get(0).state());
      assertNull(d.attachments().items().get(0).rawState());
    }

    @Test
    void garbage_count_headers_are_ignored() {
      ShashuDecision d =
          Shashu.decisionFrom(
              null,
              headers(
                  "allow",
                  null,
                  "x-shashu-attachments",
                  "total=;scanned=x;;=3",
                  "x-shashu-memory",
                  "nonsense"));
      assertEquals("allow", d.decision());
      assertNull(d.attachments());
      assertNull(d.memory());
    }

    @Test
    void attachment_state_from_wire() {
      assertEquals(AttachmentState.SCANNED_PARTIAL, AttachmentState.fromWire("scanned_partial"));
      assertEquals(AttachmentState.UNKNOWN, AttachmentState.fromWire("brand_new"));
      assertEquals(AttachmentState.UNKNOWN, AttachmentState.fromWire(null));
    }
  }

  @Nested
  @DisplayName("exceptions and isBlock")
  class ExceptionTests {
    @Test
    void memoryWriteBlocked_is_a_policy_violation() {
      assertTrue(ShashuPolicyViolation.class.isAssignableFrom(MemoryWriteBlocked.class));
    }

    @Test
    void isBlock_is_unchanged_for_the_new_refusals() {
      for (String name : List.of("file_blocked", "memory_blocked", "route_blocked")) {
        assertFalse(Shashu.isBlock(SCENARIOS.get(name).body()), name);
      }
    }
  }

  // --- wire e2e: the six responses through the real client ------------------

  static <T> T withServer(String name, ServerCall<T> call) throws Exception {
    Scenario s = SCENARIOS.get(name);
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      MockResponse r =
          new MockResponse()
              .setResponseCode(s.status())
              .setHeader("Content-Type", "application/json")
              .setBody(s.body());
      s.headers().forEach(r::setHeader);
      server.enqueue(r);
      try (ShashuClient client =
          ShashuClient.builder()
              .apiKey("sk-trustgate-test")
              .baseUrl(server.url("/").toString())
              .build()) {
        return call.run(client);
      }
    } finally {
      server.shutdown();
    }
  }

  interface ServerCall<T> {
    T run(ShashuClient client) throws Exception;
  }

  static ChatCompletionRequest request() {
    return ChatCompletionRequest.builder()
        .model("gpt-4o")
        .addMessage(ChatMessage.user("hi"))
        .build();
  }

  static ShashuException thrown(String name) throws Exception {
    return withServer(
        name,
        c -> assertThrows(ShashuException.class, () -> c.chat().completions().create(request())));
  }

  @Nested
  @DisplayName("wire e2e")
  class WireE2E {
    @Test
    void no_file() throws Exception {
      ChatCompletionResponse resp =
          withServer("no_file", c -> c.chat().completions().create(request()));
      ShashuDecision d = Shashu.decisionOf(resp);
      assertEquals("allow", d.decision());
      assertNull(d.attachments());
      assertNull(d.memory());
      assertNull(d.routePolicy());
      assertEquals(0, d.filesUnverified() + d.filesBlocked() + d.memoryBlocked());
    }

    @Test
    void file_flagged() throws Exception {
      ChatCompletionResponse resp =
          withServer("file_flagged", c -> c.chat().completions().create(request()));
      ShashuDecision d = Shashu.decisionOf(resp);
      assertEquals("not_checked", d.decision());
      assertEquals("body", d.attachments().carrier());
      assertEquals(1, d.filesUnverified());
      assertEquals(
          List.of(AttachmentState.SCANNED, AttachmentState.NOT_INSPECTED),
          d.attachments().items().stream().map(Attachment::state).toList());
    }

    @Test
    void file_blocked() throws Exception {
      ShashuException ex = thrown("file_blocked");
      assertInstanceOf(ShashuPolicyViolation.class, ex);
      assertFalse(ex instanceof MemoryWriteBlocked);
      ShashuDecision d = Shashu.decisionOf(ex);
      assertEquals(1, d.filesBlocked());
      assertEquals("type_mismatch", d.attachments().items().get(0).reason());
      assertFalse(Shashu.isBlock(ex));
    }

    @Test
    void memory_blocked() throws Exception {
      ShashuException ex = thrown("memory_blocked");
      MemoryWriteBlocked mwb = assertInstanceOf(MemoryWriteBlocked.class, ex);
      assertInstanceOf(ShashuPolicyViolation.class, ex);
      assertEquals(403, ex.statusCode());
      assertEquals("memory_sink_injection", mwb.memory().items().get(0).reason());
      assertFalse(Shashu.isBlock(ex));
    }

    @Test
    void route_data_policy() throws Exception {
      ShashuException ex = thrown("route_blocked");
      assertInstanceOf(ShashuPolicyViolation.class, ex);
      assertFalse(ex instanceof MemoryWriteBlocked);
      assertEquals("general-chat", ex.routePolicy().route());
      assertEquals("phi", ex.routePolicy().category());
      assertFalse(Shashu.isBlock(ex));
    }

    @Test
    void header_only() throws Exception {
      ChatCompletionResponse resp =
          withServer("header_only", c -> c.chat().completions().create(request()));
      ShashuDecision d = Shashu.decisionOf(resp);
      assertEquals("header", d.attachments().carrier());
      assertEquals("header", d.memory().carrier());
      assertEquals(2, d.filesUnverified());
      assertEquals(1, d.memoryRecallsFlagged());
    }
  }
}
