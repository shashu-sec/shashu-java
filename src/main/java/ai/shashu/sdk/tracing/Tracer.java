package ai.shashu.sdk.tracing;

import ai.shashu.sdk.internal.CredentialResolver;
import ai.shashu.sdk.internal.ShashuConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Shashu's own hierarchical-span tracer. Symmetric with the Python SDK's {@code tracer.span()}
 * contract — NOT W3C OpenTelemetry. Spans are buffered in-process and flushed to the gateway's
 * {@code POST /api/telemetry/spans} endpoint either on demand ({@link #flush()}) or on JVM
 * shutdown.
 *
 * <p>What gets emitted on the wire (set by {@link ai.shashu.sdk.context.WorkflowContext
 * WorkflowContext}):
 *
 * <ul>
 *   <li>{@code x-shashu-trace-id} — active span's trace id
 *   <li>{@code x-shashu-span-id} — active span's span id
 *   <li>{@code x-shashu-parent-id} — active span's parent (empty for root)
 * </ul>
 *
 * <p>Use the tracer to instrument workflow steps:
 *
 * <pre>{@code
 * try (Tracer.SpanHandle outer = Tracer.span("retrieval")) {
 *     client.chat().completions().create(retrievalRequest);
 *
 *     try (Tracer.SpanHandle inner = Tracer.span("answer")) {
 *         client.chat().completions().create(answerRequest);
 *     }  // inner span ends, outer remains active
 * }  // outer span ends; spans flushed on close()
 * }</pre>
 *
 * <p>Threading: built on {@link ThreadLocal}. Each thread has its own span stack. Async frameworks
 * that hop threads (CompletableFuture, Reactor) need to manually propagate context — same
 * constraint as SLF4J MDC.
 */
public final class Tracer {

  private Tracer() {}

  private static final Logger LOG = System.getLogger(Tracer.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ThreadLocal<Deque> STACK = ThreadLocal.withInitial(Deque::new);

  // ----- captured inbound A2A flow ----- (WP-A2A-4 / ADR-007 §0, §4.1 locked)
  // When this agent is SERVING a request, captureIncoming() stores the inbound flow here
  // so a downstream gateway call PROPAGATES it (trace + session + hop + caller lineage)
  // instead of minting a fresh one. All null/0 => origin (non-A2A behavior unchanged).
  // ThreadLocal (not inheritable) — each serving thread keeps its own captured flow;
  // async frameworks that hop threads must re-capture, the same constraint as the span
  // stack above (see the class-level threading note).
  private static final ThreadLocal<String> CAPTURED_TRACE_ID = new ThreadLocal<>();
  private static final ThreadLocal<String> A2A_SESSION_ID = new ThreadLocal<>();
  private static final ThreadLocal<Integer> INBOUND_HOP_DEPTH = ThreadLocal.withInitial(() -> 0);
  private static final ThreadLocal<String> INBOUND_CALLER_AGENT_ID = new ThreadLocal<>();

  private static final List<Map<String, Object>> BUFFER = new ArrayList<>();
  private static final Object BUFFER_LOCK = new Object();

  private static volatile CredentialResolver credential;
  private static volatile String gatewayBaseUrl;
  private static final AtomicBoolean SHUTDOWN_REGISTERED = new AtomicBoolean(false);
  private static final AtomicBoolean NO_ADDRESS_WARNED = new AtomicBoolean(false);
  private static volatile HttpClient flushHttp;

  /** Whether the calling thread has an active span. */
  public static boolean isActive() {
    return STACK.get().peek() != null;
  }

  /**
   * Active trace id. Prefers the active span's trace; otherwise the captured mid-flow A2A trace (so
   * a downstream gateway call propagates the served flow even with no span open); null at origin
   * with no span and no capture.
   */
  public static String traceId() {
    Frame f = STACK.get().peek();
    if (f != null) {
      return f.traceId;
    }
    return CAPTURED_TRACE_ID.get();
  }

  /** Active span id, or null when no span is active. */
  public static String currentSpanId() {
    Frame f = STACK.get().peek();
    return f != null ? f.spanId : null;
  }

  /** Parent span id of the active span, or null for root spans. */
  public static String parentSpanId() {
    Frame f = STACK.get().peek();
    return f != null ? f.parentId : null;
  }

  // ------------------------------------------------------------------ //
  // A2A inbound-capture + outbound propagation (ADR-007 §0, §4.1 locked)
  // ------------------------------------------------------------------ //

  /**
   * True when the calling thread CAPTURED an inbound flow — so a downstream gateway call should
   * PROPAGATE the trace and attach caller/session/hop lineage rather than originate a new flow.
   * False = origin (header set unchanged).
   */
  public static boolean isA2aHop() {
    return CAPTURED_TRACE_ID.get() != null;
  }

  /** The captured inbound session id, or null when none was propagated. */
  public static String sessionId() {
    String s = A2A_SESSION_ID.get();
    return (s == null || s.isBlank()) ? null : s;
  }

  /** Advisory inbound hop depth captured from {@code x-shashu-hop-depth} (0 if absent). */
  public static int inboundHopDepth() {
    Integer n = INBOUND_HOP_DEPTH.get();
    return n != null ? n : 0;
  }

  /**
   * Hop depth to advertise on the next downstream call = inbound + 1 (advisory; the gateway
   * recounts authoritatively from its {@code a2a:hop:{trace_id}} counter).
   */
  public static int outboundHopDepth() {
    return inboundHopDepth() + 1;
  }

  /**
   * The captured inbound {@code x-shashu-caller-agent-id} — the upstream agent that INVOKED this
   * hop. The SDK FORWARDS this on the outbound gateway call so the gateway records the real
   * (invoker &rarr; this agent) edge, NEVER this agent's own id (§4.1). To stamp caller = self when
   * this agent INVOKES a downstream agent, use {@link
   * ai.shashu.sdk.context.WorkflowContext#buildInvocationHeaders}. null at origin /
   * caller-stripped.
   */
  public static String inboundCallerAgentId() {
    String c = INBOUND_CALLER_AGENT_ID.get();
    return (c == null || c.isBlank()) ? null : c;
  }

  /**
   * Capture an inbound flow from the headers of a request this agent is SERVING, so a downstream
   * call made through the SDK propagates the SAME flow (WP-A2A-4 / ADR-007 §0). Reads
   * (case-insensitively) {@code x-shashu-trace-id} (the flow id), {@code x-shashu-session-id},
   * {@code x-shashu-hop-depth}, and {@code x-shashu-caller-agent-id} (the upstream INVOKER). The
   * captured trace is PROPAGATED on the next outbound call (never re-minted); the inbound caller is
   * FORWARDED on the gateway call (§4.1), never replaced by self.
   *
   * <p>One-shot: the capture persists for the rest of the calling thread's work. Servers handling
   * many requests should prefer {@link #incoming(Map)} (a scoped try-with-resources) so one
   * request's lineage never leaks into the next. No inbound trace &rArr; origin: the ambient
   * capture is cleared and {@code false} is returned. Never throws.
   *
   * @param headers the inbound request headers (single-valued; multi-value frameworks should pass
   *     the first value per key)
   * @return true if a trace was captured (mid-flow hop); false at origin
   */
  public static boolean captureIncoming(Map<String, String> headers) {
    return applyCapture(coerce(headers));
  }

  /**
   * Scoped inbound capture: capture for the duration of serving ONE request, then restore the prior
   * context when the returned handle is closed — so a long-lived worker thread does not leak one
   * flow's lineage into the next. Prefer this in servers; {@link #captureIncoming(Map)} is the
   * one-shot.
   *
   * <pre>{@code
   * try (Tracer.A2aScope scope = Tracer.incoming(request.headers())) {
   *     client.chat().completions().create(...); // propagates the captured flow
   * }
   * }</pre>
   */
  public static A2aScope incoming(Map<String, String> headers) {
    return new A2aScope(coerce(headers));
  }

  /**
   * Set the captured A2A state from a normalized (lowercase-keyed) header map. Clears to origin
   * when no inbound trace is present. Returns true when a trace was captured.
   */
  private static boolean applyCapture(Map<String, String> h) {
    String trace = trimToNull(h.get("x-shashu-trace-id"));
    if (trace == null) {
      // Origin: clear any ambient capture so non-A2A behavior is unchanged.
      CAPTURED_TRACE_ID.remove();
      A2A_SESSION_ID.remove();
      INBOUND_HOP_DEPTH.set(0);
      INBOUND_CALLER_AGENT_ID.remove();
      return false;
    }
    CAPTURED_TRACE_ID.set(trace);
    A2A_SESSION_ID.set(trimToNull(h.get("x-shashu-session-id")));
    INBOUND_HOP_DEPTH.set(parseHopDepth(h.get("x-shashu-hop-depth")));
    INBOUND_CALLER_AGENT_ID.set(trimToNull(h.get("x-shashu-caller-agent-id")));
    return true;
  }

  /** Inbound hop depth as a non-negative int; 0 on absent/garbage (advisory). */
  private static int parseHopDepth(String raw) {
    String v = raw != null ? raw.trim() : "";
    try {
      int n = Integer.parseInt(v);
      return n > 0 ? n : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Lowercase-keyed copy of a header map (tolerant of null keys/values; never throws). */
  private static Map<String, String> coerce(Map<String, String> headers) {
    Map<String, String> o = new HashMap<>();
    if (headers == null) {
      return o;
    }
    for (Map.Entry<String, String> e : headers.entrySet()) {
      if (e.getKey() != null && e.getValue() != null) {
        o.put(e.getKey().toLowerCase(), e.getValue());
      }
    }
    return o;
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  /**
   * Configure the tracer's flush target. Call once at app startup — idempotent. If unset, the
   * tracer reads {@code SHASHU_API_KEY} + {@code SHASHU_BASE_URL} at flush time.
   */
  public static void configure(String apiKey, String gatewayBaseUrl) {
    Tracer.credential = apiKey == null ? null : new CredentialResolver(apiKey, null, false);
    applyGatewayBase(gatewayBaseUrl);
  }

  /** Configure flush with a cloud pass rather than a static key. */
  public static void configure(Supplier<String> tokenProvider, String gatewayBaseUrl) {
    Tracer.credential = new CredentialResolver(null, tokenProvider, false);
    applyGatewayBase(gatewayBaseUrl);
  }

  private static void applyGatewayBase(String gatewayBaseUrl) {
    if (gatewayBaseUrl != null && !gatewayBaseUrl.isBlank()) {
      Tracer.gatewayBaseUrl = gatewayBaseUrl.replaceAll("/$", "");
    } else if (gatewayBaseUrl == null) {
      Tracer.gatewayBaseUrl = null;
    }
    if (SHUTDOWN_REGISTERED.compareAndSet(false, true)) {
      Runtime.getRuntime().addShutdownHook(new Thread(Tracer::flush, "shashu-tracer-flush"));
    }
  }

  /** Open a new span. The returned handle MUST be closed (try-with-resources). */
  public static SpanHandle span(String name) {
    return span(name, null);
  }

  /** Open a new span with attributes. */
  public static SpanHandle span(String name, Map<String, Object> attributes) {
    Deque stack = STACK.get();
    Frame parent = stack.peek();

    // First span on this thread → start a new trace, UNLESS we're inside a captured
    // A2A flow, in which case inherit the captured trace so the flow stays one lineage
    // (parity with the Python/Node/.NET reference). Nested spans inherit the parent's.
    String traceId;
    if (parent != null) {
      traceId = parent.traceId;
    } else {
      String captured = CAPTURED_TRACE_ID.get();
      traceId = captured != null ? captured : newId();
    }
    String parentId = parent != null ? parent.spanId : null;
    String spanId = newId();
    Instant startUtc = Instant.now();

    Frame frame =
        new Frame(
            name,
            traceId,
            spanId,
            parentId,
            startUtc,
            attributes != null ? new HashMap<>(attributes) : null);
    stack.push(frame);
    return new SpanHandle(frame);
  }

  /**
   * Flush buffered spans to the gateway's {@code POST /api/telemetry/spans} endpoint. Best-effort;
   * failures are logged but never thrown. With no gateway address configured the spans are dropped
   * and one warning is logged &mdash; the tracer does not guess a host.
   */
  public static void flush() {
    List<Map<String, Object>> snapshot;
    synchronized (BUFFER_LOCK) {
      if (BUFFER.isEmpty()) return;
      snapshot = new ArrayList<>(BUFFER);
      BUFFER.clear();
    }

    String key = credential != null ? credential.get() : ShashuConfig.envApiKey();
    if (key == null || key.isBlank()) return; // No key = no flush.

    // No address = no telemetry. The tracer does NOT throw the way the
    // client and the interceptor do: a customer who never configured
    // tracing should not have their app fall over because a span was
    // opened. It also does not guess a host - spans carry prompt-adjacent
    // workflow names, and posting them to an address nobody chose is the
    // same defect as routing a prompt there.
    String base = ShashuConfig.optionalBaseUrl(gatewayBaseUrl);
    if (base == null) {
      warnNoGatewayAddress(snapshot.size());
      return;
    }
    base = base.replaceAll("/$", "");

    try {
      byte[] body = MAPPER.writeValueAsBytes(snapshot);
      if (flushHttp == null) {
        flushHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      }
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(base + "/api/telemetry/spans"))
              .header("Authorization", "Bearer " + key)
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(2))
              .POST(HttpRequest.BodyPublishers.ofByteArray(body))
              .build();
      flushHttp.send(req, HttpResponse.BodyHandlers.discarding());
    } catch (IOException | InterruptedException e) {
      System.err.println("[trustgate.tracer] flush failed: " + e.getMessage());
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * One WARNING, the first time a flush finds no gateway address. Routed through {@link
   * System.Logger} so it surfaces without an SLF4J/log4j binding, and warned once per process so a
   * tracer flushing on every span cannot turn a misconfiguration into a log flood.
   */
  private static void warnNoGatewayAddress(int dropped) {
    if (NO_ADDRESS_WARNED.compareAndSet(false, true)) {
      LOG.log(
          Level.WARNING,
          "SHASHU tracer has no gateway address, so "
              + dropped
              + " buffered span(s) were dropped rather than sent. Call"
              + " Tracer.configure(apiKey, gatewayBaseUrl) or set the"
              + " SHASHU_BASE_URL environment variable. SHASHU is"
              + " self-hosted: the SDK has no default address and will not"
              + " guess one. (warned once per process)");
    }
  }

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static void recordSpan(Frame frame) {
    // Wire shape locked-in by core/app/api/routers/telemetry.py
    // SpanIngest model: parent_span_id (not parent_id), epoch floats
    // for start_time / end_time (NOT ISO strings), float `duration`
    // in seconds (not duration_ms). Drift here makes the gateway
    // 422 the request and the span never lands in agent_spans.
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("trace_id", frame.traceId);
    record.put("span_id", frame.spanId);
    record.put("parent_span_id", frame.parentId); // gateway's field name
    record.put("name", frame.name);
    double startEpoch = frame.startUtc.toEpochMilli() / 1000.0;
    Instant end = Instant.now();
    double endEpoch = end.toEpochMilli() / 1000.0;
    record.put("start_time", startEpoch);
    record.put("end_time", endEpoch);
    record.put("duration", endEpoch - startEpoch);
    record.put("status", "ok");
    if (frame.attributes != null && !frame.attributes.isEmpty()) {
      // Gateway's SpanIngest accepts "metadata" as the free-form
      // attribute bag — not "attributes".
      record.put("metadata", frame.attributes);
    }
    synchronized (BUFFER_LOCK) {
      BUFFER.add(record);
    }
  }

  /** Stack frame for one open span. */
  private static final class Frame {
    final String name;
    final String traceId;
    final String spanId;
    final String parentId; // null for root
    final Instant startUtc;
    final Map<String, Object> attributes;

    Frame(
        String name,
        String traceId,
        String spanId,
        String parentId,
        Instant startUtc,
        Map<String, Object> attributes) {
      this.name = name;
      this.traceId = traceId;
      this.spanId = spanId;
      this.parentId = parentId;
      this.startUtc = startUtc;
      this.attributes = attributes;
    }
  }

  /**
   * Returned from {@link #span}. Closing pops the frame off the thread's stack and records the span
   * for flushing.
   */
  public static final class SpanHandle implements AutoCloseable {
    private final Frame frame;
    private boolean closed = false;

    SpanHandle(Frame frame) {
      this.frame = frame;
    }

    @Override
    /** End this span (or client) and release its resources. */
    public void close() {
      if (closed) return;
      closed = true;
      Deque stack = STACK.get();
      // Pop until we've popped THIS frame. Defensive against
      // out-of-order close (which shouldn't happen with
      // try-with-resources but might if someone misuses the API).
      while (!stack.isEmpty()) {
        Frame popped = stack.pop();
        if (popped == frame) {
          recordSpan(popped);
          return;
        }
        // Out-of-order close: still record the popped frames so
        // we don't leak them, then continue popping.
        recordSpan(popped);
      }
    }
  }

  /**
   * Scoped A2A capture handle returned by {@link #incoming(Map)}. Snapshots the prior captured-flow
   * state on construction and restores it on {@link #close()} so serving one request never leaks
   * lineage into the next on a reused worker thread. {@code close()} declares no checked
   * exceptions, so try-with-resources needs no {@code throws} clause.
   */
  public static final class A2aScope implements AutoCloseable {
    private final String priorTrace;
    private final String priorSession;
    private final int priorHop;
    private final String priorCaller;
    private boolean closed = false;

    A2aScope(Map<String, String> headers) {
      this.priorTrace = CAPTURED_TRACE_ID.get();
      this.priorSession = A2A_SESSION_ID.get();
      this.priorHop = inboundHopDepth();
      this.priorCaller = INBOUND_CALLER_AGENT_ID.get();
      applyCapture(headers);
    }

    @Override
    /** End this span (or client) and release its resources. */
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      restore(CAPTURED_TRACE_ID, priorTrace);
      restore(A2A_SESSION_ID, priorSession);
      INBOUND_HOP_DEPTH.set(priorHop);
      restore(INBOUND_CALLER_AGENT_ID, priorCaller);
    }

    private static void restore(ThreadLocal<String> tl, String value) {
      if (value == null) {
        tl.remove();
      } else {
        tl.set(value);
      }
    }
  }

  /**
   * Minimal stack to avoid pulling java.util.Deque concurrent variants into a hot path.
   * Single-thread access (one ThreadLocal per thread).
   */
  private static final class Deque {
    private final java.util.ArrayDeque<Frame> inner = new java.util.ArrayDeque<>();

    Frame peek() {
      return inner.peek();
    }

    void push(Frame f) {
      inner.push(f);
    }

    Frame pop() {
      return inner.pop();
    }

    boolean isEmpty() {
      return inner.isEmpty();
    }
  }
}
