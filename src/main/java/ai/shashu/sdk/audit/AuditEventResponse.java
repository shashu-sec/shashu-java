package ai.shashu.sdk.audit;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway response from {@code /v1/audit/events}. The most-actionable field is {@link #isThreat()};
 * downstream alerting fires on it.
 */
public final class AuditEventResponse {

  private final String eventId;
  private final String verdict;
  private final boolean isThreat;
  private final List<String> threats;
  private final List<String> pillars;
  private final String severity;
  private final Map<String, Object> extra = new HashMap<>();

  @JsonCreator
  /** Parsed body of {@code POST /v1/audit/events}. */
  public AuditEventResponse(
      @JsonProperty("event_id") String eventId,
      @JsonProperty("verdict") String verdict,
      @JsonProperty("is_threat") boolean isThreat,
      @JsonProperty("threats") List<String> threats,
      @JsonProperty("pillars") List<String> pillars,
      @JsonProperty("severity") String severity) {
    this.eventId = eventId;
    this.verdict = verdict;
    this.isThreat = isThreat;
    this.threats =
        threats != null ? Collections.unmodifiableList(threats) : Collections.emptyList();
    this.pillars =
        pillars != null ? Collections.unmodifiableList(pillars) : Collections.emptyList();
    this.severity = severity;
  }

  /** Gateway-assigned id of the ingested audit event. */
  public String eventId() {
    return eventId;
  }

  /** Scanner verdict for the ingested event. */
  public String verdict() {
    return verdict;
  }

  /** Whether the scanner treated this event as a threat. */
  public boolean isThreat() {
    return isThreat;
  }

  /** Threat labels the scanner attached. */
  public List<String> threats() {
    return threats;
  }

  /** Forensic pillars the scanner attached. */
  public List<String> pillars() {
    return pillars;
  }

  /** Scanner severity for the ingested event. */
  public String severity() {
    return severity;
  }

  /** Free-form extension fields the gateway may emit alongside the typed ones. */
  public Map<String, Object> extra() {
    return extra;
  }

  @JsonAnySetter
  @SuppressWarnings("unused")
  private void setExtra(String key, Object value) {
    this.extra.put(key, value);
  }
}
