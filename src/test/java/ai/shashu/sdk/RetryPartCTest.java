package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetryPartCTest {

  private static final String BUSY_SENTENCE =
      "The gateway is busy. Retry after the advertised wait.";

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

  private ShashuClient.Builder builder() {
    return ShashuClient.builder().apiKey("tg_sk_same").baseUrl(server.url("/").toString());
  }

  private static ChatCompletionRequest req() {
    return ChatCompletionRequest.builder().model("m").addMessage(ChatMessage.user("hi")).build();
  }

  private static MockResponse ok() {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"id\":\"chatcmpl-mock\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
  }

  private static MockResponse busy(int status) {
    return new MockResponse()
        .setResponseCode(status)
        .setHeader("Retry-After", "0")
        .setHeader("x-should-retry", "true")
        .setBody("{\"error\":{\"message\":\"" + BUSY_SENTENCE + "\",\"code\":\"gateway_busy\"}}");
  }

  @Test
  void c1_503_then_200_byte_identical() throws Exception {
    server.enqueue(busy(503));
    server.enqueue(ok());
    Retrieval.declareRetrieval(java.util.List.of("CANARY"), "c1", "q");
    try (ShashuClient client = builder().maxRetries(2).build()) {
      client.chat().completions().create(req());
    }
    assertEquals(2, server.getRequestCount());
    String a = server.takeRequest().getBody().readUtf8();
    String b = server.takeRequest().getBody().readUtf8();
    assertEquals(a, b);
    assertTrue(a.contains("c1"));
  }

  @Test
  void c2_429_then_200_byte_identical() throws Exception {
    server.enqueue(busy(429));
    server.enqueue(ok());
    Retrieval.declareRetrieval(java.util.List.of("CANARY"), "c2", "q");
    try (ShashuClient client = builder().maxRetries(2).build()) {
      client.chat().completions().create(req());
    }
    assertEquals(
        server.takeRequest().getBody().readUtf8(), server.takeRequest().getBody().readUtf8());
  }

  @Test
  void c3_503_without_invitation_does_not_retry() throws Exception {
    server.enqueue(
        new MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"busy\"}}"));
    try (ShashuClient client = builder().maxRetries(2).build()) {
      assertThrows(ShashuException.class, () -> client.chat().completions().create(req()));
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void c4_max_retries_zero() throws Exception {
    server.enqueue(busy(503));
    try (ShashuClient client = builder().maxRetries(0).build()) {
      assertThrows(ShashuException.class, () -> client.chat().completions().create(req()));
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void c5_gives_up_after_max() throws Exception {
    for (int i = 0; i < 8; i++) {
      server.enqueue(busy(503));
    }
    try (ShashuClient client = builder().maxRetries(2).build()) {
      assertThrows(ShashuException.class, () -> client.chat().completions().create(req()));
    }
    assertEquals(3, server.getRequestCount());
  }

  @Test
  void c6_5xx_not_retried_by_default() throws Exception {
    server.enqueue(
        new MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"boom\"}}"));
    try (ShashuClient client = builder().maxRetries(2).build()) {
      assertThrows(ShashuException.class, () -> client.chat().completions().create(req()));
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void c7_next_call_does_not_leak() throws Exception {
    server.enqueue(busy(503));
    server.enqueue(ok());
    server.enqueue(ok());
    Retrieval.declareRetrieval(java.util.List.of("first"), "only_first", "q");
    try (ShashuClient client = builder().maxRetries(2).build()) {
      client.chat().completions().create(req());
      client.chat().completions().create(req());
    }
    String first = server.takeRequest().getBody().readUtf8();
    String retry = server.takeRequest().getBody().readUtf8();
    String second = server.takeRequest().getBody().readUtf8();
    assertEquals(first, retry);
    assertTrue(first.contains("only_first"));
    assertFalse(second.contains("only_first"));
  }

  @Test
  void c8_idempotency_key_none_sent() throws Exception {
    server.enqueue(ok());
    try (ShashuClient client = builder().maxRetries(0).build()) {
      client.chat().completions().create(req());
    }
    RecordedRequest seen = server.takeRequest();
    assertFalse(seen.getHeader("Idempotency-Key") != null);
    assertFalse(seen.getBody().readUtf8().toLowerCase(Locale.ROOT).contains("idempotency"));
  }

  @Test
  void c9_authorization_only_overlay() throws Exception {
    server.enqueue(busy(503));
    server.enqueue(ok());
    try (ShashuClient client = builder().maxRetries(2).build()) {
      client.chat().completions().create(req());
    }
    RecordedRequest a = server.takeRequest();
    RecordedRequest b = server.takeRequest();
    assertEquals(a.getHeader("Authorization"), b.getHeader("Authorization"));
    assertEquals(a.getHeader("x-shashu-trace-id"), b.getHeader("x-shashu-trace-id"));
  }

  @Test
  void c10_hop_depth_frozen() throws Exception {
    server.enqueue(busy(503));
    server.enqueue(ok());
    try (ShashuClient client = builder().maxRetries(2).build()) {
      client.chat().completions().create(req());
    }
    assertEquals(
        server.takeRequest().getHeader("x-shashu-hop-depth"),
        server.takeRequest().getHeader("x-shashu-hop-depth"));
  }

  @Test
  void c11_trace_and_session_frozen() throws Exception {
    server.enqueue(busy(503));
    server.enqueue(ok());
    try (ShashuClient client = builder().maxRetries(2).build()) {
      client.chat().completions().create(req());
    }
    RecordedRequest a = server.takeRequest();
    RecordedRequest b = server.takeRequest();
    assertEquals(a.getHeader("x-shashu-trace-id"), b.getHeader("x-shashu-trace-id"));
    assertEquals(a.getHeader("x-shashu-session-id"), b.getHeader("x-shashu-session-id"));
  }

  @Test
  void two_threads_keep_their_own_declaration() throws Exception {
    java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> hits =
        new java.util.concurrent.ConcurrentHashMap<>();
    java.util.concurrent.ConcurrentHashMap<String, java.util.List<String>> bodies =
        new java.util.concurrent.ConcurrentHashMap<>();
    server.setDispatcher(
        new okhttp3.mockwebserver.Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            String body = request.getBody().readUtf8();
            String corpus =
                body.contains("corpus_left")
                    ? "corpus_left"
                    : body.contains("corpus_right") ? "corpus_right" : "none";
            bodies
                .computeIfAbsent(
                    corpus, k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(body);
            int n =
                hits.computeIfAbsent(corpus, k -> new java.util.concurrent.atomic.AtomicInteger())
                    .incrementAndGet();
            return n == 1 ? busy(503) : ok();
          }
        });
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch done = new CountDownLatch(2);
    var pool = Executors.newFixedThreadPool(2);
    pool.submit(
        () -> {
          Retrieval.declareRetrieval(java.util.List.of("LEFT"), "corpus_left", "LEFT");
          ready.countDown();
          ready.await(5, TimeUnit.SECONDS);
          try (ShashuClient client = builder().maxRetries(2).build()) {
            client.chat().completions().create(req());
          }
          done.countDown();
          return null;
        });
    pool.submit(
        () -> {
          Retrieval.declareRetrieval(java.util.List.of("RIGHT"), "corpus_right", "RIGHT");
          ready.countDown();
          ready.await(5, TimeUnit.SECONDS);
          try (ShashuClient client = builder().maxRetries(2).build()) {
            client.chat().completions().create(req());
          }
          done.countDown();
          return null;
        });
    assertTrue(done.await(20, TimeUnit.SECONDS));
    pool.shutdownNow();
    assertFalse(bodies.containsKey("none"));
    List<String> left = bodies.getOrDefault("corpus_left", List.of());
    List<String> right = bodies.getOrDefault("corpus_right", List.of());
    assertEquals(2, left.size());
    assertEquals(2, right.size());
    for (String body : left) {
      assertFalse(body.contains("RIGHT"));
    }
    for (String body : right) {
      assertFalse(body.contains("LEFT"));
    }
    assertEquals(left.get(0), left.get(1));
    assertEquals(right.get(0), right.get(1));
  }
}
