package ai.shashu.sdk.patching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
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
 * The {@code ai.shashu.sdk.patching} package is deprecated in favour of {@code
 * ai.shashu.sdk.routing}, but it is still public API and someone is on it. These tests hold the
 * aliases to routing behaviour, not merely to compiling.
 */
@SuppressWarnings("deprecation")
class DeprecatedPatchingAliasTest {

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

  @Test
  void deprecated_ProviderPatch_still_routes_to_the_gateway() throws Exception {
    gateway.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    OkHttpClient http =
        ProviderPatch.newOkHttpClient("sk-shashu-fake", gateway.url("/").toString(), null);

    Request req =
        new Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer sk-real-openai-key")
            .post(RequestBody.create("{\"model\":\"gpt-4o\"}", MediaType.parse("application/json")))
            .build();
    http.newCall(req).execute().close();

    RecordedRequest seen = gateway.takeRequest();
    assertEquals("/v1/chat/completions", seen.getPath());
    assertEquals("Bearer sk-shashu-fake", seen.getHeader("Authorization"));
  }

  @Test
  void deprecated_interceptor_is_the_routing_interceptor_not_a_look_alike() {
    ShashuRoutingInterceptor legacy =
        new ShashuRoutingInterceptor("sk-shashu-fake", gateway.url("/").toString(), null);
    assertInstanceOf(ai.shashu.sdk.routing.ShashuRoutingInterceptor.class, legacy);
  }
}
