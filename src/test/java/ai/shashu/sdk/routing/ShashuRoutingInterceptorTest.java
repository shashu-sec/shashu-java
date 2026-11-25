package ai.shashu.sdk.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Hermetic tests for the routing interceptor. We use a MockWebServer as the "gateway"; the
 * interceptor rewrites traffic destined for api.openai.com / api.anthropic.com /
 * generativelanguage.googleapis.com to land on the mock server, where we inspect the rewritten
 * request.
 *
 * <p>The trick: we point the interceptor's gatewayBaseUrl at the MockWebServer URL, then send a
 * request whose ORIGINAL URL is one of the provider hosts. The interceptor rewrites the URL to the
 * mock, which is what we then verify.
 */
class ShashuRoutingInterceptorTest {

  private MockWebServer gateway;

  @BeforeEach
  void setUp() throws IOException {
    gateway = new MockWebServer();
    gateway.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    gateway.shutdown();
  }

  private OkHttpClient buildClient() {
    return new OkHttpClient.Builder()
        .addInterceptor(
            new ShashuRoutingInterceptor("sk-trustgate-fake", gateway.url("/").toString(), null))
        .build();
  }

  @Test
  void openai_request_is_rerouted_to_gateway_with_Shashu_bearer() throws Exception {
    gateway.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    OkHttpClient http = buildClient();
    Request req =
        new Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer sk-real-openai-key")
            .post(RequestBody.create("{\"model\":\"gpt-4o\"}", MediaType.parse("application/json")))
            .build();

    http.newCall(req).execute().close();

    RecordedRequest seen = gateway.takeRequest();
    // Host is the mock server; path preserved as /v1/chat/completions.
    assertEquals("/v1/chat/completions", seen.getPath());
    assertEquals("Bearer sk-trustgate-fake", seen.getHeader("Authorization"));
    // Vendor key dropped:
    assertNotEquals("Bearer sk-real-openai-key", seen.getHeader("Authorization"));
  }

  @Test
  void anthropic_request_is_rerouted_with_xapikey_dropped() throws Exception {
    gateway.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    OkHttpClient http = buildClient();
    Request req =
        new Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", "sk-ant-fake")
            .header("anthropic-version", "2023-06-01")
            .post(
                RequestBody.create(
                    "{\"model\":\"claude-sonnet-4-6\"}", MediaType.parse("application/json")))
            .build();

    http.newCall(req).execute().close();

    RecordedRequest seen = gateway.takeRequest();
    assertEquals("/v1/messages", seen.getPath());
    assertEquals("Bearer sk-trustgate-fake", seen.getHeader("Authorization"));
    // Vendor-specific auth headers stripped — gateway never forwards them.
    assertNull(seen.getHeader("x-api-key"));
    assertNull(seen.getHeader("anthropic-version"));
  }

