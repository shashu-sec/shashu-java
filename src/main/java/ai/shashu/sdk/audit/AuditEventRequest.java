package ai.shashu.sdk.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

/**
 * Request payload for {@code POST /v1/audit/events}. Mirrors the Python SDK's {@code
 * tg.audit.events(...)} kwargs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AuditEventRequest {

  @JsonProperty("source")
  private final String source;

  @JsonProperty("conversation")
  private final AuditConversation conversation;

  @JsonProperty("external_event_id")
  private final String externalEventId;

  @JsonProperty("occurred_at")
  private final String occurredAt;

  @JsonProperty("user_context")
  private final Map<String, Object> userContext;

  @JsonProperty("model")
  private final String model;

  private AuditEventRequest(Builder b) {
    this.source = b.source;
    this.conversation = b.conversation;
    this.externalEventId = b.externalEventId;
    this.occurredAt = b.occurredAt;
    this.userContext = b.userContext != null ? Map.copyOf(b.userContext) : null;
    this.model = b.model;
  }

  /** Connector that produced this ingested event. */
  public String source() {
    return source;
  }

  /** Turns being ingested as an audit event. */
  public AuditConversation conversation() {
    return conversation;
  }

  /** Caller's id for this event, used for idempotent ingest. */
  public String externalEventId() {
    return externalEventId;
  }

  /** When the vendor call happened, as ISO-8601. */
  public String occurredAt() {
    return occurredAt;
  }

  /** Extra caller-supplied fields attached to the audit row. */
  public Map<String, Object> userContext() {
    return userContext;
  }

  /** Model id on the request or the routed model on the response. */
  public String model() {
    return model;
  }

  /** Start a fluent builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for this type. */
  public static final class Builder {
    private String source;
    private AuditConversation conversation;
    private String externalEventId;
    private String occurredAt;
    private Map<String, Object> userContext;
    private String model;

    /**
     * Connector source: one of {@code ms_copilot}, {@code salesforce_einstein}, {@code
     * google_workspace}, {@code purview_dlp}, {@code audit_other}. Determines which Shashu surface
     * the gateway routes the conversation through.
     */
    public Builder source(String source) {
      this.source = source;
      return this;
    }

    /** Turns being ingested as an audit event. */
    public Builder conversation(AuditConversation conversation) {
      this.conversation = conversation;
      return this;
    }

    /**
     * Vendor's stable event id — used for de-duplication across re-pushes of the same source event.
     */
    public Builder externalEventId(String externalEventId) {
      this.externalEventId = externalEventId;
      return this;
    }

    /**
     * When the vendor LLM call originally happened. ISO-8601 string (e.g. {@code
     * "2026-05-02T14:00:00Z"}).
     */
    public Builder occurredAt(String occurredAt) {
      this.occurredAt = occurredAt;
      return this;
    }

    /** Extra caller-supplied fields attached to the audit row. */
    public Builder userContext(Map<String, Object> userContext) {
      this.userContext = userContext;
      return this;
    }

    /** Add one key to {@code userContext}. */
    public Builder addUserContext(String key, Object value) {
      if (this.userContext == null) this.userContext = new HashMap<>();
      this.userContext.put(key, value);
      return this;
    }

    /** Model id on the request or the routed model on the response. */
    public Builder model(String model) {
      this.model = model;
      return this;
    }

    /** Construct the immutable value from this builder. */
    public AuditEventRequest build() {
      if (source == null || source.isBlank()) {
        throw new IllegalStateException("source is required");
      }
      if (conversation == null) {
        throw new IllegalStateException("conversation is required");
      }
      return new AuditEventRequest(this);
    }
  }
}
