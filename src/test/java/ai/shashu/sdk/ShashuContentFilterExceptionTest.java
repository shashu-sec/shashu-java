package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.*;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The five error-dispatch properties, pinned.
 *
 * <p><b>The defect.</b> Under the gateway's {@code explicit} block encoding (the default at
 * contract version {@code 2026-09-01}) a DLP, jailbreak or prompt-injection block arrives as
 * <b>HTTP 400</b> with {@code error.type "content_filter"}. This SDK reported it as a bare {@link
 * ShashuException} reading "Shashu request failed with HTTP 400" — indistinguishable from a
 * malformed request. A caller with correct code was sent to fix a payload that was never the
 * problem, and the block never reached whatever handles blocks.
 *
 * <p><b>The other four</b> are not new behaviour and are asserted anyway: they are the fail-closed
 * properties of {@link ShashuException#fromResponse}, which had no test of its own, and they are
 * what stops the fix above from being widened into a swallow. (a) is the fix; (b) an unrecognised
 * code on a 403 stays a policy violation; (c) an unrecognised code on a 400 stays a client error;
 * (d) an absent code dispatches on the transport status; (e) a numeric-looking code is never read
 * as an HTTP status.
 *
 * <p><b>Body provenance.</b> The 403 body is the contract-version shape of the capture in {@link
 * ShashuPolicyViolationTest}. The 400 content-block bodies were produced on 2026-09-07 by driving
 * {@code app.contract.responses.build_block} in-process from the trustgate-ai checkout — the same
 * function the gateway's {@code /v1} hot path calls to serialise a block. Nothing here is
 * hand-written, and the difference in provenance is stated rather than blurred: an in-process
 * capture proves what the builder emits, not that the hot path reaches it.
 */
class ShashuContentFilterExceptionTest {

  /** The {@code explicit} encoding, {@code jailbreak} finding. HTTP 400. */
  private static final String BLOCK_400_JAILBREAK =
      "{\"error\":{\"message\":\"I cannot fulfill this request because it violates the "
          + "organization's security policy.\",\"type\":\"content_filter\",\"param\":null,"
          + "\"code\":\"jailbreak\",\"shashu\":{\"decision\":\"block\",\"retryable\":false,"
          + "\"id\":\"9f4c1c2e-6b3d-4a51-9c0e-2a7f8b1d4e30\",\"code\":\"jailbreak\"}},"
          + "\"choices\":[]}";

  /** The same encoding, {@code dlp} finding — the code varies, the type does not. */
  private static final String BLOCK_400_DLP =
      "{\"error\":{\"message\":\"I cannot fulfill this request because it violates the "
          + "organization's security policy.\",\"type\":\"content_filter\",\"param\":null,"
          + "\"code\":\"dlp\",\"shashu\":{\"decision\":\"block\",\"retryable\":false,"
          + "\"id\":\"9f4c1c2e-6b3d-4a51-9c0e-2a7f8b1d4e30\",\"code\":\"dlp\"}},\"choices\":[]}";

  /**
   * A policy refusal on the contract version. {@code error.code} is a §3 REASON, not a status — the
   * case that must still land on {@link ShashuPolicyViolation}.
   */
  private static final String POLICY_403_EGRESS_DENIED =
      "{\"error\":{\"message\":\"A2A cross-tenant call blocked: the calling agent belongs to "
          + "a different tenant and cross-tenant calls are not allowed.\","
          + "\"type\":\"permission_error\",\"param\":null,\"code\":\"egress_denied\"},"
          + "\"choices\":[]}";

  /**
   * A schema-invalid body on the LLM wire. {@code error.code} is {@code "invalid_request"} —
   * neither a §3 reason nor a status, the third thing the field can hold.
   */
  private static final String SCHEMA_INVALID_400 =
      "{\"error\":{\"message\":\"The request body is not valid for this endpoint.\","
          + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_request\"},"
          + "\"choices\":[],\"detail\":[]}";

  /** The 500 crash handler: {@code error} is a STRING, so there is no error.code. */
  private static final String CRASH_500 =
      "{\"error\":\"Internal Server Error\",\"detail\":\"Internal error\","
          + "\"request_id\":\"dfeffdc1-aedd-4759-b160-dd85fd4cb0a0\"}";

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
        .apiKey("sk-shashu-test")
        .baseUrl(server.url("/").toString())
        .maxRetries(0)
        .build();
  }

  private ShashuException post(int status, String body) {
    server.enqueue(new MockResponse().setResponseCode(status).setBody(body));
    try (ShashuClient client = newClient()) {
      return assertThrows(
          ShashuException.class,
          () ->
              client
                  .chat()
                  .completions()
                  .create(
                      ChatCompletionRequest.builder()
                          .model("gpt-4o")
                          .addMessage(ChatMessage.user("hi"))
                          .build()));
    }
  }

  // --- (a) THE defect ---------------------------------------------------- //

  @Test
  void a_400_content_filter_is_a_content_filter_not_a_generic_client_error() {
    ShashuException ex = post(400, BLOCK_400_JAILBREAK);

    assertInstanceOf(ShashuContentFilterException.class, ex);
    ShashuContentFilterException block = (ShashuContentFilterException) ex;
    assertEquals(400, block.statusCode());
    assertEquals("content_filter", block.errorType());
    // The finding survives to the caller — the field that says WHICH
    // detector refused, and the reason error.code is no longer a status.
    assertEquals("jailbreak", block.code());
    // The refusal sentence, not "Shashu request failed with HTTP 400".
    assertEquals(
        "I cannot fulfill this request because it violates the organization's security policy.",
        block.getMessage());
  }

  @Test
  void a_every_content_finding_lands_on_the_same_type_not_just_jailbreak() {
    ShashuException ex = post(400, BLOCK_400_DLP);

    assertInstanceOf(ShashuContentFilterException.class, ex);
    assertEquals("dlp", ((ShashuContentFilterException) ex).code());
  }

  @Test
  void a_content_filter_block_is_still_catchable_as_the_parent() {
    // A customer writing `catch (ShashuException ex)` for generic
    // handling must keep working.
    assertInstanceOf(ShashuException.class, post(400, BLOCK_400_JAILBREAK));
  }

  // --- (b) fail closed on an unrecognised 403 code ----------------------- //

  @Test
  void b_403_with_an_unrecognised_code_is_still_a_policy_violation() {
    ShashuException ex = post(403, POLICY_403_EGRESS_DENIED);

    assertInstanceOf(ShashuPolicyViolation.class, ex);
    assertEquals(403, ex.statusCode());
    // `egress_denied` is a §3 reason. Nothing may read it as a status, and
    // the 403 branch must not become reachable only when the code is "403".
    assertEquals("permission_error", ((ShashuPolicyViolation) ex).errorType());
  }

  // --- (c) an unrecognised 400 code is not swallowed --------------------- //

  @Test
  void c_400_with_an_unrecognised_code_stays_a_client_error() {
    ShashuException ex = post(400, SCHEMA_INVALID_400);

    // Emphatically NOT a content filter: this really is a malformed body,
    // and reporting it as a block would be the mirror image of the defect
    // this file exists to close.
    assertFalse(
        ex instanceof ShashuContentFilterException,
        "a schema-invalid body must not be reported as a content block");
    assertEquals(400, ex.statusCode());
  }

  @Test
  void c_400_with_an_unparseable_body_stays_a_client_error() {
    ShashuException ex = post(400, "not json at all");

    assertFalse(ex instanceof ShashuContentFilterException);
    assertEquals(400, ex.statusCode());
  }

  // --- (d) an absent code dispatches on the transport status ------------- //

  @Test
  void d_a_code_that_is_absent_entirely_dispatches_on_the_transport_status() {
    // The 500 crash handler sends `error` as a bare STRING — no
    // error.code, no error.message.
    ShashuException ex = post(500, CRASH_500);

    assertEquals(500, ex.statusCode());
    assertFalse(ex instanceof ShashuPolicyViolation);
    assertFalse(ex instanceof ShashuContentFilterException);
    assertEquals(ShashuException.class, ex.getClass());
  }

  // --- (e) a numeric-looking code is never read as a status -------------- //

  @Test
  void e_a_numeric_looking_code_is_not_dispatched_as_an_http_status() {
    // No §3 code is numeric today, so this is a guard rather than a bug
    // report — but it is the guard that keeps dispatch on the transport
    // status structural. A parser that read `error.code` leniently would
    // turn "4_0_3" or "403_policy" on a 400 into a policy violation nobody
    // raised; this SDK never reads the field for routing, and that is
    // asserted here rather than left to be re-derived.
    for (String numericLooking : new String[] {"4_0_3", "403_policy", "403", "0403"}) {
      String body =
          BLOCK_400_JAILBREAK.replace(
              "\"code\":\"jailbreak\"", "\"code\":\"" + numericLooking + "\"");
      ShashuException ex = post(400, body);

      assertInstanceOf(
          ShashuContentFilterException.class,
          ex,
          "code=" + numericLooking + " must not move a 400 off its own status");
      assertEquals(400, ex.statusCode(), "code=" + numericLooking);
    }
  }
}
