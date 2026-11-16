package ai.shashu.sdk.internal;

import java.util.HashMap;
import java.util.Map;

/**
 * The test tree's door onto the retrieval internals.
 *
 * <p>It lives in {@code src/test} in the internal package so the seams it needs &mdash; resetting
 * the process-wide bus, driving the environment &mdash; stay package-private on the shipped
 * classes. A mutable global switch reachable from a customer's classpath is a defect, not a
 * convenience, so the reach is arranged here rather than by widening anything in {@code src/main}.
 */
public final class RetrievalTestAccess {

  private RetrievalTestAccess() {}

  /**
   * Return every carrier to its start state. The bus is process-wide by nature, so a test that did
   * not reset it would measure the previous test's declarations.
   */
  public static void reset() {
    RetrievalBus.resetForTesting();
    setEnv(new HashMap<>());
    CredentialResolver.setNowSeconds(null);
  }

  /** The caps the SDK compiled in, for the fence that holds them to the schema. */
  public static Map<String, Integer> caps() {
    return RetrievalBus.caps();
  }

  /** Drive {@code SHASHU_*} / {@code TRUSTGATE_*} without touching the JVM's env. */
  public static void setEnv(Map<String, String> values) {
    Map<String, String> copy = new HashMap<>(values);
    ShashuConfig.setEnv(copy::get);
  }

  /** Drive {@link CredentialResolver} time without waiting. */
  public static void setNowSeconds(java.util.function.LongSupplier clock) {
    CredentialResolver.setNowSeconds(clock);
  }
}
