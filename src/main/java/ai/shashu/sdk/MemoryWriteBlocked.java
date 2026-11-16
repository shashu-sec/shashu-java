package ai.shashu.sdk;

/**
 * 403 — the gateway refused to store a memory write.
 *
 * <p>A {@link ShashuPolicyViolation}, so every existing {@code catch} for that type still catches
 * it. Thrown when the decision's {@code memory} block reports a blocked write; which write, and
 * why, is on {@link #memory()}. The type is chosen from that block, never from a copy of the {@code
 * code} or reason vocabulary.
 */
public final class MemoryWriteBlocked extends ShashuPolicyViolation {

  MemoryWriteBlocked(int statusCode, String responseBody, String message, String errorType) {
    super(statusCode, responseBody, message, errorType);
  }

  /** The decision's memory block. */
  public MemoryActivity memory() {
    ShashuDecision decision = Shashu.decisionOf(this);
    return decision == null ? null : decision.memory();
  }
}
