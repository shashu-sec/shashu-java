package ai.shashu.sdk;

/**
 * What happened to one attached file. A closed set on the gateway ({@code
 * contracts/decision-envelope.v1.md}, contract {@code 2026-10-01}).
 *
 * <p>A value this SDK does not know is {@link #UNKNOWN}, never an exception; the wire string stays
 * on {@link Attachment#rawState()}.
 */
public enum AttachmentState {
  SCANNED("scanned"),
  SCANNED_PARTIAL("scanned_partial"),
  NOT_INSPECTED("not_inspected"),
  BLOCKED("blocked"),
  UNKNOWN("unknown");

  private final String wire;

  AttachmentState(String wire) {
    this.wire = wire;
  }

  /** The wire spelling. */
  public String wire() {
    return wire;
  }

  /** The state for a wire value; {@link #UNKNOWN} for anything unrecognised, including null. */
  public static AttachmentState fromWire(String value) {
    if (value != null) {
      for (AttachmentState s : values()) {
        if (s != UNKNOWN && s.wire.equals(value)) {
          return s;
        }
      }
    }
    return UNKNOWN;
  }
}
