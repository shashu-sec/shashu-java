package ai.shashu.sdk.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a customer's memory items into {@code memory_context.v1} and folds carried declarations
 * into the one block the joiner attaches.
 */
public final class MemoryContextBuilder {

  private MemoryContextBuilder() {}

  public static final List<String> SOURCES =
      Collections.unmodifiableList(
          Arrays.asList(
              "sdk",
              "manual",
              "langchain",
              "llamaindex",
              "tool",
              "otel",
              "n8n",
              "provider",
              "hosted",
              "other"));

  public static final List<String> SCOPES =
      Collections.unmodifiableList(Arrays.asList("agent", "crew", "tenant"));

  /**
   * {@code null} when nothing readable survived, matching Python/Node: an empty item list is a
   * no-op and does not validate {@code store}.
   */
  public static MemoryEvent newEvent(Object items, String store, String scope, String source) {
    var coerced = RagContextBuilder.coerceDocs(items);
    if (coerced.isEmpty()) {
      return null;
    }
    return new MemoryEvent(
        requireStore(store), requireScope(scope), requireSource(source), coerced);
  }

  static String requireStore(String store) {
    if (store == null || store.isBlank()) {
      throw new IllegalArgumentException(
          "declareMemory needs a non-empty store: it is what the gateway"
              + " records the items as having come from, and memory_context.v1"
              + " requires it.");
    }
    String trimmed = store.trim();
    return trimmed.length() > RetrievalBus.MAX_CORPUS_CHARS
        ? trimmed.substring(0, RetrievalBus.MAX_CORPUS_CHARS)
        : trimmed;
  }

  static String requireScope(String scope) {
    String value = (scope == null || scope.isBlank()) ? "agent" : scope.trim();
    if (!SCOPES.contains(value)) {
      throw new IllegalArgumentException(
          "declareMemory scope='"
              + scope
              + "' is not a memory_context.v1"
              + " value. Use one of: "
              + String.join(", ", SCOPES)
              + ".");
    }
    return value;
  }

  static String requireSource(String source) {
    if (source == null || !SOURCES.contains(source)) {
      throw new IllegalArgumentException(
          "declareMemory source='"
              + source
              + "' is not a memory_context.v1"
              + " value. Use one of: "
              + String.join(", ", SOURCES)
              + ".");
    }
    return source;
  }

  public static Map<String, Object> build(List<MemoryEvent> events) {
    if (events == null || events.isEmpty()) {
      return null;
    }
    List<RagContextDoc> items = new ArrayList<>();
    Set<String> stores = new LinkedHashSet<>();
    Set<String> sources = new LinkedHashSet<>();
    String scope = "agent";
    Set<String> seen = new LinkedHashSet<>();
    for (MemoryEvent event : events) {
      stores.add(event.store());
      sources.add(event.source());
      scope = event.scope();
      for (RagContextDoc item : event.items()) {
        String key = item.id() + "\0" + item.preview();
        if (!seen.add(key)) {
          continue;
        }
        items.add(item);
      }
    }
    if (items.isEmpty()) {
      return null;
    }
    if (items.size() > RetrievalBus.MAX_DOCS) {
      items = items.subList(items.size() - RetrievalBus.MAX_DOCS, items.size());
    }
    String store = String.join("+", stores);
    if (store.length() > RetrievalBus.MAX_CORPUS_CHARS) {
      store = store.substring(0, RetrievalBus.MAX_CORPUS_CHARS);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("v", 1);
    context.put("store", store);
    context.put("scope", scope);
    context.put("source", sources.size() == 1 ? sources.iterator().next() : "other");
    List<Map<String, Object>> wire = new ArrayList<>(items.size());
    for (RagContextDoc item : items) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", item.id());
      row.put("preview", item.preview());
      wire.add(row);
    }
    context.put("items", wire);
    return context;
  }
}
