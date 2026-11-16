package ai.shashu.sdk.audit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One message in an audit conversation. */
public final class AuditMessage {

  private final String role;
  private final String content;

  @JsonCreator
  /** One turn in an ingested conversation. */
  public AuditMessage(@JsonProperty("role") String role, @JsonProperty("content") String content) {
    this.role = role;
    this.content = content;
  }

  /** Message role ({@code system}, {@code user}, {@code assistant}, or {@code tool}). */
  public String role() {
    return role;
  }

  /** Message or delta text. */
  public String content() {
    return content;
  }
}
