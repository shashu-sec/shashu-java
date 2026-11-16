package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.*;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Verifies 403 responses surface as the typed {@link ShashuPolicyViolation} with the gateway's
 * {@code error.message} parsed and exposed via {@link Exception#getMessage()}.
 *
 * <p><b>Every body in this file is a verbatim capture from a live gateway (2026-09-01, {@code
 * sdks/e2e/capture_error_contract.py}).</b> That is a rule, not a courtesy: this suite drives
 * {@code MockWebServer}, so it returns whatever it is handed and will pass against a body the
 * gateway has never sent. It did exactly that for months — it asserted {@code error.type ==
 * "policy_violation"}, a value no gateway code path produces, and stayed green the whole time.
 * Re-capture before changing a body here; do not hand-write one.
 */
class ShashuPolicyViolationTest {

  /**
   * Captured: {@code POST /v1/chat/completions}, model outside the agent's allowed routes. Note
   * {@code type} is {@code invalid_request_error} — the same value a 401 and a 422 carry — and
   * {@code choices: []} is present so naive OpenAI clients do not crash mapping over the error
   * path.
   */
  private static final String ACCESS_DENIED_403 =
      "{\"error\":{\"message\":\"Access Denied: Model 'claude-3-opus-20240229' is not in the "
          + "allowed routes for this agent.\",\"type\":\"invalid_request_error\",\"param\":null,"
          + "\"code\":\"403\"},\"choices\":[]}";

  /**
   * Captured: the same decision on {@code /v1/messages} with {@code
   * TRUSTGATE_WIRE_SHAPE_MESSAGES=enforce}. The per-wire adapter rewrites rendered error envelopes
   * into Anthropic's shape.
   */
  private static final String ACCESS_DENIED_403_MESSAGES_WIRE =
      "{\"type\":\"error\",\"error\":{\"type\":\"permission_error\",\"message\":\"Access Denied: "
          + "Model 'claude-3-opus-20240229' is not in the allowed routes for this agent.\"}}";

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  private ShashuClient newClient() {
    return ShashuClient.builder()
        .apiKey("sk-trustgate-test")
        .baseUrl(server.url("/").toString())
        .maxRetries(0)
        .build();
  }

  private ShashuPolicyViolation post403(String body) {
    server.enqueue(new MockResponse().setResponseCode(403).setBody(body));
    try (ShashuClient client = newClient()) {
      return assertThrows(
          ShashuPolicyViolation.class,
          () ->
              client
                  .chat()
                  .completions()
                  .create(
                      ChatCompletionRequest.builder()
                          .model("claude-3-opus-20240229")
                          .addMessage(ChatMessage.user("hi"))
                          .build()));
    }
  }

  @Test
  void access_denial_403_throws_ShashuPolicyViolation_with_parsed_message() {
    ShashuPolicyViolation ex = post403(ACCESS_DENIED_403);

    assertEquals(403, ex.statusCode());
    assertEquals(
        "Access Denied: Model 'claude-3-opus-20240229' is not in the allowed routes for this agent.",
        ex.getMessage());
    assertEquals(ACCESS_DENIED_403, ex.responseBody());
  }

  @Test
  void error_type_on_a_gateway_403_is_invalid_request_error_not_a_policy_discriminator() {
    // Pinning the value the gateway ACTUALLY sends. `invalid_request_error`
    // is what every gateway-raised 4xx carries, which is precisely why
    // callers must branch on statusCode() and not on errorType().
    assertEquals("invalid_request_error", post403(ACCESS_DENIED_403).errorType());
  }

  @Test
  void messages_wire_403_is_anthropic_shaped_and_still_parses() {
    ShashuPolicyViolation ex = post403(ACCESS_DENIED_403_MESSAGES_WIRE);

    assertEquals(
        "Access Denied: Model 'claude-3-opus-20240229' is not in the allowed routes for this agent.",
        ex.getMessage());
    assertEquals("permission_error", ex.errorType());
  }

  @Test
  void catch_trustgate_exception_parent_also_works() {
    // Customer might write `catch (ShashuException ex)` for generic
    // handling; ShashuPolicyViolation must still satisfy that catch.
    server.enqueue(new MockResponse().setResponseCode(403).setBody(ACCESS_DENIED_403));

    try (ShashuClient client = newClient()) {
      ShashuException ex =
          assertThrows(
              ShashuException.class,
              () ->
                  client
                      .chat()
                      .completions()
                      .create(
                          ChatCompletionRequest.builder()
                              .model("claude-3-opus-20240229")
                              .addMessage(ChatMessage.user("hi"))
                              .build()));

      assertInstanceOf(ShashuPolicyViolation.class, ex);
    }
  }

  @Test
  void unparseable_body_uses_default_message() {
    // Defensive parser coverage, NOT a gateway contract: the gateway has
    // never served a non-JSON body on this path. A reverse proxy in front
    // of it can, which is the only reason this branch exists.
    ShashuPolicyViolation ex = post403("<html>plain text not json</html>");

    assertEquals("Shashu policy violation (403)", ex.getMessage());
    assertTrue(ex.responseBody().contains("plain text"));
  }

  @Test
  void bare_top_level_message_falls_back_correctly() {
    // Also defensive parser coverage. This file used to describe
    // {"message": "..."} as what "some legacy gateway versions return".
    // No released gateway has ever returned it — the envelope has carried
    // `error.message` since the OpenAI-compatible error shape landed.
    ShashuPolicyViolation ex = post403("{\"message\":\"blocked by DLP\"}");

    assertEquals("blocked by DLP", ex.getMessage());
  }

  @Test
  void provider_5xx_throws_generic_ShashuException_not_PolicyViolation() {
    // Captured shape of an upstream failure: the gateway wraps the provider
    // error in the OpenAI envelope and puts the provider-error class name
    // in `type`, with the message prefixed "TrustGate Provider Error:".
    server.enqueue(
        new MockResponse()
            .setResponseCode(502)
            .setBody(
                "{\"error\":{\"message\":\"TrustGate Provider Error: upstream provider down\","
                    + "\"type\":\"ProviderServerError\",\"param\":null,\"code\":\"502\"},\"choices\":[]}"));

    try (ShashuClient client = newClient()) {
      ShashuException ex =
          assertThrows(
              ShashuException.class,
              () ->
                  client
                      .chat()
                      .completions()
                      .create(
                          ChatCompletionRequest.builder()
                              .model("gpt-4o")
                              .addMessage(ChatMessage.user("x"))
                              .build()));

      // Must NOT be the policy-violation subclass.
      assertFalse(
          ex instanceof ShashuPolicyViolation,
          "5xx responses should be base ShashuException, not ShashuPolicyViolation");
      assertEquals(502, ex.statusCode());
    }
  }

  @Test
  @Disabled(
      "Asserts an UNIMPLEMENTED contract: TRUSTGATE-403-DISCRIMINATOR "
          + "(https://github.com/aisquad001/trustgate-ai/issues/736). "
          + "The gateway has never emitted error.type=\"policy_violation\" — every 4xx it raises "
          + "itself carries \"invalid_request_error\" (proxy/helpers.py::_openai_error_response and "
          + "main.py::http_exception_handler). This test passed only because MockWebServer served "
          + "the body the test itself wrote. Re-enable when the gateway ships a real 403 "
          + "discriminator, and not before.")
  void future_gateway_403_carries_a_policy_discriminator_in_error_type() {
    String body =
        "{\"error\":{\"message\":\"Tool call rejected: prompt-injection pattern matched\","
            + "\"type\":\"policy_violation\"}}";
    server.enqueue(new MockResponse().setResponseCode(403).setBody(body));

    try (ShashuClient client = newClient()) {
      ShashuPolicyViolation ex =
          assertThrows(
              ShashuPolicyViolation.class,
              () ->
                  client
                      .chat()
                      .completions()
                      .create(
                          ChatCompletionRequest.builder()
                              .model("gpt-4o")
                              .addMessage(ChatMessage.user("ignore previous instructions"))
                              .build()));

      assertEquals("policy_violation", ex.errorType());
    }
  }
}
