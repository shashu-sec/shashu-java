package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.shashu.sdk.context.WorkflowContext;
import ai.shashu.sdk.tracing.Tracer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * There is exactly ONE place that decides which session id goes on the wire: {@link
 * WorkflowContext#resolveSession}. These tests pin the precedence at that one place AND pin the
 * property that made the rule provable — that {@link WorkflowContext#build} stamps what it is
 * handed and never reads the tracer as a second source.
 *
 * <p>The second read was not cosmetic. With it, a mutation that emptied the caller's resolution
 * left {@code SessionOriginTests .captured_inbound_session_overrides_construction_default} green,
 * because {@code build} silently supplied the captured session itself. The rule was written but not
 * proven.
 */
@DisplayName("Session id: one resolution point")
class SessionResolutionTests {

  private static final Map<String, String> INBOUND =
      Map.of(
          "x-trustgate-trace-id", "flow",
          "x-trustgate-session-id", "captured");

  @Test
  void per_request_is_an_instruction_that_beats_a_captured_inbound() {
    try (Tracer.A2aScope ignored = Tracer.incoming(INBOUND)) {
      assertEquals("per-request", WorkflowContext.resolveSession("per-request", "constructed"));
    }
  }

  @Test
  void captured_inbound_beats_the_construction_default() {
    try (Tracer.A2aScope ignored = Tracer.incoming(INBOUND)) {
      assertEquals("captured", WorkflowContext.resolveSession(null, "constructed"));
    }
  }

  @Test
  void construction_default_stands_when_nothing_was_captured() {
    assertEquals("constructed", WorkflowContext.resolveSession(null, "constructed"));
  }

  @Test
  void a_blank_per_request_session_is_not_an_instruction() {
    try (Tracer.A2aScope ignored = Tracer.incoming(INBOUND)) {
      assertEquals("captured", WorkflowContext.resolveSession("   ", "constructed"));
    }
  }

  @Test
  @DisplayName("build() stamps what the caller resolved and never re-reads the tracer")
  void build_does_not_read_the_tracer_as_a_second_source() {
    try (Tracer.A2aScope ignored = Tracer.incoming(INBOUND)) {
      // Handed an explicit session: that is what is stamped.
      assertEquals(
          "explicit", WorkflowContext.build(null, null, "explicit").get("x-trustgate-session-id"));

      // Handed nothing: the caller resolved to no session, so none is
      // stamped. build() must NOT reach into the tracer to supply one.
      assertFalse(WorkflowContext.build(null, null, null).containsKey("x-trustgate-session-id"));
    }
  }

  @Test
  @DisplayName(
      "the no-arg overload resolves at its own entry, so manual header building still propagates")
  void the_no_arg_overload_still_propagates_a_captured_session() {
    try (Tracer.A2aScope ignored = Tracer.incoming(INBOUND)) {
      assertEquals("captured", WorkflowContext.build().get("x-trustgate-session-id"));
    }
  }
}
