package ai.shashu.sdk.context;

import ai.shashu.sdk.ShashuClient;
import ai.shashu.sdk.tracing.Tracer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@code x-shashu-*} + {@code x-trustlog-*} headers that give the gateway Shadow AI
 * attribution and runtime context for every request. Symmetric with the .NET SDK's {@code
 * WorkflowContext} class and the Python SDK's {@code build_trustgate_headers()}.
 *
 * <p>What gets emitted:
 *
 * <ul>
 *   <li>{@code x-shashu-source} — one of {@code n8n} / {@code github_actions} / {@code gitlab_ci} /
 *       {@code local_script}.
 *   <li>{@code x-shashu-trace-id} — workflow trace id from {@code N8N_EXECUTION_ID} / {@code
 *       GITHUB_RUN_ID} / {@code CI_PIPELINE_ID}, or a fresh UUID per call.
 *   <li>{@code x-shashu-workflow-name} — when running inside a managed workflow.
 *   <li>{@code x-shashu-workflow-step} — caller-supplied or auto-detected from {@code
 *       N8N_NODE_NAME} / {@code N8N_NODE_ID}.
 *   <li>{@code x-trustlog-os} / {@code -arch} / {@code -runtime-version} — runtime metadata.
 *   <li>{@code x-shashu-source-tool} — JSON blob with SDK identification + system info. Free-form;
 *       extends without breaking the contract.
 * </ul>
 */
public final class WorkflowContext {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WorkflowContext() {}

  /**
   * THE session-id resolution point. Every caller — the per-request path in {@code ShashuApi},
   * {@link #build()} and {@link #buildInvocationHeaders} — goes through here, so the precedence
   * rule exists once and a mutation to it is visible to the tests.
   *
   * <p>Precedence, per {@code contracts/sdk-surface.v1.md}:
   *
   * <ol>
   *   <li>a per-request session is an INSTRUCTION and wins;
   *   <li>a session captured from an inbound A2A hop OVERRIDES the construction-time default;
   *   <li>otherwise the construction-time default (minted when omitted).
   * </ol>
   *
   * @param perRequestSessionId session passed on this call, or null
   * @param clientDefault the client's construction-time session, or null when the caller has none
   *     (static header building)
   * @return the session to stamp, or null when there is none
   */
  public static String resolveSession(String perRequestSessionId, String clientDefault) {
    if (perRequestSessionId != null && !perRequestSessionId.isBlank()) {
      return perRequestSessionId.trim();
    }
    String captured = Tracer.sessionId();
    if (captured != null && !captured.isBlank()) {
      return captured;
    }
    return clientDefault;
  }

  /**
   * Build the per-request header map. Static, allocates a fresh map on every call (per-request
   * trace id when no managed-workflow trace exists).
   */
  public static Map<String, String> build() {
    return build(null, null, resolveSession(null, null));
  }

  /**
   * Build the per-request header map with an explicit workflow step + source-tool overrides.
   *
   * @param workflowStep optional explicit step (e.g. {@code "Data_Extraction"}); wins over
   *     auto-detection.
   * @param sourceToolOverrides extra fields merged into the {@code x-shashu-source-tool} JSON blob.
   */
  public static Map<String, String> build(
      String workflowStep, Map<String, Object> sourceToolOverrides) {
    return build(workflowStep, sourceToolOverrides, resolveSession(null, null));
  }

