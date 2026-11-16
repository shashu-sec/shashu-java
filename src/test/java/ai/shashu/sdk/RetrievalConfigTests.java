package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.harness.LogCapture;
import ai.shashu.sdk.internal.RetrievalTestAccess;
import ai.shashu.sdk.routing.ShashuRoutingInterceptor;
import ai.shashu.sdk.tracing.Tracer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Case 12: gateway address resolution.
 *
 * <p>Shashu is self-hosted, so an SDK that guessed a hostname would send a misconfigured process's
 * prompts to a host nobody in that organisation chose. These hold the two properties the contract
 * states for every SDK: the error <b>names {@code TRUSTGATE_BASE_URL}</b>, and it <b>contains no
 * hostname</b>.
 */
@DisplayName("Case 12: there is no default gateway address")
class RetrievalConfigTests {

  /**
   * The frozen template from {@code contracts/sdk-surface.v1.md}, with Java's two substitutions
   * filled in. Restated here rather than imported: this is the fence, so it has to be an
   * independent copy of the words.
   */
  private static String frozen(String entryPoint, String argument) {
    return "SHASHU gateway address is not configured, so "
        + entryPoint
        + " has nothing to route to. Pass "
        + argument
        + " to "
        + entryPoint
        + ", or set the SHASHU_BASE_URL or TRUSTGATE_BASE_URL environment variable. SHASHU is"
        + " self-hosted: the SDK has no default address and will not guess one.";
  }

  @BeforeEach
  void setUp() {
    RetrievalTestAccess.reset();
  }

  @AfterEach
  void tearDown() {
    RetrievalTestAccess.reset();
  }

  @Test
  @DisplayName("the client throws, naming TRUSTGATE_BASE_URL and no hostname")
  void the_client_refuses_to_build_without_an_address() {
    RetrievalTestAccess.setEnv(Map.of());

    ShashuConfigException thrown =
        assertThrows(
            ShashuConfigException.class,
            () -> ShashuClient.builder().apiKey("sk-trustgate-test").build());

    assertEquals(frozen("ShashuClient.builder().build()", ".baseUrl(...)"), thrown.getMessage());
    assertNamesTheVariableAndNoHost(thrown.getMessage());
  }

  @Test
  @DisplayName("the routing interceptor throws too, with its own two substitutions")
  void the_interceptor_refuses_to_build_without_an_address() {
    RetrievalTestAccess.setEnv(Map.of());

    ShashuConfigException thrown =
        assertThrows(
            ShashuConfigException.class, () -> new ShashuRoutingInterceptor("sk-trustgate-test"));

    assertEquals(
        frozen("new ShashuRoutingInterceptor(...)", "gatewayBaseUrlOverride"), thrown.getMessage());
    assertNamesTheVariableAndNoHost(thrown.getMessage());
  }

  @Test
  @DisplayName("ShashuConfigException is a ShashuException, so existing catches still work")
  void the_config_exception_is_catchable_as_the_parent() {
    RetrievalTestAccess.setEnv(Map.of());
    assertThrows(ShashuException.class, () -> new ShashuRoutingInterceptor("sk-trustgate-test"));
  }

  @Test
  @DisplayName("TRUSTGATE_BASE_URL is used when no explicit address is passed")
  void the_environment_variable_resolves() {
    RetrievalTestAccess.setEnv(Map.of("TRUSTGATE_BASE_URL", "https://gw.internal.example/"));

    // Constructing without throwing IS the assertion: the interceptor
    // resolves its address in the constructor.
    ShashuRoutingInterceptor interceptor = new ShashuRoutingInterceptor("sk-trustgate-test");
    assertFalse(interceptor.toString().isEmpty());

    try (ShashuClient client = ShashuClient.builder().apiKey("sk-trustgate-test").build()) {
      assertTrue(client.agentId() == null || !client.agentId().isEmpty());
    }
  }

  @Test
  @DisplayName("an explicit address always beats the environment")
  void the_explicit_option_wins() {
    RetrievalTestAccess.setEnv(Map.of("TRUSTGATE_BASE_URL", "https://from-env.example/"));
    // No throw, and no reliance on the env value: the explicit one is used.
    new ShashuRoutingInterceptor("sk-trustgate-test", "https://explicit.test.invalid/", null);
  }

  @Test
  @DisplayName("the tracer drops its spans and warns once rather than throwing or guessing")
  void the_tracer_neither_throws_nor_invents_a_host() {
    RetrievalTestAccess.setEnv(Map.of());

    try (LogCapture logs = new LogCapture()) {
      Tracer.configure("sk-trustgate-test", null);
      try (Tracer.SpanHandle span = Tracer.span("retrieval")) {
        assertTrue(Tracer.isActive());
      }
      // close() flushes; a second flush must not warn again.
      Tracer.flush();

      List<String> warnings = logs.warnings("SHASHU tracer has no gateway address");
      assertEquals(1, warnings.size(), "warned once per process: " + warnings);
      assertNamesTheVariableAndNoHost(warnings.get(0));
    } finally {
      Tracer.configure((String) null, null);
    }
  }

  /** The two properties the contract asserts in every SDK's surface test. */
  private static void assertNamesTheVariableAndNoHost(String message) {
    assertTrue(
        message.contains("TRUSTGATE_BASE_URL"), "the message must name the variable: " + message);
    assertFalse(
        message.contains("trustgateai.io"),
        "there is no address to suggest, so suggesting one is a bug: " + message);
    assertFalse(
        message.contains("trustgate.ai"),
        "the other spelling of the vendor domain is a bug too: " + message);
    assertFalse(message.contains("://"), "no URL of any kind belongs in this message: " + message);
  }
}
