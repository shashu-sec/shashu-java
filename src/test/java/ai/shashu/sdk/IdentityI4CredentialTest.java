package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.internal.CredentialResolver;
import ai.shashu.sdk.internal.RetrievalTestAccess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityI4CredentialTest {

  private static final String EXPIRED_SENTENCE = "This pass has expired. Fetch a new one.";
  private static final String OK_BODY =
      "{"
          + "\"id\":\"chatcmpl-mock\","
          + "\"object\":\"chat.completion\","
          + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]"
          + "}";

  private MockWebServer server;

  @BeforeEach
  void setUp() throws Exception {
    RetrievalTestAccess.reset();
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    RetrievalTestAccess.reset();
    server.shutdown();
  }

  @Test
  void both_or_neither_error() {
    ShashuConfigException both =
        assertThrows(
            ShashuConfigException.class,
            () ->
                ShashuClient.builder()
                    .baseUrl(server.url("/").toString())
                    .apiKey("tg_sk_x")
                    .tokenProvider(() -> "p")
                    .build());
    assertEquals(CredentialResolver.BOTH_OR_NEITHER, both.getMessage());

    if (System.getenv("SHASHU_API_KEY") == null && System.getenv("TRUSTGATE_API_KEY") == null) {
      ShashuConfigException neither =
          assertThrows(
              ShashuConfigException.class,
              () -> ShashuClient.builder().baseUrl(server.url("/").toString()).build());
      assertEquals(CredentialResolver.BOTH_OR_NEITHER, neither.getMessage());
    }
  }

  @Test
  void reuse_until_skew() throws Exception {
    long iat = 1_700_000_000L;
    String token = mint(iat, 300);
    AtomicInteger calls = new AtomicInteger();
    RetrievalTestAccess.setNowSeconds(() -> iat);
    enqueueOk();
    enqueueOk();
    try (ShashuClient client =
        passClient(
            () -> {
              calls.incrementAndGet();
              return token;
            })) {
      create(client);
      create(client);
    }
    assertEquals(1, calls.get());
    assertEquals("Bearer " + token, server.takeRequest().getHeader("Authorization"));
    assertEquals("Bearer " + token, server.takeRequest().getHeader("Authorization"));
  }

  @Test
  void refresh_at_exp_minus_60() throws Exception {
    long iat = 1_700_000_000L;
    String first = mint(iat, 90);
    String second = mint(iat, 300);
    AtomicInteger n = new AtomicInteger();
    AtomicLong now = new AtomicLong(iat);
    RetrievalTestAccess.setNowSeconds(now::get);
    enqueueOk();
    enqueueOk();
    try (ShashuClient client = passClient(() -> n.getAndIncrement() == 0 ? first : second)) {
      create(client);
      now.set(iat + 90 - 59);
      create(client);
    }
    assertEquals(2, n.get());
    assertEquals("Bearer " + first, server.takeRequest().getHeader("Authorization"));
    assertEquals("Bearer " + second, server.takeRequest().getHeader("Authorization"));
  }

  @Test
  void one_refresh_under_50_concurrent() throws Exception {
    String token = mint(System.currentTimeMillis() / 1000L, 300);
    AtomicInteger calls = new AtomicInteger();
    for (int i = 0; i < 50; i++) {
      enqueueOk();
    }
    try (ShashuClient client =
        passClient(
            () -> {
              calls.incrementAndGet();
              return token;
            })) {
      ExecutorService pool = Executors.newFixedThreadPool(50);
      java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < 50; i++) {
        futures.add(pool.submit(() -> create(client)));
      }
      for (java.util.concurrent.Future<?> f : futures) {
        f.get();
      }
      pool.shutdown();
    }
    assertEquals(1, calls.get());
    assertEquals(50, server.getRequestCount());
  }

  @Test
  void retry_only_pass_expired() throws Exception {
    String first = mint(System.currentTimeMillis() / 1000L, 300);
    String second = mint(System.currentTimeMillis() / 1000L, 300);
    AtomicInteger n = new AtomicInteger();
    server.enqueue(json(401, refusal(PassCodes.PASS_EXPIRED, EXPIRED_SENTENCE)));
    enqueueOk();
    try (ShashuClient client = passClient(() -> n.getAndIncrement() == 0 ? first : second)) {
      assertEquals("ok", create(client).choices().get(0).message().content());
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
  void other_pass_codes_raise_with_code_and_exact_message_no_retry(String code) throws Exception {
    String sentence = SENTENCE.get(code);
    AtomicInteger n = new AtomicInteger();
    server.enqueue(json(401, refusal(code, sentence)));
    enqueueOk();
    try (ShashuClient client =
        passClient(
            () -> {
              n.incrementAndGet();
              return mint(System.currentTimeMillis() / 1000L, 300);
            })) {
      ShashuAuthenticationException ex =
          assertThrows(ShashuAuthenticationException.class, () -> create(client));
      assertEquals(code, ex.getCode());
      assertEquals(sentence, ex.getMessage());
    }
    assertEquals(1, n.get());
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void expired_twice_retries_only_once() throws Exception {
    AtomicInteger n = new AtomicInteger();
    server.enqueue(json(401, refusal(PassCodes.PASS_EXPIRED, EXPIRED_SENTENCE)));
    server.enqueue(json(401, refusal(PassCodes.PASS_EXPIRED, EXPIRED_SENTENCE)));
    try (ShashuClient client =
        passClient(
            () -> {
              n.incrementAndGet();
              return mint(System.currentTimeMillis() / 1000L, 300);
            })) {
      ShashuAuthenticationException ex =
          assertThrows(ShashuAuthenticationException.class, () -> create(client));
      assertEquals(PassCodes.PASS_EXPIRED, ex.getCode());
      assertEquals(EXPIRED_SENTENCE, ex.getMessage());
    }
    assertEquals(2, n.get());
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void missing_code_is_not_retried() throws Exception {
    AtomicInteger n = new AtomicInteger();
    server.enqueue(
        json(
            401,
            "{\"error\":{\"message\":\""
                + EXPIRED_SENTENCE
                + "\",\"type\":\"invalid_request_error\"}}"));
    try (ShashuClient client =
        passClient(
            () -> {
              n.incrementAndGet();
              return mint(System.currentTimeMillis() / 1000L, 300);
            })) {
      ShashuAuthenticationException ex =
          assertThrows(ShashuAuthenticationException.class, () -> create(client));
      assertEquals(EXPIRED_SENTENCE, ex.getMessage());
    }
    assertEquals(1, n.get());
  }

  @Test
  void key_mode_unchanged_and_never_retries() throws Exception {
    enqueueOk();
    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("tg_sk_test")
            .baseUrl(server.url("/").toString())
            .maxRetries(0)
            .build()) {
      create(client);
    }
    assertEquals("Bearer tg_sk_test", server.takeRequest().getHeader("Authorization"));

    server.enqueue(json(401, refusal(PassCodes.PASS_EXPIRED, EXPIRED_SENTENCE)));
    enqueueOk();
    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("tg_sk_test")
            .baseUrl(server.url("/").toString())
            .maxRetries(0)
            .build()) {
      ShashuAuthenticationException ex =
          assertThrows(ShashuAuthenticationException.class, () -> create(client));
      assertEquals(PassCodes.PASS_EXPIRED, ex.getCode());
    }
    assertEquals(1, server.getRequestCount() - 1);
  }

  @Test
  void streaming_retries_before_caller_bytes_then_not_after() throws Exception {
    AtomicInteger n = new AtomicInteger();
    String first = mint(System.currentTimeMillis() / 1000L, 300);
    String second = mint(System.currentTimeMillis() / 1000L, 300);
    server.enqueue(json(401, refusal(PassCodes.PASS_EXPIRED, EXPIRED_SENTENCE)));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"));
    try (ShashuClient client = passClient(() -> n.getAndIncrement() == 0 ? first : second);
        var stream = client.chat().completions().createStream(req())) {
      StringBuilder out = new StringBuilder();
      stream.forEach(
          chunk -> {
            if (!chunk.choices().isEmpty()
                && chunk.choices().get(0).delta() != null
                && chunk.choices().get(0).delta().content() != null) {
              out.append(chunk.choices().get(0).delta().content());
            }
          });
      assertEquals("x", out.toString());
    }
    assertEquals(2, n.get());
    assertFalse(CredentialResolver.shouldRetryPassRefused(true, 401, PassCodes.PASS_EXPIRED, true));
    assertTrue(CredentialResolver.shouldRetryPassRefused(true, 401, PassCodes.PASS_EXPIRED, false));
  }

  @Test
  void the_six_codes_are_named_constants() {
    assertEquals(
        List.of(
            "pass_provider_not_set_up",
            "pass_not_licensed",
            "pass_expired",
            "pass_wrong_audience",
            "pass_wrong_issuer",
            "pass_unknown_agent"),
        PassCodes.ALL);
    assertEquals("pass_expired", PassCodes.PASS_EXPIRED);
  }

  @Test
  void no_public_type_name_contains_TrustGate() throws Exception {
    Path root = Path.of("src/main/java");
    Pattern pub =
        Pattern.compile("public\\s+(?:final\\s+)?(?:class|interface|enum|record)\\s+(\\w+)");
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path path : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
        String src = Files.readString(path);
        Matcher m = pub.matcher(src);
        while (m.find()) {
          if (m.group(1).contains("TrustGate")) {
            offenders.add(path + ":" + m.group(1));
          }
        }
      }
    }
    assertEquals(List.of(), offenders);
  }

  private ShashuClient passClient(Supplier<String> provider) {
    return ShashuClient.builder()
        .baseUrl(server.url("/").toString())
        .tokenProvider(provider)
        .maxRetries(0)
        .build();
  }

  private static ai.shashu.sdk.chat.ChatCompletionResponse create(ShashuClient client) {
    return client.chat().completions().create(req());
  }

  private static ChatCompletionRequest req() {
    return ChatCompletionRequest.builder().model("mock").addMessage(ChatMessage.user("hi")).build();
  }

  private void enqueueOk() {
    server.enqueue(json(200, OK_BODY));
  }

  private static MockResponse json(int status, String body) {
    return new MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  private static String refusal(String code, String message) {
    return "{\"error\":{\"message\":\""
        + message
        + "\",\"type\":\"invalid_request_error\",\"code\":\""
        + code
        + "\"}}";
  }

  private static String mint(long iat, long lifetimeSeconds) {
    long exp = iat + lifetimeSeconds;
    String header = b64("{\"alg\":\"none\"}");
    String payload = b64("{\"iat\":" + iat + ",\"exp\":" + exp + "}");
    return header + "." + payload + ".sig";
  }

  private static String b64(String s) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private static final java.util.Map<String, String> SENTENCE =
      java.util.Map.of(
          PassCodes.PASS_PROVIDER_NOT_SET_UP, "No identity provider is set up for agents.",
          PassCodes.PASS_NOT_LICENSED, "Pass sign-in needs the Enterprise plan.",
          PassCodes.PASS_EXPIRED, EXPIRED_SENTENCE,
          PassCodes.PASS_WRONG_AUDIENCE, "This pass is meant for a different audience.",
          PassCodes.PASS_WRONG_ISSUER, "This pass comes from an unknown issuer.",
          PassCodes.PASS_UNKNOWN_AGENT, "No agent matches this pass.");
}