  /**
   * Build the per-request header map.
   *
   * @param workflowStep optional explicit step; wins over auto-detection.
   * @param sourceToolOverrides extra fields merged into the {@code x-shashu-source-tool} JSON blob.
   * @param sessionId session to stamp. The caller resolved precedence; this method does not mint.
   *     Emitted whenever non-empty, at origin and mid-flow.
   */
  public static Map<String, String> build(
      String workflowStep, Map<String, Object> sourceToolOverrides, String sessionId) {
    String source = detectSource();
    String traceId = resolveTraceId();
    String workflowName = resolveWorkflowName();
    String step =
        (workflowStep != null && !workflowStep.isBlank()) ? workflowStep : resolveN8NWorkflowStep();

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-shashu-source", source);
    headers.put("x-shashu-trace-id", traceId);
    headers.put("x-trustlog-os", System.getProperty("os.name", "unknown").toLowerCase());
    headers.put("x-trustlog-arch", System.getProperty("os.arch", "unknown").toLowerCase());
    headers.put(
        "x-trustlog-runtime-version", "java-" + System.getProperty("java.version", "unknown"));

    if (workflowName != null && !workflowName.isBlank()) {
      headers.put("x-shashu-workflow-name", workflowName);
    }
    if (step != null && !step.isBlank()) {
      headers.put("x-shashu-workflow-step", step);
    }

    // Source-tool metadata: SDK identification + system info. Same
    // contract as the .NET SDK's WorkflowContext.
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("sdk", "trustgate-java");
    metadata.put("sdk_version", ShashuClient.SDK_VERSION);
    metadata.put("language", "java");
    metadata.put("language_version", System.getProperty("java.version"));
    metadata.put("os", System.getProperty("os.name"));
    metadata.put("arch", System.getProperty("os.arch"));
    metadata.put("timestamp_utc", Instant.now().toString());
    if (sourceToolOverrides != null && !sourceToolOverrides.isEmpty()) {
      metadata.putAll(sourceToolOverrides);
    }
    try {
      headers.put("x-shashu-source-tool", MAPPER.writeValueAsString(metadata));
    } catch (Exception e) {
      // Non-fatal: omit source-tool if serialisation fails. The other
      // headers carry enough context for the gateway to attribute traffic.
    }

    // Tracer integration: when a span is active on the current thread,
    // OVERRIDE the workflow's trace id with the span's id and emit
    // span/parent headers so the gateway can build hierarchy. A span opened
    // inside a captured A2A flow inherits that flow's trace (see Tracer.span),
    // so the trace stamped here is the propagated flow id, not a fresh one.
    if (Tracer.isActive()) {
      String activeTrace = Tracer.traceId();
      if (activeTrace != null) {
        headers.put("x-shashu-trace-id", activeTrace);
        String span = Tracer.currentSpanId();
        String parent = Tracer.parentSpanId();
        headers.put("x-shashu-span-id", span != null ? span : "");
        headers.put("x-shashu-parent-id", parent != null ? parent : "");
      }
    } else if (Tracer.isA2aHop()) {
      // Mid-flow without a span: PROPAGATE the captured trace (do NOT mint a new one).
      String flowTrace = Tracer.traceId();
      if (flowTrace != null) {
        headers.put("x-shashu-trace-id", flowTrace);
      }
    }

    // Session: emit whenever we have one — origin and A2A. Precedence is
    // resolved in exactly ONE place, {@link #resolveSession}; this method
    // stamps what it is handed and NEVER reads the tracer as a second
    // source. A second read here made the A2A-overrides rule unprovable:
    // a mutation that emptied the caller's resolution left the test green.
    if (sessionId != null && !sessionId.isBlank()) {
      headers.put("x-shashu-session-id", sessionId);
    }

    // A2A OUTBOUND PROPAGATION (WP-A2A-4 / ADR-007 §0, §4.1 LOCKED). Only mid-flow
    // (an inbound flow was captured) do we attach the cross-agent lineage headers; at
    // origin the header set is unchanged, so non-A2A users are unaffected.
    //   - caller-agent-id: the FORWARDED inbound caller (the upstream INVOKER), NEVER
    //                      this agent's own id. Origin / caller-stripped → omitted, so
    //                      the gateway records (invoker → this agent), not a self-edge.
    //                      To stamp caller = self when INVOKING a downstream agent, use
    //                      buildInvocationHeaders (the invoking side).
    //   - hop-depth:       inbound + 1 (advisory; the gateway recounts authoritatively).
    if (Tracer.isA2aHop()) {
      String inboundCaller = Tracer.inboundCallerAgentId();
      if (inboundCaller != null) {
        headers.put("x-shashu-caller-agent-id", inboundCaller);
      }
      headers.put("x-shashu-hop-depth", Integer.toString(Tracer.outboundHopDepth()));
    }

    return headers;
  }

  /**
   * Build the Shashu lineage headers to stamp on an INTER-AGENT INVOCATION — the message THIS agent
   * sends to a DOWNSTREAM agent it is calling (the invoking side, ADR-007 §4.1 locked). The
   * downstream agent then calls {@link Tracer#captureIncoming} on these headers and FORWARDS this
   * caller on its own gateway call — forming the (this agent &rarr; downstream agent) edge.
   *
   * <p>{@code callerAgentId} is THIS agent's own id (the invoker stamps itself) — the one place the
   * SDK sets caller = self. It is advisory lineage, NEVER an auth credential; authenticate to the
   * downstream service by its own means.
   *
   * <p>Convenience overload pulling all lineage fields from the tracer.
   *
   * @param callerAgentId this agent's own id (required, non-blank)
   */
  public static Map<String, String> buildInvocationHeaders(String callerAgentId) {
    return buildInvocationHeaders(callerAgentId, null, null, null);
  }

