package ai.shashu.sdk;

import ai.shashu.sdk.internal.MemoryContextBuilder;
import ai.shashu.sdk.internal.RetrievalBus;
import ai.shashu.sdk.internal.RetrievalScopeHandle;
import ai.shashu.sdk.internal.ShashuConfig;

/**
 * The memory declaration surface: tell Shashu AI which memory items were bound into the prompt.
 * Same unit-of-work shape as {@link Retrieval}.
 *
 * <p>There are exactly two members here. Collectors are not shipped in this release. Both members
 * are no-ops when {@code SHASHU_DISABLE=1}.
 */
public final class Memory {

  private Memory() {}

  /** Attach recalled memory items to the next request on this thread. */
  public static void declareMemory(Object items, String store) {
    declareMemory(items, store, "agent", "sdk");
  }

  /** Attach recalled memory items to the next request on this thread. */
  public static void declareMemory(Object items, String store, String scope) {
    declareMemory(items, store, scope, "sdk");
  }

  /** Attach recalled memory items to the next request on this thread. */
  public static void declareMemory(Object items, String store, String scope, String source) {
    if (ShashuConfig.isDisabled()) {
      return;
    }
    var event = MemoryContextBuilder.newEvent(items, store, scope, source);
    if (event == null) {
      return;
    }
    RetrievalBus.pushMemoryEvent(event);
  }

  /** Same as {@link #declareMemory}, as a closeable scope that clears on close. */
  public static RetrievalScopeHandle memoryScope(Object items, String store) {
    return memoryScope(items, store, "agent", "sdk");
  }

  /** Same as {@link #declareMemory}, as a closeable scope that clears on close. */
  public static RetrievalScopeHandle memoryScope(Object items, String store, String scope) {
    return memoryScope(items, store, scope, "sdk");
  }

  /** Same as {@link #declareMemory}, as a closeable scope that clears on close. */
  public static RetrievalScopeHandle memoryScope(
      Object items, String store, String scope, String source) {
    if (ShashuConfig.isDisabled()) {
      return RetrievalBus.inertScope();
    }
    var event = MemoryContextBuilder.newEvent(items, store, scope, source);
    return RetrievalBus.openMemoryScope(event);
  }
}
