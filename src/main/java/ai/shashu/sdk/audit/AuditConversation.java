package ai.shashu.sdk.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The conversation payload sent to {@code /v1/audit/events}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AuditConversation {

  @JsonProperty("messages")
  private final List<AuditMessage> messages;

  @JsonProperty("response")
  private final String response;

  private AuditConversation(Builder b) {
    this.messages = Collections.unmodifiableList(new ArrayList<>(b.messages));
    this.response = b.response;
  }

  /** Conversation turns in this payload. */
  public List<AuditMessage> messages() {
    return messages;
  }

  /** Model reply text on an ingested audit event. */
  public String response() {
    return response;
  }

  /** Start a fluent builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for this type. */
  public static final class Builder {
    private final List<AuditMessage> messages = new ArrayList<>();
    private String response;

    /** Append one conversation turn. */
    public Builder addMessage(AuditMessage m) {
      this.messages.add(m);
      return this;
    }

    /** Conversation turns in this payload. */
    public Builder messages(List<AuditMessage> messages) {
      this.messages.clear();
      this.messages.addAll(messages);
      return this;
    }

    /** The vendor LLM's final response text (audit-only — not re-sent). */
    public Builder response(String response) {
      this.response = response;
      return this;
    }

    /** Construct the immutable value from this builder. */
    public AuditConversation build() {
      if (messages.isEmpty()) {
        throw new IllegalStateException("at least one message is required");
      }
      return new AuditConversation(this);
    }
  }
}