  /**
   * Build the Shashu lineage headers for an inter-agent invocation (see {@link
   * #buildInvocationHeaders(String)}). Defaults pull from the tracer so the served flow is
   * propagated: {@code traceId} &rarr; {@link Tracer#traceId()} (a fresh root is minted at origin,
   * the captured flow is propagated mid-flow); {@code hopDepth} &rarr; {@link
   * Tracer#outboundHopDepth()}; {@code sessionId} &rarr; the captured session.
   *
   * @param callerAgentId this agent's own id (required, non-blank)
   * @param traceId explicit trace id, or null to use the tracer's
   * @param sessionId explicit session id, or null to use the captured session
   * @param hopDepth explicit hop depth, or null to use the tracer's outbound depth
   */
  public static Map<String, String> buildInvocationHeaders(
      String callerAgentId, String traceId, String sessionId, Integer hopDepth) {
    if (callerAgentId == null || callerAgentId.isBlank()) {
      throw new IllegalArgumentException("callerAgentId is required (this agent's own id).");
    }

    String trace = (traceId != null && !traceId.isBlank()) ? traceId : Tracer.traceId();
    if (trace == null || trace.isBlank()) {
      trace = UUID.randomUUID().toString().replace("-", "");
    }
    int hop = (hopDepth != null) ? hopDepth : Tracer.outboundHopDepth();
    String session = resolveSession(sessionId, null);

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-shashu-trace-id", trace);
    headers.put("x-shashu-caller-agent-id", callerAgentId);
    headers.put("x-shashu-hop-depth", Integer.toString(hop));
    if (session != null && !session.isBlank()) {
      headers.put("x-shashu-session-id", session);
    }
    return headers;
  }

  /**
   * Detect the Shadow AI source from environment variables. Order matters — n8n &gt; github_actions
   * &gt; gitlab_ci &gt; local_script.
   */
  public static String detectSource() {
    if (notBlank(System.getenv("N8N_EXECUTION_ID"))) return "n8n";
    if ("true".equals(System.getenv("GITHUB_ACTIONS"))) return "github_actions";
    if ("true".equals(System.getenv("GITLAB_CI"))) return "gitlab_ci";
    return "local_script";
  }

  private static String resolveTraceId() {
    String n8n = System.getenv("N8N_EXECUTION_ID");
    if (notBlank(n8n)) return n8n;

    String github = System.getenv("GITHUB_RUN_ID");
    if (notBlank(github)) return github;

    if ("true".equals(System.getenv("GITLAB_CI"))) {
      String pipelineId = System.getenv("CI_PIPELINE_ID");
      if (notBlank(pipelineId)) return pipelineId;
    }

    // Local: fresh UUID. The tracer module (item #4.D) overrides this
    // when a span is active.
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static String resolveWorkflowName() {
    if (notBlank(System.getenv("N8N_EXECUTION_ID"))) {
      String name = System.getenv("N8N_WORKFLOW_NAME");
      if (notBlank(name)) return name;
      String id = System.getenv("N8N_WORKFLOW_ID");
      return notBlank(id) ? id : "n8n";
    }
    if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
      String wf = System.getenv("GITHUB_WORKFLOW");
      return notBlank(wf) ? wf : "github_actions";
    }
    if ("true".equals(System.getenv("GITLAB_CI"))) {
      String name = System.getenv("CI_JOB_NAME");
      if (notBlank(name)) return name;
      String src = System.getenv("CI_PIPELINE_SOURCE");
      return notBlank(src) ? src : "gitlab_ci";
    }
    return null;
  }

  private static String resolveN8NWorkflowStep() {
    if (!notBlank(System.getenv("N8N_EXECUTION_ID"))) return null;
    String name = System.getenv("N8N_NODE_NAME");
    if (notBlank(name)) return name;
    String id = System.getenv("N8N_NODE_ID");
    return notBlank(id) ? id : null;
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
