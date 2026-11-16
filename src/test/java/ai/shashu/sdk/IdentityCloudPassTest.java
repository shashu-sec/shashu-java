package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.internal.CredentialResolver;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityCloudPassTest {

  private static final String CHAT =
      "{\"id\":\"chatcmpl-mock\",\"object\":\"chat.completion\","
          + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
  private static final String EXPIRED_SENTENCE = "This pass has expired. Fetch a new one.";

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

  static String mint(long lifetimeSeconds) {
    long now = System.currentTimeMillis() / 1000L;
    String header = b64("{\"alg\":\"none\"}");
    String payload = b64("{\"exp\":" + (now + lifetimeSeconds) + ",\"iat\":" + now + "}");
    return header + "." + payload + ".sig";
  }

  private static String b64(String s) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String refusal(String code, String message) {
    return "{\"error\":{\"message\":\""
        + message
        + "\",\"type\":\"invalid_request_error\",\"code\":\""
        + code
        + "\"}}";
  }

  private ShashuClient client(java.util.function.Supplier<String> provider) {
    return ShashuClient.builder()
        .baseUrl(server.url("/").toString())
        .tokenProvider(provider)
        .maxRetries(0)
        .build();
  }

  private void chat(ShashuClient tg) {
    tg.chat()
        .completions()
        .create(
            ChatCompletionRequest.builder()
                .model("mock")
                .addMessage(ChatMessage.user("hi"))
                .build());
  }

  @Test
  void both_or_neither() {
    ShashuConfigException both =
        assertThrows(
            ShashuConfigException.class,
            () ->
                ShashuClient.builder()
                    .baseUrl("http://127.0.0.1:9")
                    .apiKey("tg_sk_x")
                    .tokenProvider(() -> "p")
                    .build());
    assertEquals(CredentialResolver.BOTH_OR_NEITHER, both.getMessage());
  }

  @Test
  void reuse_until_skew() throws Exception {
    String token = mint(300);
    AtomicInteger n = new AtomicInteger();
    server.enqueue(json(CHAT));
    server.enqueue(json(CHAT));
    try (ShashuClient tg =
        client(
            () -> {
              n.incrementAndGet();
              return token;
            })) {
      chat(tg);
      chat(tg);
    }
    assertEquals(1, n.get());
    assertEquals("Bearer " + token, server.takeRequest().getHeader("Authorization"));
    assertEquals("Bearer " + token, server.takeRequest().getHeader("Authorization"));
  }

  @Test
  void refresh_at_exp_minus_60s() throws Exception {
    String first = mint(50);
    String second = mint(300);
    AtomicInteger n = new AtomicInteger();
    server.enqueue(json(CHAT));
    server.enqueue(json(CHAT));
    try (ShashuClient tg = client(() -> n.getAndIncrement() == 0 ? first : second)) {
      chat(tg);
      chat(tg);
    }
    assertEquals(2, n.get());
    assertEquals("Bearer " + first, server.takeRequest().getHeader("Authorization"));
    assertEquals("Bearer " + second, server.takeRequest().getHeader("Authorization"));
  }

  @Test
  void retry_once_on_pass_expired() throws Exception {
    String first = mint(300);
    String second = mint(300);
    AtomicInteger n = new AtomicInteger();
    server.enqueue(json(refusal(Shashu.PASS_EXPIRED, EXPIRED_SENTENCE)).setResponseCode(401));
    server.enqueue(json(CHAT));
    try (ShashuClient tg = client(() -> n.getAndIncrement() == 0 ? first : second)) {
      chat(tg);
    }
    assertEquals(2, n.get());
    assertEquals(2, server.getRequestCount());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        PassCodes.PASS_PROVIDER_NOT_SET_UP,
        PassCodes.PASS_NOT_LICENSED,
        PassCodes.PASS_WRONG_AUDIENCE,
        PassCodes.PASS_WRONG_ISSUER,
        PassCodes.PASS_UNKNOWN_AGENT
      })
  void other_pass_codes_no_retry(String code) {
    String sentence =
        switch (code) {
          case PassCodes.PASS_WRONG_AUDIENCE -> "This pass is meant for a different audience.";
          case PassCodes.PASS_UNKNOWN_AGENT -> "No agent matches this pass.";
          default -> "refused";
        };
    AtomicInteger n = new AtomicInteger();
    server.enqueue(json(refusal(code, sentence)).setResponseCode(401));
    try (ShashuClient tg =
        client(
            () -> {
              n.incrementAndGet();
              return mint(300);
            })) {
      ShashuAuthenticationException ex =
          assertThrows(ShashuAuthenticationException.class, () -> chat(tg));
      assertEquals(code, ex.getCode());
      assertEquals(sentence, ex.getMessage());
    }
    assertEquals(1, n.get());
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void key_mode_no_retry_on_pass_expired() {
    server.enqueue(json(refusal(Shashu.PASS_EXPIRED, EXPIRED_SENTENCE)).setResponseCode(401));
    try (ShashuClient tg =
        ShashuClient.builder()
            .baseUrl(server.url("/").toString())
            .apiKey("tg_sk_test")
            .maxRetries(0)
            .build()) {
      ShashuAuthenticationException ex =
          assertThrows(ShashuAuthenticationException.class, () -> chat(tg));
      assertEquals(EXPIRED_SENTENCE, ex.getMessage());
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void missing_code_is_not_retried() {
    AtomicInteger n = new AtomicInteger();
    server.enqueue(
        new MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"error\":{\"message\":\"Not authenticated\",\"type\":\"invalid_request_error\"}}"));
    try (ShashuClient tg =
        client(
            () -> {
              n.incrementAndGet();
              return mint(300);
            })) {
      assertThrows(ShashuException.class, () -> chat(tg));
    }
    assertEquals(1, n.get());
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void six_codes_are_named_constants() {
    assertEquals(
        Set.of(
            "pass_provider_not_set_up",
            "pass_not_licensed",
            "pass_expired",
            "pass_wrong_audience",
            "pass_wrong_issuer",
            "pass_unknown_agent"),
        Shashu.PASS_CODES);
  }

  @Test
  void no_public_type_named_TrustGate() {
    Class<?>[] publicTypes = {
      Shashu.class,
      ShashuClient.class,
      ShashuClientOptions.class,
      ShashuException.class,
      ShashuAuthenticationException.class,
      ShashuDecision.class,
      ShashuConfigException.class,
      ShashuContentFilterException.class,
      ShashuPolicyViolation.class,
      PassCodes.class
    };
    for (Class<?> c : publicTypes) {
      assertTrue(Modifier.isPublic(c.getModifiers()));
      assertTrue(!c.getSimpleName().contains("TrustGate"), c.getName());
    }
  }

  @Test
  void one_refresh_under_fifty_concurrent() throws Exception {
    String token = mint(300);
    AtomicInteger n = new AtomicInteger();
    for (int i = 0; i < 50; i++) {
      server.enqueue(json(CHAT));
    }
    try (ShashuClient tg =
        client(
            () -> {
              n.incrementAndGet();
              return token;
            })) {
      java.util.concurrent.ExecutorService pool =
          java.util.concurrent.Executors.newFixedThreadPool(50);
      java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < 50; i++) {
        futures.add(pool.submit(() -> chat(tg)));
      }
      for (java.util.concurrent.Future<?> f : futures) {
        f.get();
      }
      pool.shutdown();
    }
    assertEquals(1, n.get());
  }

  @Test
  void streaming_does_not_retry_after_first_byte() {
    assertTrue(
        !ai.shashu.sdk.internal.CredentialResolver.shouldRetryPassRefused(
            true, 401, Shashu.PASS_EXPIRED, true));
    assertTrue(
        ai.shashu.sdk.internal.CredentialResolver.shouldRetryPassRefused(
            true, 401, Shashu.PASS_EXPIRED, false));
  }

  @Test
  void key_mode_unchanged() throws Exception {
    server.enqueue(json(CHAT));
    try (ShashuClient tg =
        ShashuClient.builder()
            .baseUrl(server.url("/").toString())
            .apiKey("tg_sk_test")
            .maxRetries(0)
            .build()) {
      chat(tg);
    }
    assertEquals("Bearer tg_sk_test", server.takeRequest().getHeader("Authorization"));
  }

  private static MockResponse json(String body) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }
}
