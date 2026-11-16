package ai.shashu.sdk.audit;

import ai.shashu.sdk.internal.ShashuApi;
import java.util.Objects;

/**
 * Audit-event ingestion. Maps to {@code POST /v1/audit/events}.
 *
 * <p>Use when you have a connector (Microsoft Purview, Salesforce Event Bus, Google Workspace audit
 * log, custom webhook) that observes vendor LLM events and needs to push them through Shashu's
 * scanner pipeline for audit-only logging — verdicts feed the audit log + alerts but never block
 * the originating request (the user already saw the vendor's response).
 */
public final class AuditResource {

  private final ShashuApi api;

  /** Namespace for {@code POST /v1/audit/events}. */
  public AuditResource(ShashuApi api) {
    this.api = api;
  }

  /**
   * Submit a single vendor LLM event for audit-only scanning.
   *
   * <p>The most-actionable field on the response is {@link AuditEventResponse#isThreat()};
   * downstream alerting fires on it.
   *
   * <pre>{@code
   * AuditEventResponse result = client.audit().events(
   *     AuditEventRequest.builder()
   *         .source("ms_copilot")
   *         .externalEventId("evt_abc123")
   *         .occurredAt("2026-05-02T14:00:00Z")
   *         .conversation(AuditConversation.builder()
   *             .addMessage(new AuditMessage("user", "summarize Q3 board minutes"))
   *             .response("Q3 minutes: ...")
   *             .build())
   *         .userContext(Map.of("user_id", "alice@example.com"))
   *         .build());
   * if (result.isThreat()) { ...alert... }
   * }</pre>
   */
  public AuditEventResponse events(AuditEventRequest request) {
    Objects.requireNonNull(request, "request");
    return api.post("/v1/audit/events", request, AuditEventResponse.class);
  }
}
