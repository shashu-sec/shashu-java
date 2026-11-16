package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.harness.RagContextSchema;
import ai.shashu.sdk.internal.RetrievalScopeHandle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the public retrieval surface to {@code contracts/sdk-surface.v1.md}.
 *
 * <p>The contract requires each SDK to carry a test asserting its exported names are exactly the
 * expected set, "so a new export fails the build. That test exists because a name did leak: Node
 * exported a third retrieval function, {@code isolateRetrieval}, whose customer-facing job {@code
 * retrievalScope} already covered." This is that test for Java.
 */
@DisplayName("The frozen public surface")
class SdkSurfaceTests {

  /**
   * The holder the contract names for Java: {@code ai.shashu.sdk.Retrieval}.
   *
   * <p>Resolved by name rather than with a class literal on purpose. The holder's name IS part of
   * the contract, so renaming it has to fail as a red test with a readable message, not merely as a
   * compile error in whichever file happens to mention the type first.
   */
  private static Class<?> holder() {
    try {
      return Class.forName("ai.shashu.sdk.Retrieval");
    } catch (ClassNotFoundException e) {
      throw new AssertionError(
          "The public type ai.shashu.sdk.Retrieval is not on the classpath. "
              + "contracts/sdk-surface.v1.md names it as Java's retrieval holder.",
          e);
    }
  }

  @Test
  @DisplayName("the contract's Java row names the holder and the form this SDK ships")
  void the_contract_row_and_the_code_agree() {
    // The spec row and the shipped form are asserted against each other so
    // neither can drift alone.
    String row =
        lines(RagContextSchema.directory().resolve("sdk-surface.v1.md")).stream()
            .filter(l -> l.trim().startsWith("| Java | static holder named"))
            .findFirst()
            .orElse(null);

    assertNotNull(row, "contracts/sdk-surface.v1.md has no Java qualifier row");
    assertTrue(row.contains("static holder named `" + holder().getSimpleName() + "`"), row);
    assertTrue(row.contains("Retrieval.declareRetrieval(docs, corpus)"), row);

    String form =
        lines(RagContextSchema.directory().resolve("sdk-surface.v1.md")).stream()
            .filter(l -> l.trim().startsWith("| Java | `try ("))
            .findFirst()
            .orElse(null);
    assertNotNull(form, "contracts/sdk-surface.v1.md has no Java form row");
    assertTrue(form.contains("Retrieval.retrievalScope(docs, corpus)"), form);
  }

