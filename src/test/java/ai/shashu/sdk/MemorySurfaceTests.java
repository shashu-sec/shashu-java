package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.harness.RagContextSchema;
import ai.shashu.sdk.internal.MemoryContextBuilder;
import ai.shashu.sdk.internal.RetrievalScopeHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The frozen public memory surface")
class MemorySurfaceTests {

  @Test
  @DisplayName("there are exactly two public members and no third")
  void exactly_two_public_memory_members() throws ClassNotFoundException {
    Class<?> holder = Class.forName("ai.shashu.sdk.Memory");
    var names = new TreeSet<String>();
    for (Method method : holder.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
        assertTrue(Modifier.isStatic(method.getModifiers()), method.getName());
        names.add(method.getName());
      }
    }
    assertEquals(List.of("declareMemory", "memoryScope"), new ArrayList<>(names));
  }

  @Test
  @DisplayName("the contract's Java memory row names this holder")
  void the_contract_row_and_the_code_agree() throws Exception {
    String row =
        java.nio.file.Files.readAllLines(RagContextSchema.directory().resolve("sdk-surface.v1.md"))
            .stream()
            .filter(l -> l.contains("Memory.declareMemory(items, store)"))
            .findFirst()
            .orElse(null);
    assertNotNull(row, "contracts/sdk-surface.v1.md has no Java Memory.declareMemory row");
    assertTrue(row.contains("static holder named `Memory`"), row);

    String form =
        java.nio.file.Files.readAllLines(RagContextSchema.directory().resolve("sdk-surface.v1.md"))
            .stream()
            .filter(l -> l.contains("Memory.memoryScope(items, store)"))
            .findFirst()
            .orElse(null);
    assertNotNull(form, "contracts/sdk-surface.v1.md has no Java Memory.memoryScope form");
  }

  @Test
  @DisplayName("memoryScope is AutoCloseable like retrievalScope")
  void memory_scope_is_the_unit_of_work() {
    assertTrue(AutoCloseable.class.isAssignableFrom(RetrievalScopeHandle.class));
    try (var scope =
        Memory.memoryScope(List.of(Map.of("id", "m1", "preview", "note one")), "agent_notes")) {
      assertNotNull(scope);
    }
  }

  @Test
  @DisplayName("empty items are a no-op even with a blank store (Python/Node match)")
  void empty_items_do_not_validate_store() {
    assertDoesNotThrow(() -> Memory.declareMemory(List.of(), ""));
    assertDoesNotThrow(() -> Memory.declareMemory(null, null));
  }

  @Test
  @DisplayName("a blank store with a real item is a call-site error")
  void blank_store_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Memory.declareMemory(List.of(Map.of("id", "m1", "preview", "note")), ""));
  }

  @Test
  @DisplayName("scope and source must be memory_context.v1 enums")
  void enums_are_the_schema() {
    var item = List.of(Map.of("id", "m1", "preview", "note"));
    assertThrows(
        IllegalArgumentException.class, () -> Memory.declareMemory(item, "agent_notes", "session"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Memory.declareMemory(item, "agent_notes", "agent", "vectorstore"));
    Memory.declareMemory(item, "agent_notes", "crew", "tool");
    Memory.declareMemory(item, "agent_notes", "tenant", "hosted");
  }

  @Test
  @DisplayName("folded context is memory_context.v1: v=1, store, scope, source, items")
  void folded_context_matches_schema_version() {
    var event =
        MemoryContextBuilder.newEvent(
            List.of(Map.of("id", "m1", "preview", "alpha"), Map.of("id", "m1", "preview", "alpha")),
            "agent_notes",
            "agent",
            "sdk");
    var ctx = MemoryContextBuilder.build(List.of(event));
    assertEquals(1, ctx.get("v"));
    assertEquals("agent_notes", ctx.get("store"));
    assertEquals("agent", ctx.get("scope"));
    assertEquals("sdk", ctx.get("source"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) ctx.get("items");
    assertEquals(1, items.size());
    assertEquals("m1", items.get(0).get("id"));
    assertEquals("alpha", items.get(0).get("preview"));
    assertNull(MemoryContextBuilder.build(List.of()));
    assertNull(MemoryContextBuilder.newEvent(List.of(), "store", "agent", "sdk"));
  }
}
