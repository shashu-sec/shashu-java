package ai.shashu.sdk.internal;

import java.util.Collections;
import java.util.List;

/**
 * One memory declaration, already coerced to the wire shape. Visible across the {@code
 * ai.shashu.sdk} packages but not part of the public API.
 */
public final class MemoryEvent {

  private final String store;
  private final String scope;
  private final String source;
  private final List<RagContextDoc> items;

  MemoryEvent(String store, String scope, String source, List<RagContextDoc> items) {
    this.store = store;
    this.scope = scope;
    this.source = source;
    this.items = Collections.unmodifiableList(items);
  }

  public String store() {
    return store;
  }

  public String scope() {
    return scope;
  }

  public String source() {
    return source;
  }

  public List<RagContextDoc> items() {
    return items;
  }

  MemoryEvent withItems(List<RagContextDoc> replacement) {
    return new MemoryEvent(store, scope, source, replacement);
  }
}