  @Test
  @DisplayName("there are exactly two public members and no third")
  void exactly_two_public_retrieval_members() {
    var names = new TreeSet<String>();
    for (Method method : holder().getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
        assertTrue(
            Modifier.isStatic(method.getModifiers()),
            method.getName() + " must be static: the type is a holder, not a value");
        names.add(method.getName());
      }
    }
    assertEquals(List.of("declareRetrieval", "retrievalScope"), new ArrayList<>(names));
  }

  @Test
  @DisplayName("the holder exposes no public fields and no public nested type")
  void the_holder_carries_nothing_else() {
    assertEquals(
        0, Arrays.stream(holder().getFields()).count(), "a public constant is a third member");
    assertEquals(
        0,
        Arrays.stream(holder().getClasses()).count(),
        "a public nested type is a third member - which is why "
            + "RetrievalScopeHandle lives in the internal package");
    assertEquals(
        0,
        Arrays.stream(holder().getConstructors()).count(),
        "the holder must not be instantiable");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"declareRetrieval", "retrievalScope"})
  @DisplayName("argument order is the frozen one, and the overloads are the Java default form")
  void argument_order_is_frozen(String name) {
    // Java has no default arguments, so the contract's optional query and
    // source are overloads. The ORDER is what is frozen.
    List<Class<?>[]> signatures = new ArrayList<>();
    for (Method method : holder().getDeclaredMethods()) {
      if (method.getName().equals(name) && Modifier.isPublic(method.getModifiers())) {
        signatures.add(method.getParameterTypes());
      }
    }
    assertEquals(
        3,
        signatures.size(),
        name
            + " must offer (docs, corpus), (docs, corpus, query) and "
            + "(docs, corpus, query, source)");

    signatures.sort((a, b) -> Integer.compare(a.length, b.length));
    assertArrayTypes(signatures.get(0), Object.class, String.class);
    assertArrayTypes(signatures.get(1), Object.class, String.class, String.class);
    assertArrayTypes(signatures.get(2), Object.class, String.class, String.class, String.class);
  }

  @Test
  @DisplayName(
      "retrievalScope returns something try-with-resources accepts, without a checked close")
  void the_scope_handle_is_usable_in_try_with_resources() throws Exception {
    Method scope = holder().getMethod("retrievalScope", Object.class, String.class);
    assertTrue(AutoCloseable.class.isAssignableFrom(scope.getReturnType()));

    // A close() that declares a checked exception would force every
    // customer into a catch they have nothing to do with.
    assertEquals(0, RetrievalScopeHandle.class.getMethod("close").getExceptionTypes().length);
  }

  @Test
  @DisplayName("no public API anywhere hands back a default gateway address")
  void no_public_constant_carries_a_gateway_host() {
    // A constant carrying a gateway hostname is the same defect wherever
    // it lives, so the whole shipped source tree is swept rather than the
    // three call sites that used to hold it.
    List<String> offenders = new ArrayList<>();
    for (Path file : shippedSources()) {
      int number = 0;
      for (String line : lines(file)) {
        number++;
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("trustgateai.io") || lower.contains("trustgate.ai")) {
          offenders.add(file.getFileName() + ":" + number + " " + line.trim());
        }
      }
    }

    assertEquals(
        List.of(),
        offenders,
        "the SDK must not name a gateway host anywhere in shipped code, "
            + "javadoc and samples included");
  }

  @Test
  @DisplayName("the shipped sources are plain text: no stray control characters")
  void the_sources_contain_no_control_characters() {
    // A literal NUL reached RagContextBuilder.java during this change and
    // was invisible in every editor - but `grep` reports such a file as
    // "Binary file matches" and skips it, so the SDK would have dropped
    // silently out of the line-based fence directly above this one, and
    // out of every grep-based check in CI. Cheap to assert, expensive to
    // find by hand.
    List<String> offenders = new ArrayList<>();
    for (Path file : shippedSources()) {
      byte[] raw;
      try {
        raw = Files.readAllBytes(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      for (int i = 0; i < raw.length; i++) {
        int b = raw[i] & 0xFF;
        // Numeric, not character escapes: writing this line with
        // escapes is how the control characters got in.
        if (b < 0x20 && b != 0x0A && b != 0x0D && b != 0x09) {
          offenders.add(file.getFileName() + " byte " + i + " = 0x" + Integer.toHexString(b));
          break;
        }
      }
    }
    assertEquals(List.of(), offenders);
  }

  @Test
  @DisplayName("the bus, the joiner, the builder and the handle stay out of the public package")
  void the_internals_are_not_in_the_public_package() {
    // The contract: "Anything not on this page is internal and must not be
    // exported." Java has no module-info here, so the fence is the package
    // boundary this SDK already uses for ShashuApi.
    for (String name :
        List.of(
            "ai.shashu.sdk.internal.RetrievalBus",
            "ai.shashu.sdk.internal.RetrievalJoiner",
            "ai.shashu.sdk.internal.RagContextBuilder",
            "ai.shashu.sdk.internal.RetrievalEvent",
            "ai.shashu.sdk.internal.RagContextDoc",
            "ai.shashu.sdk.internal.RetrievalScopeHandle",
            "ai.shashu.sdk.internal.ShashuConfig",
            "ai.shashu.sdk.internal.MemoryContextBuilder",
            "ai.shashu.sdk.internal.MemoryEvent")) {
      try {
        assertEquals("ai.shashu.sdk.internal", Class.forName(name).getPackageName(), name);
      } catch (ClassNotFoundException e) {
        throw new AssertionError(name + " is missing", e);
      }
    }
  }

  @Test
  @DisplayName("the environment seam is not reachable from a customer's classpath")
  void the_env_seam_is_package_private() {
    // A mutable process-wide switch on a shipped public surface is a
    // defect, not a convenience. The tests reach it from the same package.
    assertFalse(
        Arrays.stream(ai.shashu.sdk.internal.ShashuConfig.class.getMethods())
            .anyMatch(m -> m.getName().equals("setEnv")));
  }

  @Test
  @DisplayName("the decision exposes exactly the reading members sdk-surface.v1.md names")
  void the_decision_reading_members_are_the_frozen_set() {
    // FILE-06 added the file / memory / route members. A new public method
    // on the decision is a change to the contract, so it fails here first.
    // `inspection` (2026-10-20) is parity with Python's and Node's existing
    // `inspection`; sdk-surface.v1.md is vendored byte-for-byte into those two
    // repos, so its table is not edited from this one.
    var names = new TreeSet<String>();
    for (Method m : ShashuDecision.class.getDeclaredMethods()) {
      if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
        names.add(m.getName());
      }
    }
    assertEquals(
        List.of(
            "attachments",
            "code",
            "decision",
            "filesBlocked",
            "filesUnverified",
            "id",
            "inspection",
            "memory",
            "memoryBlocked",
            "memoryCoverage",
            "memoryRecallsFlagged",
            "retryable",
            "routePolicy"),
        new ArrayList<>(names));
    String contract =
        String.join("\n", lines(RagContextSchema.directory().resolve("sdk-surface.v1.md")));
    for (String member :
        List.of(
            "attachments()",
            "filesUnverified()",
            "filesBlocked()",
            "memory()",
            "memoryBlocked()",
            "memoryRecallsFlagged()",
            "memoryCoverage()",
            "routePolicy()",
            "MemoryWriteBlocked")) {
      assertTrue(contract.contains(member), "contracts/sdk-surface.v1.md does not name " + member);
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "Attachments",
        "Attachment",
        "AttachmentFinding",
        "MemoryActivity",
        "MemoryItem",
        "RoutePolicy"
      })
  @DisplayName("the block types are public, read-only records")
  void the_block_types_are_public_records(String simple) throws ClassNotFoundException {
    Class<?> type = Class.forName("ai.shashu.sdk." + simple);
    assertTrue(Modifier.isPublic(type.getModifiers()), simple);
    assertTrue(type.isRecord(), simple + " must be a record: read-only by construction");
  }

  @Test
  @DisplayName(
      "MemoryWriteBlocked is a ShashuPolicyViolation, and AttachmentState keeps an UNKNOWN")
  void the_exception_and_the_state_enum() {
    assertTrue(ShashuPolicyViolation.class.isAssignableFrom(MemoryWriteBlocked.class));
    assertEquals(
        List.of("SCANNED", "SCANNED_PARTIAL", "NOT_INSPECTED", "BLOCKED", "UNKNOWN"),
        Arrays.stream(AttachmentState.values()).map(Enum::name).toList());
  }

  /** Every {@code .java} file this SDK actually ships. */
  private static List<Path> shippedSources() {
    Path main = RagContextSchema.directory().getParent().resolve("src").resolve("main");
    try (var files = Files.walk(main)) {
      return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void assertArrayTypes(Class<?>[] actual, Class<?>... expected) {
    assertEquals(Arrays.toString(expected), Arrays.toString(actual));
  }

  private static List<String> lines(Path path) {
    try {
      return Files.readAllLines(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
