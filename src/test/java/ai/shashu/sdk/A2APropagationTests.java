package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.context.WorkflowContext;
import ai.shashu.sdk.tracing.Tracer;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

/**
 * WP-A2A-4 / P4-d — A2A inbound-capture + outbound propagation (ADR-007 §0, §4.1).
 *
 * <p>Mirrors the corrected Python reference (trustgate 0.9.1), the Node SDK (1.7.1), and the .NET
 * SDK ({@code A2APropagationTests.cs}): the gateway call FORWARDS the captured upstream caller
 * (never self), and the invoking side stamps caller = self. Capture is exercised inside {@code
 * Tracer.incoming(...)} scopes so the per-thread ThreadLocal state is deterministic per-case (no
 * leakage between tests on a reused JUnit worker thread).
 */
class A2APropagationTests {

  /** Build a header map from alternating key/value varargs. */
  private static Map<String, String> h(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  // --------------------------------------------------------------------- //
  // Inbound capture
  // --------------------------------------------------------------------- //
  @Test
  void capture_populates_store_including_inbound_caller() {
    try (Tracer.A2aScope s =
        Tracer.incoming(
            h(
                "x-trustgate-trace-id",
                "flow-1",
                "x-trustgate-session-id",
                "sess-1",
                "x-trustgate-hop-depth",
                "2",
                "x-trustgate-caller-agent-id",
                "agent-A"))) {
      assertTrue(Tracer.isA2aHop());
      assertEquals("flow-1", Tracer.traceId());
      assertEquals("sess-1", Tracer.sessionId());
      assertEquals(2, Tracer.inboundHopDepth());
      assertEquals(3, Tracer.outboundHopDepth());
      assertEquals("agent-A", Tracer.inboundCallerAgentId()); // §4.1: the upstream invoker
    }
    assertFalse(Tracer.isA2aHop());
  }

  @Test
  void no_inbound_trace_is_origin_returns_false() {
    try (Tracer.A2aScope s = Tracer.incoming(h())) {
      assertFalse(Tracer.captureIncoming(h("x-trustgate-session-id", "s")));
      assertFalse(Tracer.captureIncoming(h()));
      assertFalse(Tracer.isA2aHop());
    }
  }

  @Test
  void capture_is_case_insensitive() {
    try (Tracer.A2aScope s =
        Tracer.incoming(
            h(
                "X-Trustgate-Trace-Id",
                "T1",
                "X-Trustgate-Session-Id",
                "S1",
                "X-Trustgate-Caller-Agent-Id",
                "A1"))) {
      assertEquals("T1", Tracer.traceId());
      assertEquals("S1", Tracer.sessionId());
      assertEquals("A1", Tracer.inboundCallerAgentId());
    }
  }

  @Test
  void garbage_hop_depth_defaults_to_zero() {
    // Advisory header: non-numeric/negative → inbound 0, outbound 1 (never throws).
    try (Tracer.A2aScope s =
        Tracer.incoming(h("x-trustgate-trace-id", "T2", "x-trustgate-hop-depth", "not-a-number"))) {
      assertEquals(0, Tracer.inboundHopDepth());
      assertEquals(1, Tracer.outboundHopDepth());
    }
  }

  @Test
  void no_hop_header_defaults_outbound_to_one() {
    try (Tracer.A2aScope s = Tracer.incoming(h("x-trustgate-trace-id", "f"))) {
      assertEquals(0, Tracer.inboundHopDepth());
      assertEquals(1, Tracer.outboundHopDepth());
    }
  }

  @Test
  void no_caller_leaves_inbound_caller_null() {
    try (Tracer.A2aScope s =
        Tracer.incoming(h("x-trustgate-trace-id", "f", "x-trustgate-hop-depth", "1"))) {
      assertNull(Tracer.inboundCallerAgentId());
    }
  }

  // --------------------------------------------------------------------- //
  // Outbound gateway call (WorkflowContext.build): propagate, FORWARD caller
  // --------------------------------------------------------------------- //
  @Test
  void gateway_propagates_captured_trace_with_session_and_hop() {
    try (Tracer.A2aScope s =
        Tracer.incoming(
            h(
                "x-trustgate-trace-id",
                "flow-xyz",
                "x-trustgate-session-id",
                "sess-1",
                "x-trustgate-hop-depth",
                "2",
                "x-trustgate-caller-agent-id",
                "agent-A"))) {
      Map<String, String> hdr = WorkflowContext.build();
      assertEquals("flow-xyz", hdr.get("x-trustgate-trace-id")); // propagated, not a fresh UUID
      assertEquals("sess-1", hdr.get("x-trustgate-session-id"));
      assertEquals("3", hdr.get("x-trustgate-hop-depth")); // inbound 2 + 1
    }
  }

  @Test
  void gateway_forwards_inbound_caller_not_self() {
    try (Tracer.A2aScope s =
        Tracer.incoming(
            h(
                "x-trustgate-trace-id",
                "F",
                "x-trustgate-caller-agent-id",
                "agent-A",
                "x-trustgate-hop-depth",
                "1"))) {
      assertEquals("agent-A", WorkflowContext.build().get("x-trustgate-caller-agent-id"));
    }
  }

  @Test
  void origin_emits_no_lineage_headers() {
    try (Tracer.A2aScope s = Tracer.incoming(h())) { // explicit origin scope
      Map<String, String> hdr = WorkflowContext.build();
      assertFalse(hdr.containsKey("x-trustgate-session-id"));
      assertFalse(hdr.containsKey("x-trustgate-caller-agent-id"));
      assertFalse(hdr.containsKey("x-trustgate-hop-depth"));
    }
  }

  @Test
  void caller_omitted_when_no_inbound_caller() {
    try (Tracer.A2aScope s =
        Tracer.incoming(h("x-trustgate-trace-id", "f", "x-trustgate-hop-depth", "1"))) {
      Map<String, String> hdr = WorkflowContext.build();
      assertEquals("f", hdr.get("x-trustgate-trace-id"));
      assertEquals("2", hdr.get("x-trustgate-hop-depth"));
      assertFalse(hdr.containsKey("x-trustgate-caller-agent-id"));
    }
  }

  @Test
  void session_omitted_when_not_propagated() {
    try (Tracer.A2aScope s =
        Tracer.incoming(h("x-trustgate-trace-id", "f", "x-trustgate-caller-agent-id", "A"))) {
      Map<String, String> hdr = WorkflowContext.build();
      assertFalse(hdr.containsKey("x-trustgate-session-id"));
      assertEquals("A", hdr.get("x-trustgate-caller-agent-id")); // forwarded invoker
      assertEquals("1", hdr.get("x-trustgate-hop-depth"));
    }
  }

  @Test
  void span_inside_capture_inherits_trace_and_forwards_lineage() {
    try (Tracer.A2aScope s =
            Tracer.incoming(
                h(
                    "x-trustgate-trace-id",
                    "FLOW",
                    "x-trustgate-session-id",
                    "S",
                    "x-trustgate-hop-depth",
                    "1",
                    "x-trustgate-caller-agent-id",
                    "A"));
        Tracer.SpanHandle span = Tracer.span("work")) {
      Map<String, String> hdr = WorkflowContext.build();
      assertEquals("FLOW", hdr.get("x-trustgate-trace-id")); // span inherits captured trace
      assertTrue(hdr.containsKey("x-trustgate-span-id"));
      assertEquals("S", hdr.get("x-trustgate-session-id"));
      assertEquals("A", hdr.get("x-trustgate-caller-agent-id")); // forwarded invoker, not self
      assertEquals("2", hdr.get("x-trustgate-hop-depth"));
    }
  }

  // --------------------------------------------------------------------- //
  // Invoking side — buildInvocationHeaders stamps caller = SELF (§4.1)
  // --------------------------------------------------------------------- //
  @Test
  void invocation_at_origin_stamps_caller_self() {
    try (Tracer.A2aScope s = Tracer.incoming(h())) { // clean origin: outbound hop = 1, no session
      Map<String, String> hdr = WorkflowContext.buildInvocationHeaders("agent-A");
      assertEquals("agent-A", hdr.get("x-trustgate-caller-agent-id"));
      assertTrue(hdr.containsKey("x-trustgate-trace-id"));
      assertEquals("1", hdr.get("x-trustgate-hop-depth"));
      assertFalse(hdr.containsKey("x-trustgate-session-id"));
    }
  }

  @Test
  void invocation_midflow_propagates_served_flow() {
    try (Tracer.A2aScope s =
        Tracer.incoming(
            h(
                "x-trustgate-trace-id",
                "F",
                "x-trustgate-session-id",
                "S",
                "x-trustgate-hop-depth",
                "1",
                "x-trustgate-caller-agent-id",
                "agent-A"))) {
      Map<String, String> hdr = WorkflowContext.buildInvocationHeaders("agent-B");
      assertEquals("F", hdr.get("x-trustgate-trace-id")); // served flow propagated
      assertEquals(
          "agent-B", hdr.get("x-trustgate-caller-agent-id")); // B stamps ITSELF as invoker of C
      assertEquals("S", hdr.get("x-trustgate-session-id"));
      assertEquals("2", hdr.get("x-trustgate-hop-depth"));
    }
  }

  @Test
  void invocation_honors_explicit_overrides() {
    Map<String, String> hdr = WorkflowContext.buildInvocationHeaders("me", "T", "S", 5);
    assertEquals("T", hdr.get("x-trustgate-trace-id"));
    assertEquals("me", hdr.get("x-trustgate-caller-agent-id"));
    assertEquals("5", hdr.get("x-trustgate-hop-depth"));
    assertEquals("S", hdr.get("x-trustgate-session-id"));
  }

  @Test
  void invocation_throws_when_caller_empty() {
    assertThrows(IllegalArgumentException.class, () -> WorkflowContext.buildInvocationHeaders(""));
    assertThrows(
        IllegalArgumentException.class, () -> WorkflowContext.buildInvocationHeaders(null));
  }

  @Test
  void a2a_caller_chain_end_to_end() {
    try (Tracer.A2aScope origin = Tracer.incoming(h())) { // clean origin
      // A (origin) invokes B: A stamps caller = A.
      Map<String, String> aToB =
          WorkflowContext.buildInvocationHeaders("agent-A", "FLOW", null, null);
      assertEquals("agent-A", aToB.get("x-trustgate-caller-agent-id"));

      // B serves A's call: B's gateway call FORWARDS caller = A (the A→B edge).
      try (Tracer.A2aScope bServes = Tracer.incoming(aToB)) {
        Map<String, String> bGw = WorkflowContext.build();
        assertEquals("FLOW", bGw.get("x-trustgate-trace-id"));
        assertEquals("agent-A", bGw.get("x-trustgate-caller-agent-id"));
        assertEquals("2", bGw.get("x-trustgate-hop-depth")); // inbound 1 + 1

        // B invokes C: B stamps ITSELF; C will then forward B (the B→C edge).
        Map<String, String> bToC = WorkflowContext.buildInvocationHeaders("agent-B");
        assertEquals("agent-B", bToC.get("x-trustgate-caller-agent-id"));
        assertEquals("FLOW", bToC.get("x-trustgate-trace-id")); // same flow

        try (Tracer.A2aScope cServes = Tracer.incoming(bToC)) {
          Map<String, String> cGw = WorkflowContext.build();
          assertEquals("agent-B", cGw.get("x-trustgate-caller-agent-id")); // C forwards B
          assertEquals("3", cGw.get("x-trustgate-hop-depth"));
        }
      }
    }
  }

  // --------------------------------------------------------------------- //
  // incoming() scopes the capture
  // --------------------------------------------------------------------- //
  @Test
  void incoming_scopes_and_restores() {
    try (Tracer.A2aScope s =
        Tracer.incoming(
            h(
                "x-trustgate-trace-id",
                "T",
                "x-trustgate-session-id",
                "S",
                "x-trustgate-caller-agent-id",
                "A"))) {
      assertTrue(Tracer.isA2aHop());
      assertEquals("T", WorkflowContext.build().get("x-trustgate-trace-id"));
    }
    assertFalse(Tracer.isA2aHop()); // restored → origin, no lineage leakage
  }

  @Test
  void nested_incoming_scopes_restore_outer_flow() {
    try (Tracer.A2aScope outer =
        Tracer.incoming(h("x-trustgate-trace-id", "OUTER", "x-trustgate-hop-depth", "1"))) {
      assertEquals("OUTER", Tracer.traceId());
      try (Tracer.A2aScope inner =
          Tracer.incoming(h("x-trustgate-trace-id", "INNER", "x-trustgate-hop-depth", "5"))) {
        assertEquals("INNER", Tracer.traceId());
        assertEquals(6, Tracer.outboundHopDepth());
      }
      // Inner scope closed → outer flow restored, not cleared.
      assertEquals("OUTER", Tracer.traceId());
      assertEquals(2, Tracer.outboundHopDepth());
    }
    assertFalse(Tracer.isA2aHop());
  }

  // --------------------------------------------------------------------- //
  // Client self-id cache + invocation helper
  // --------------------------------------------------------------------- //
  @Test
  void client_invocation_uses_seeded_self_id() {
    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl("https://gateway.test.invalid/")
            .agentId("seed-self")
            .build()) {
      assertEquals("seed-self", client.agentId());
      try (Tracer.A2aScope s =
          Tracer.incoming(h("x-trustgate-trace-id", "F", "x-trustgate-hop-depth", "1"))) {
        Map<String, String> hdr = client.buildInvocationHeaders();
        assertEquals(
            "seed-self", hdr.get("x-trustgate-caller-agent-id")); // caller = self (invoking side)
        assertEquals("F", hdr.get("x-trustgate-trace-id"));
        assertEquals("2", hdr.get("x-trustgate-hop-depth"));
      }
    }
  }

