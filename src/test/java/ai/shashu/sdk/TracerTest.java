package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.*;

import ai.shashu.sdk.tracing.Tracer;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the hierarchical span tracer: trace ids stable across nested spans, parent/child IDs
 * link correctly, stack pops correctly on close.
 */
class TracerTest {

  @Test
  void tracer_is_inactive_outside_any_span() {
    assertFalse(Tracer.isActive());
    assertNull(Tracer.traceId());
    assertNull(Tracer.currentSpanId());
    assertNull(Tracer.parentSpanId());
  }

  @Test
  void single_span_sets_trace_and_span_ids_clears_on_close() {
    assertFalse(Tracer.isActive());

    try (Tracer.SpanHandle s = Tracer.span("retrieval")) {
      assertTrue(Tracer.isActive());
      assertNotNull(Tracer.traceId());
      assertNotNull(Tracer.currentSpanId());
      assertNull(Tracer.parentSpanId()); // root span has no parent
      assertNotEquals(Tracer.traceId(), Tracer.currentSpanId());
    }

    assertFalse(Tracer.isActive());
    assertNull(Tracer.traceId());
    assertNull(Tracer.currentSpanId());
  }

  @Test
  void nested_spans_share_trace_id_and_link_via_parent_id() {
    try (Tracer.SpanHandle outer = Tracer.span("outer")) {
      String outerTrace = Tracer.traceId();
      String outerSpan = Tracer.currentSpanId();
      assertNotNull(outerTrace);
      assertNotNull(outerSpan);

      try (Tracer.SpanHandle inner = Tracer.span("inner")) {
        assertEquals(outerTrace, Tracer.traceId());
        assertNotEquals(outerSpan, Tracer.currentSpanId());
        assertEquals(outerSpan, Tracer.parentSpanId());
      }

      // Outer still active after inner closes.
      assertTrue(Tracer.isActive());
      assertEquals(outerTrace, Tracer.traceId());
      assertEquals(outerSpan, Tracer.currentSpanId());
    }
  }

  @Test
  void span_with_attributes_does_not_throw() {
    try (Tracer.SpanHandle s = Tracer.span("with-attrs", Map.of("model", "gpt-4o"))) {
      assertTrue(Tracer.isActive());
    }
    assertFalse(Tracer.isActive());
  }

  @Test
  void flush_with_no_buffered_spans_is_noop() {
    // After all earlier spans flushed (or none opened), flush should be safe.
    Tracer.flush();
  }

  @Test
  void thread_local_state_does_not_leak_across_threads() throws Exception {
    // Each thread should have its own span stack — opening a span in
    // one thread must not affect another. Critical for server apps.
    Thread other =
        new Thread(
            () -> {
              assertFalse(
                  Tracer.isActive(), "Other thread must not see spans opened on the test thread");
            });

    try (Tracer.SpanHandle s = Tracer.span("on-test-thread")) {
      assertTrue(Tracer.isActive());
      other.start();
      other.join();
    }
  }
}
