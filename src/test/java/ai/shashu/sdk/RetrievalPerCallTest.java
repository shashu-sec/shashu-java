package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.chat.ContentPart;
import ai.shashu.sdk.internal.RetrievalTestAccess;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A retrieval declaration belongs to the one call it was made for, whatever the body size: it is
 * attached to that call and never carried over to another.
 */
class RetrievalPerCallTest {

  /** Above the 8 MiB cap the joiner used to apply. */
  private static final int LARGE_BYTES = 9 * 1024 * 1024;

  private MockWebServer server;
  private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void setUp() throws IOException {
    RetrievalTestAccess.reset();
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            String body = request.getBody().readUtf8();
            bodies.add(body);
            if (body.contains("FAIL-THIS-CALL")) {
              return new MockResponse()
                  .setResponseCode(400)
                  .setHeader("Content-Type", "application/json")
                  .setBody("{\"error\":{\"message\":\"bad\",\"code\":\"bad_request\"}}");
            }
            if ("text/event-stream".equals(request.getHeader("Accept"))) {
              return new MockResponse()
                  .setHeader("Content-Type", "text/event-stream")
                  .setBody(
                      "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}\n\n"
                          + "data: [DONE]\n\n");
            }
            return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    "{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
          }
        });
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
    RetrievalTestAccess.reset();
  }

  @Test
  void a_large_request_carries_its_declaration() {
    Retrieval.declareRetrieval(List.of("large declared document"), "large_corpus", "q");
    try (ShashuClient client = client()) {
      client.chat().completions().create(large());
    }
    assertEquals(1, bodies.size());
    assertTrue(bodies.get(0).length() > LARGE_BYTES);
    assertTrue(bodies.get(0).contains("large_corpus"), "the large request lost its declaration");
  }

  @Test
  void b_the_next_undeclared_call_carries_nothing() {
    Retrieval.declareRetrieval(List.of("large declared document"), "large_corpus", "q");
    try (ShashuClient client = client()) {
      client.chat().completions().create(large());
      client.chat().completions().create(small("next"));
    }
    assertEquals(2, bodies.size());
    assertFalse(bodies.get(1).contains("shashu_metadata"), "leftover leaked: " + bodies.get(1));
  }

  @Test
  void c_concurrent_large_and_small_each_carry_only_their_own() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch declared = new CountDownLatch(2);
    try (ShashuClient client = client()) {
      Future<?> big =
          pool.submit(
              () -> {
                Retrieval.declareRetrieval(List.of("large declared document"), "large_corpus", "q");
                declared.countDown();
                await(declared);
                client.chat().completions().create(large());
                return null;
              });
      Future<?> little =
          pool.submit(
              () -> {
                Retrieval.declareRetrieval(List.of("small declared document"), "small_corpus", "q");
                declared.countDown();
                await(declared);
                client.chat().completions().create(small("small"));
                return null;
              });
      big.get(60, TimeUnit.SECONDS);
      little.get(60, TimeUnit.SECONDS);
      // Each worker thread then makes an undeclared call: nothing may be left over.
      pool.submit(() -> client.chat().completions().create(small("after-1"))).get();
      pool.submit(() -> client.chat().completions().create(small("after-2"))).get();
    } finally {
      pool.shutdownNow();
    }
    String largeBody = find("LARGE-MARKER");
    String smallBody = find("\"small\"");
    assertTrue(largeBody.contains("large_corpus"), "large lost its own declaration");
    assertFalse(largeBody.contains("small_corpus"));
    assertTrue(smallBody.contains("small_corpus"));
    assertFalse(smallBody.contains("large_corpus"));
    assertFalse(find("after-1").contains("shashu_metadata"), "leftover leaked");
    assertFalse(find("after-2").contains("shashu_metadata"), "leftover leaked");
  }

  @Test
  void d_a_streamed_call_carries_its_declaration_and_leaves_nothing() {
    Retrieval.declareRetrieval(List.of("stream declared document"), "stream_corpus", "q");
    try (ShashuClient client = client()) {
      try (var stream = client.chat().completions().createStream(small("stream"))) {
        stream.forEach(c -> {});
      }
      client.chat().completions().create(small("next"));
    }
    assertEquals(2, bodies.size());
    assertTrue(bodies.get(0).contains("stream_corpus"), "the stream lost its declaration");
    assertFalse(bodies.get(1).contains("shashu_metadata"), "leftover leaked: " + bodies.get(1));
  }

  @Test
  void e_a_failed_call_leaves_nothing_for_the_next() {
    Retrieval.declareRetrieval(List.of("failed declared document"), "failed_corpus", "q");
    try (ShashuClient client = client()) {
      assertThrows(
          ShashuException.class, () -> client.chat().completions().create(small("FAIL-THIS-CALL")));
      client.chat().completions().create(small("next"));
    }
    assertEquals(2, bodies.size());
    assertTrue(bodies.get(0).contains("failed_corpus"));
    assertFalse(bodies.get(1).contains("shashu_metadata"), "leftover leaked: " + bodies.get(1));
  }

  private ShashuClient client() {
    return ShashuClient.builder()
        .apiKey("shashu_sk_percall")
        .baseUrl(server.url("/").toString())
        .maxRetries(0)
        .build();
  }

  private String find(String marker) {
    synchronized (bodies) {
      return bodies.stream()
          .filter(b -> b.contains(marker))
          .findFirst()
          .orElseThrow(() -> new AssertionError("no request carried " + marker));
    }
  }

  private static ChatCompletionRequest small(String text) {
    return ChatCompletionRequest.builder().model("m").addMessage(ChatMessage.user(text)).build();
  }

  static ChatCompletionRequest large() {
    byte[] pdf = new byte[LARGE_BYTES];
    new Random(7).nextBytes(pdf);
    return ChatCompletionRequest.builder()
        .model("m")
        .addMessage(
            ChatMessage.user(
                ContentPart.text("LARGE-MARKER"),
                ContentPart.file(pdf, "large.pdf", "application/pdf")))
        .build();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