  @Test
  void google_request_is_remapped_to_OpenAI_compatible_path() throws Exception {
    gateway.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    OkHttpClient http = buildClient();
    Request req =
        new Request.Builder()
            .url(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent")
            .header("x-goog-api-key", "AIza-fake")
            .post(RequestBody.create("{\"contents\":[]}", MediaType.parse("application/json")))
            .build();

    http.newCall(req).execute().close();

    RecordedRequest seen = gateway.takeRequest();
    // Google's vendor path gets mapped to the gateway's OpenAI-compat
    // endpoint; gateway has a Google-shape adapter that reshapes the body.
    assertEquals("/v1/chat/completions", seen.getPath());
    assertEquals("Bearer sk-trustgate-fake", seen.getHeader("Authorization"));
    assertNull(seen.getHeader("x-goog-api-key"));
  }

  @Test
  void non_provider_host_passes_through_untouched() throws Exception {
    // For this test we need a different MockWebServer instance — the
    // interceptor must NOT rewrite the URL for non-provider hosts.
    // We send to the same MockWebServer (acting as a non-provider
    // host) and verify the URL was preserved.
    gateway.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    // Build a client with the interceptor + a separate server URL we
    // can verify the request landed on. The mock IS the destination
    // because the original URL is pointed at it directly.
    OkHttpClient http =
        new OkHttpClient.Builder()
            .addInterceptor(
                new ShashuRoutingInterceptor(
                    "sk-trustgate-fake",
                    "https://gateway.test.invalid/", // gateway URL the interceptor would rewrite TO
                    null))
            .build();

    Request req =
        new Request.Builder()
            .url(gateway.url("/users/me").newBuilder().build()) // direct request to mock
            .header("X-Custom-Auth", "secret-token-123")
            .get()
            .build();

    http.newCall(req).execute().close();

    RecordedRequest seen = gateway.takeRequest();
    // Path preserved (not rewritten).
    assertEquals("/users/me", seen.getPath());
    // Custom auth header preserved.
    assertEquals("secret-token-123", seen.getHeader("X-Custom-Auth"));
    // No Shashu Authorization injected (not a provider host).
    assertNull(seen.getHeader("Authorization"));
  }

  @Test
  void query_string_is_preserved_through_rewrite() throws Exception {
    gateway.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    OkHttpClient http = buildClient();
    Request req =
        new Request.Builder()
            .url("https://api.openai.com/v1/models?limit=10&order=desc")
            .get()
            .build();

    http.newCall(req).execute().close();

    RecordedRequest seen = gateway.takeRequest();
    assertEquals("/v1/models", seen.getRequestUrl().encodedPath());
    assertEquals("limit=10&order=desc", seen.getRequestUrl().encodedQuery());
  }

  @Test
  void user_agent_is_appended_for_telemetry_attribution() throws Exception {
    gateway.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    OkHttpClient http = buildClient();
    Request req =
        new Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("User-Agent", "OpenAI/1.0")
            .post(RequestBody.create("{}", MediaType.parse("application/json")))
            .build();

    http.newCall(req).execute().close();

    RecordedRequest seen = gateway.takeRequest();
    String ua = seen.getHeader("User-Agent");
    assertNotNull(ua);
    assertTrue(ua.contains("OpenAI/1.0"), "Original UA preserved: " + ua);
    assertTrue(ua.contains("shashu-java/"), "Shashu UA appended: " + ua);
  }

  // The contract version Python's patched provider clients send too.
  private RecordedRequest routeOne(OkHttpClient http, String presetVersion) throws Exception {
    gateway.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    Request.Builder req =
        new Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(
                RequestBody.create("{\"model\":\"gpt-4o\"}", MediaType.parse("application/json")));
    if (presetVersion != null) {
      req.header("shashu-version", presetVersion);
    }
    http.newCall(req.build()).execute().close();
    return gateway.takeRequest();
  }

  @Test
  void routed_request_carries_the_default_contract_version() throws Exception {
    RecordedRequest seen = routeOne(buildClient(), null);
    assertEquals("2026-09-01", seen.getHeader("shashu-version"));
    assertNull(seen.getHeader("shashu-block-encoding"));
  }

  @Test
  void routed_request_keeps_a_version_the_caller_already_set() throws Exception {
    assertEquals("2026-10-01", routeOne(buildClient(), "2026-10-01").getHeader("shashu-version"));
  }

  @Test
  void routed_request_opt_out_sends_no_version() throws Exception {
    OkHttpClient http =
        new OkHttpClient.Builder()
            .addInterceptor(
                GatewayRouting.asInterceptor(
                    "sk-trustgate-fake", gateway.url("/").toString(), null, null))
            .build();
    assertNull(routeOne(http, null).getHeader("shashu-version"));
  }

  @Test
  void routed_request_pinned_version_via_facade() throws Exception {
    OkHttpClient http =
        GatewayRouting.newOkHttpClient(
            "sk-trustgate-fake", gateway.url("/").toString(), null, "2026-10-01");
    assertEquals("2026-10-01", routeOne(http, null).getHeader("shashu-version"));
  }

  @Test
  void provider_host_check_is_case_insensitive() {
    assertTrue(
        ShashuRoutingInterceptor.isProviderHost(
            HttpUrl.parse("https://API.OpenAI.COM/v1/chat/completions")));
    assertTrue(
        ShashuRoutingInterceptor.isProviderHost(
            HttpUrl.parse("https://api.anthropic.com/v1/messages")));
    assertFalse(ShashuRoutingInterceptor.isProviderHost(HttpUrl.parse("https://example.com/")));
  }

  @Test
  void facade_throws_when_api_key_missing() {
    // Skip if SHASHU_API_KEY env happens to be set — see the
    // Java SDK builder test for the same pattern.
    String envKey = System.getenv("SHASHU_API_KEY");
    String shashuKey = System.getenv("SHASHU_API_KEY");
    if ((envKey != null && !envKey.isBlank()) || (shashuKey != null && !shashuKey.isBlank())) {
      return;
    }

    ai.shashu.sdk.ShashuConfigException ex =
        assertThrows(
            ai.shashu.sdk.ShashuConfigException.class,
            () -> GatewayRouting.asInterceptor((String) null));
    assertEquals(ai.shashu.sdk.internal.CredentialResolver.BOTH_OR_NEITHER, ex.getMessage());
  }
}
