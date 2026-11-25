package ai.shashu.sdk;

import java.util.Map;

/**
 * The decision envelope a client can read. {@link #decision()} is one of {@code allow}, {@code
 * allow_altered}, {@code not_checked}, {@code block}, {@code refuse}, {@code fail} — or an
 * unrecognised value the gateway added later, which {@link Shashu#isBlock(Object)} treats as a
 * block.
 */
public final class ShashuDecision {

  private final String decision;
  private final String code;
  private final Boolean retryable;
  private final String id;
  private final Attachments attachments;
  private final MemoryActivity memory;
  private final RoutePolicy routePolicy;
  private final Map<String, Object> inspection;

  /** Parsed {@code trustgate} decision envelope from a response. */
  public ShashuDecision(String decision, String code, Boolean retryable, String id) {
    this(decision, code, retryable, id, null, null, null, null);
  }

  ShashuDecision(
      String decision,
      String code,
      Boolean retryable,
      String id,
      Attachments attachments,
      MemoryActivity memory,
      RoutePolicy routePolicy,
      Map<String, Object> inspection) {
    this.decision = decision;
    this.code = code;
    this.retryable = retryable;
    this.id = id;
    this.attachments = attachments;
    this.memory = memory;
    this.routePolicy = routePolicy;
    this.inspection = inspection;
  }

  /** The decision string. Not synthesised when absent. */
  public String decision() {
    return decision;
  }

  /** The finding code, when the envelope or header carried one. */
  public String code() {
    return code;
  }

  /** Whether the caller should retry, when stated. */
  public Boolean retryable() {
    return retryable;
  }

  /** The audit-record id, when stated. */
  public String id() {
    return id;
  }

  /**
   * What happened to each attached file; null when the request had none. Read off the body; off the
   * {@code x-shashu-attachments} counts when the body carried no envelope. {@link
   * Attachments#carrier()} says which.
   */
  public Attachments attachments() {
    return attachments;
  }

  /** Memory writes and recalls; null when there were none. Same carrier rule. */
  public MemoryActivity memory() {
    return memory;
  }

  /** Why a route was refused for the data in the request; body only. */
  public RoutePolicy routePolicy() {
    return routePolicy;
  }

  /**
   * What the gateway inspected: {@code input}, {@code output}, {@code reason} and a {@code
   * detectors} map of detector name to state (e.g. {@code memory} -> {@code checked} / {@code
   * partial} / {@code skipped}). Same shape off the body or the {@code x-shashu-inspection} header;
   * read-only; states are raw strings. Null when neither carried one.
   */
  public Map<String, Object> inspection() {
    return inspection;
  }

  /** Files the model received without a complete inspection. */
  public int filesUnverified() {
    return attachments == null ? 0 : attachments.notInspected() + attachments.scannedPartial();
  }

  /** Count of files the gateway refused to forward on this request. */
  public int filesBlocked() {
    return attachments == null ? 0 : attachments.blocked();
  }

  /** Memory writes the gateway refused to store. */
  public int memoryBlocked() {
    return memory == null ? 0 : memory.writesBlocked();
  }

  /** Count of recalled memories the gateway flagged on this request. */
  public int memoryRecallsFlagged() {
    return memory == null ? 0 : memory.recallsFlagged();
  }

  /**
   * {@code gateway} / {@code observed} / {@code hosted} / {@code mixed}, raw; null when unknown.
   */
  public String memoryCoverage() {
    return memory == null ? null : memory.coverage();
  }
}