  @Test
  void client_invocation_throws_when_self_unknown() {
    // No agentId option and no gateway call yet → self-id unknown.
    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl("https://gateway.test.invalid/")
            .build()) {
      assertThrows(IllegalStateException.class, client::buildInvocationHeaders);
    }
  }

  // --------------------------------------------------------------------- //
  // Wire-level: gateway call forwards caller + caches self-id from response
  // --------------------------------------------------------------------- //
  @Test
  void gateway_call_forwards_caller_and_caches_self_id_from_response() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setHeader("x-trustgate-agent-id", "gw-reported")
              .setBody(okBody()));

      try (ShashuClient client =
          ShashuClient.builder()
              .apiKey("sk-trustgate-test")
              .baseUrl(server.url("/").toString())
              .agentId("explicit-seed")
              .build()) {

        try (Tracer.A2aScope s =
            Tracer.incoming(
                h(
                    "x-trustgate-trace-id",
                    "flow-A",
                    "x-trustgate-hop-depth",
                    "1",
                    "x-trustgate-caller-agent-id",
                    "upstream-A"))) {
          client
              .chat()
              .completions()
              .create(
                  ChatCompletionRequest.builder()
                      .model("gpt-4o")
                      .addMessage(ChatMessage.user("hi"))
                      .build());
        }

        assertEquals("gw-reported", client.agentId()); // response refreshed self-id
      }

      RecordedRequest req = server.takeRequest();
      assertEquals("flow-A", req.getHeader("x-trustgate-trace-id")); // propagated
      assertEquals(
          "upstream-A", req.getHeader("x-trustgate-caller-agent-id")); // FORWARDED, not self
      assertEquals("2", req.getHeader("x-trustgate-hop-depth")); // inbound 1 + 1
    } finally {
      server.shutdown();
    }
  }

  @Test
  void self_id_cached_even_on_blocked_response() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      server.enqueue(
          new MockResponse()
              .setResponseCode(403)
              .setHeader("x-trustgate-agent-id", "gw-reported")
              .setBody("{\"error\":{\"message\":\"blocked\",\"type\":\"invalid_request_error\"}}"));

      try (ShashuClient client =
          ShashuClient.builder()
              .apiKey("sk-trustgate-test")
              .baseUrl(server.url("/").toString())
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

        assertEquals("gw-reported", client.agentId()); // seeded before the throw
      }
    } finally {
      server.shutdown();
    }
  }

  @Test
  void origin_client_call_emits_no_lineage_even_with_self_seeded() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody(okBody()));

      try (ShashuClient client =
          ShashuClient.builder()
              .apiKey("sk-trustgate-test")
              .baseUrl(server.url("/").toString())
              .agentId("seeded")
              .build()) {

        try (Tracer.A2aScope s = Tracer.incoming(h())) { // origin
          client
              .chat()
              .completions()
              .create(
                  ChatCompletionRequest.builder()
                      .model("gpt-4o")
                      .addMessage(ChatMessage.user("hi"))
                      .build());
        }
      }

      RecordedRequest req = server.takeRequest();
      // Origin: no caller even when self is seeded; no hop either.
      // Session IS sent — one client is one session (Job 2). The previous
      // assertion `assertNull(x-trustgate-session-id)` pinned the defect
      // (same template as Node a2a.test.ts:335 and .NET A2APropagationTests L382).
      assertNull(req.getHeader("x-trustgate-caller-agent-id"));
      assertNotNull(req.getHeader("x-trustgate-session-id"));
      assertNull(req.getHeader("x-trustgate-hop-depth"));
    } finally {
      server.shutdown();
    }
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
