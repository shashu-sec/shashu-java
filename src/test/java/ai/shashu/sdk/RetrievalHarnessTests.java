package ai.shashu.sdk;

import static ai.shashu.sdk.harness.RagContextSchema.validate;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.RagContext;
import ai.shashu.sdk.chat.RagDoc;
import ai.shashu.sdk.harness.LogCapture;
import ai.shashu.sdk.harness.MockGateway;
import ai.shashu.sdk.internal.RetrievalTestAccess;
import ai.shashu.sdk.routing.ShashuRoutingInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retrieval declaration, end to end, against an in-repo gateway that records what the SDK
 * actually sent.
 *
 * <p>Twelve cases, negative first. Each carries a <b>canary nonce</b> unique to the case, so a
 * payload that arrived from the wrong test - or from the previous one's un-cleared bus - fails
 * rather than blending in.
 *
 * <p><b>No socket is opened.</b> {@link MockGateway} is the terminal interceptor and answers in
 * place of the network, so the bytes asserted on are the exact bytes the SDK was about to write.
 */
@DisplayName("Retrieval harness: what the gateway actually receives")
class RetrievalHarnessTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private MockGateway gateway;

  @BeforeEach
  void setUp() {
    RetrievalTestAccess.reset();
    gateway = new MockGateway();
  }

  @AfterEach
  void tearDown() {
    RetrievalTestAccess.reset();
  }

  // ------------------------------------------------------------------ 1

  @Test
  @DisplayName("1 NEGATIVE: no declaration -> no shashu_metadata at all")
  void case01_no_declaration_means_no_metadata() throws Exception {
    callModel(client(), "what is the leave policy?");

    var seen = gateway.single();
    assertFalse(
        seen.hasShashuMetadata(), "an app that declared nothing must send exactly what it built");
    assertNull(seen.ragContext());
    assertEquals("not_declared", seen.declaration());
  }

  // ------------------------------------------------------------------ 2

  @Test
  @DisplayName("2 a declaration arrives, validates against the schema, and carries the nonce")
  void case02_a_declaration_arrives_schema_valid_with_the_nonce() throws Exception {
    String nonce = nonce();
    Retrieval.declareRetrieval(
        List.of(
            Map.of(
                "id",
                "policy-1",
                "text",
                "Carry-over is capped at 5 days. " + nonce,
                "score",
                0.91,
                "source_uri",
                "s3://hr/policy.pdf",
                "hash",
                "deadbeefcafe1234"),
            Map.of("id", "policy-2", "text", "Requests need 2 weeks' notice.")),
        "policy_docs_v2",
        "leave policy",
        "manual");

    callModel(client(), "what is the leave policy?");

    var seen = gateway.single();
    assertEquals("declared", seen.declaration());

    JsonNode context = seen.ragContext();
    assertEquals(List.of(), validate(context), "must satisfy rag_context.v1");
    assertEquals(1, context.get("v").asInt());
    assertEquals("policy_docs_v2", context.get("corpus").asText());
    assertEquals("leave policy", context.get("query").asText());
    assertEquals("manual", context.get("source").asText());
    assertEquals(2, context.get("docs").size());
    assertTrue(
        context.get("docs").get(0).get("preview").asText().contains(nonce),
        "the canary proves this is THIS test's payload");
    assertEquals(0.91d, context.get("docs").get(0).get("score").asDouble(), 1e-9);
    assertEquals("deadbeefcafe1234", context.get("docs").get(0).get("hash").asText());
  }

  // ------------------------------------------------------------------ 3

  @Test
  @DisplayName("3 an explicit RagContext on the request wins and arrives unmodified")
  void case03_explicit_rag_context_wins_over_a_populated_bus() throws Exception {
    String explicitNonce = nonce();
    String ambientNonce = nonce();

    // The bus is populated - and must lose.
    Retrieval.declareRetrieval(List.of("ambient document " + ambientNonce), "ambient_corpus");

    RagContext explicit =
        RagContext.builder()
            .corpus("explicit_corpus")
            .query("explicit query")
            .addDoc(RagDoc.of("explicit-1", 0.5d, "explicit document " + explicitNonce))
            .build();

    Map<String, Object> body = chatBody("hello");
    body.put("shashu_metadata", Map.of("rag_context", MAPPER.convertValue(explicit, Map.class)));
    post(client(), body);

    JsonNode context = gateway.single().ragContext();
    assertEquals("explicit_corpus", context.get("corpus").asText());
    assertEquals(1, context.get("docs").size());
    assertTrue(context.get("docs").get(0).get("preview").asText().contains(explicitNonce));
    assertFalse(
        gateway.single().bodyText().contains(ambientNonce),
        "the ambient declaration must not be merged in alongside the explicit one");

    // ...and it is DISCARDED, not deferred: it must not turn up next call.
    gateway = new MockGateway();
    callModel(client(), "second call");
    assertEquals("not_declared", gateway.single().declaration());
    assertFalse(gateway.single().bodyText().contains(ambientNonce));
  }

  // ------------------------------------------------------------------ 4

  @Test
  @DisplayName("4 snapshot-and-clear: request N's declaration never reaches request N+1")
  void case04_the_bus_is_cleared_when_it_is_written_to_a_request() throws Exception {
    String nonce = nonce();
    Retrieval.declareRetrieval(List.of("first-call document " + nonce), "corpus_a");

    OkHttpClient http = client();
    callModel(http, "first");
    callModel(http, "second");

    List<MockGateway.RecordedRequest> seen = gateway.requests();
    assertEquals(2, seen.size());
    assertEquals("declared", seen.get(0).declaration());
    assertTrue(seen.get(0).bodyText().contains(nonce));

    assertEquals("not_declared", seen.get(1).declaration());
    assertFalse(
        seen.get(1).bodyText().contains(nonce),
        "a declaration that was already sent must not be sent twice");
  }

  // ------------------------------------------------------------------ 5

  @Test
  @DisplayName("5 concurrency: 8 interleaved tasks, distinct nonces, zero cross-contamination")
  void case05_eight_concurrent_tasks_do_not_see_each_others_documents() throws Exception {
    OkHttpClient http = client();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Callable<String>> work = new ArrayList<>();

    for (int i = 0; i < 8; i++) {
      String nonce = "task-" + i + "-" + nonce();
      work.add(
          () -> {
            // Declare AND call on the same thread: the isolated shape.
            Retrieval.declareRetrieval(List.of("document for " + nonce), "corpus_" + nonce);
            callModel(http, "question " + nonce);
            return nonce;
          });
    }

    List<String> nonces = new ArrayList<>();
    for (Future<String> future : pool.invokeAll(work)) {
      nonces.add(future.get());
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

    List<MockGateway.RecordedRequest> seen = gateway.requests();
    assertEquals(8, seen.size());
    for (MockGateway.RecordedRequest request : seen) {
      assertEquals("declared", request.declaration());
      JsonNode context = request.ragContext();
      assertEquals(List.of(), validate(context));
      assertEquals(
          1,
          context.get("docs").size(),
          "exactly one document: more means another task's leaked in");

      String preview = context.get("docs").get(0).get("preview").asText();
      String corpus = context.get("corpus").asText();
      String mine = preview.substring("document for ".length());
      assertEquals(
          "corpus_" + mine, corpus, "the corpus and the document must belong to the same task");
      assertTrue(nonces.contains(mine));
    }
  }

  // ------------------------------------------------------------------ 6

  @Test
  @DisplayName("6 THE LOSS CASE: declare, hand off, and the gateway records not_declared")
  void case06_a_declaration_lost_across_an_executor_arrives_as_not_declared() throws Exception {
    String nonce = nonce();
    OkHttpClient http = client();

    // A pool warmed BEFORE the declaration - the common production shape.
    ExecutorService pool = Executors.newFixedThreadPool(1);
    pool.submit(() -> null).get();

    Retrieval.declareRetrieval(List.of("stranded document " + nonce), "stranded_corpus_" + nonce);

    // The model call happens on the pool thread. Java's ThreadLocal does
    // not follow it.
    pool.submit(
            () -> {
              callModel(http, "question");
              return null;
            })
        .get();
    pool.shutdown();
    assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

    var seen = gateway.single();
    assertEquals(
        "not_declared",
        seen.declaration(),
        "the gateway must record this request as undeclared, not as clean");
    assertFalse(seen.hasShashuMetadata());
    assertFalse(
        seen.bodyText().contains(nonce),
        "and it must not have leaked either - loss, not misattribution");
  }

  // ------------------------------------------------------------------ 7

  @Test
  @DisplayName("7 scope.wrap recovers case 6: the SAME work, the declaration arrives")
  void case07_wrap_carries_the_same_work_across_the_same_executor() throws Exception {
    String nonce = nonce();
    OkHttpClient http = client();

    ExecutorService pool = Executors.newFixedThreadPool(1);
    pool.submit(() -> null).get();

    try (var scope =
        Retrieval.retrievalScope(
            List.of("scoped document " + nonce), "scoped_corpus", "the question")) {
      // Case 6's hand-off exactly, with one difference: the task goes
      // through scope.wrap, so it carries the declaration with it.
      pool.submit(
              scope.wrap(
                  () -> {
                    callModel(http, "question");
                    return null;
                  }))
          .get();
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

    var seen = gateway.single();
    assertEquals(
        "declared",
        seen.declaration(),
        "exactly the work of case 6, handed off through scope.wrap(), must arrive declared");
    JsonNode context = seen.ragContext();
    assertEquals(List.of(), validate(context));
    assertEquals("scoped_corpus", context.get("corpus").asText());
    assertTrue(context.get("docs").get(0).get("preview").asText().contains(nonce));
  }

  @Test
  @DisplayName("7b two wrapped scopes overlapping on one pool: each request carries ONLY its own")
  void case07b_two_concurrent_wrapped_scopes_keep_their_documents_separate() throws Exception {
    OkHttpClient http = client();

    // Two "endpoint" threads, each opening its own scope, both handing off
    // onto ONE shared, already-warm worker pool. This is the shape the
    // removed adoption rule got wrong: with one scope open and an
    // undeclared call elsewhere it merged them, and merging is the failure
    // a customer cannot see.
    ExecutorService endpoints = Executors.newFixedThreadPool(2);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    workers.submit(() -> null).get();
    workers.submit(() -> null).get();

    String a = nonce();
    String b = nonce();
    CountDownLatch bothInside = new CountDownLatch(2);

    List<Callable<String>> requests = new ArrayList<>();
    for (String tag : List.of(a, b)) {
      requests.add(
          () -> {
            try (var scope =
                Retrieval.retrievalScope(List.of("doc for " + tag), "corpus_" + tag, "q " + tag)) {
              return workers
                  .submit(
                      scope.wrap(
                          () -> {
                            // Hold both units of work inside their scopes at once,
                            // so the overlap is real and not an artefact of
                            // scheduling.
                            bothInside.countDown();
                            assertTrue(bothInside.await(20, TimeUnit.SECONDS));
                            callModel(http, "question " + tag);
                            return tag;
                          }))
                  .get();
            }
          });
    }

    for (Future<String> future : endpoints.invokeAll(requests)) {
      future.get();
    }
    endpoints.shutdown();
    workers.shutdown();
    assertTrue(endpoints.awaitTermination(20, TimeUnit.SECONDS));
    assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));

    List<MockGateway.RecordedRequest> seen = gateway.requests();
    assertEquals(2, seen.size());

    List<String> corpora = new ArrayList<>();
    for (MockGateway.RecordedRequest request : seen) {
      assertEquals(
          "declared",
          request.declaration(),
          "neither unit of work may lose its declaration to the other");
      JsonNode context = request.ragContext();
      assertEquals(List.of(), validate(context));
      assertEquals(
          1, context.get("docs").size(), "two documents means the other scope's leaked in");

      String preview = context.get("docs").get(0).get("preview").asText();
      String mine = preview.substring("doc for ".length());
      assertEquals(
          "corpus_" + mine,
          context.get("corpus").asText(),
          "the corpus and the document must belong to the same unit of work");
      assertFalse(
          request.bodyText().contains(a.equals(mine) ? b : a),
          "the other unit of work's nonce must appear nowhere in this request");
      corpora.add(context.get("corpus").asText());
    }

    Collections.sort(corpora);
    List<String> expected = new ArrayList<>(List.of("corpus_" + a, "corpus_" + b));
    Collections.sort(expected);
    assertEquals(
        expected,
        corpora,
        "both units of work must be represented, one each - not one of them twice");
  }

  @Test
  @DisplayName("7c an unrelated undeclared call does NOT adopt an open scope's documents")
  void case07c_an_open_scope_is_not_adopted_by_an_unrelated_call() throws Exception {
    String nonce = nonce();
    OkHttpClient http = client();

    ExecutorService pool = Executors.newFixedThreadPool(1);
    pool.submit(() -> null).get();

    // Endpoint A opens a scope around its own work: one scope, open, in
    // the whole process. An earlier build treated exactly that as
    // adoptable, which is the defect this test exists for.
    try (var scope =
        Retrieval.retrievalScope(List.of("A's document " + nonce), "corpus_a_" + nonce)) {

      // Something with nothing to do with A makes an undeclared model
      // call on another thread while A's scope is open.
      pool.submit(
              () -> {
                callModel(http, "an unrelated question");
                return null;
              })
          .get();

      // A's own work, handed off properly, still has its declaration.
      pool.submit(
              scope.wrap(
                  () -> {
                    callModel(http, "A's question");
                    return null;
                  }))
          .get();
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

    List<MockGateway.RecordedRequest> seen = gateway.requests();
    assertEquals(2, seen.size());

    assertEquals(
        "not_declared",
        seen.get(0).declaration(),
        "an unrelated call must not be attributed A's documents - "
            + "a merge is the one failure the customer cannot see");
    assertFalse(seen.get(0).bodyText().contains(nonce));

    assertEquals(
        "declared",
        seen.get(1).declaration(),
        "...and it must not have consumed A's declaration either: "
            + "adoption took as well as gave, so one wrong row cost a right one");
    assertTrue(seen.get(1).ragContext().get("docs").get(0).get("preview").asText().contains(nonce));
  }

  // ------------------------------------------------------------------ 8

  @Test
  @DisplayName("8 the discarded-declaration warning fires exactly once, with corpus and count")
  void case08_the_loss_is_audible_in_the_customers_own_logs() throws Exception {
    String corpus = "audible_corpus_" + nonce();
    OkHttpClient http = client();

    try (LogCapture logs = new LogCapture()) {
      ExecutorService pool = Executors.newFixedThreadPool(1);
      pool.submit(() -> null).get();

      Retrieval.declareRetrieval(List.of("doc one", "doc two", "doc three"), corpus);

      // Two model calls across the hand-off: the condition holds twice,
      // the warning must still be heard once.
      pool.submit(
              () -> {
                callModel(http, "first");
                callModel(http, "second");
                return null;
              })
          .get();
      pool.shutdown();
      assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

      List<String> warnings = logs.warnings(corpus);
      assertEquals(
          1,
          warnings.size(),
          "exactly one warning per corpus, however many calls lose it: " + warnings);

      String warning = warnings.get(0);
      assertTrue(warning.contains("corpus=" + corpus), warning);
      assertTrue(warning.contains("docs=3"), "must name the document count: " + warning);
      assertTrue(
          warning.contains("retrievalScope"),
          "must tell the customer what to do about it: " + warning);
      assertFalse(warning.contains("doc one"), "document text must never be logged: " + warning);
    }

    for (MockGateway.RecordedRequest request : gateway.requests()) {
      assertEquals("not_declared", request.declaration());
    }
  }

  @Test
  @DisplayName("8b a scope that ends with no model call warns too, and never over a clean run")
  void case08b_a_scope_closed_unconsumed_warns_but_a_clean_request_does_not() throws Exception {
    String corpus = "unused_corpus_" + nonce();

    try (LogCapture logs = new LogCapture()) {
      // Declared, then the context ended with no model call at all.
      try (var scope = Retrieval.retrievalScope(List.of("never used"), corpus)) {
        assertNotNull(scope);
      }
      assertEquals(1, logs.warnings(corpus).size());
      assertTrue(logs.warnings(corpus).get(0).contains("docs=1"));

      // ...and a request that simply declared nothing stays silent. A
      // warning on the ordinary path would train customers to ignore it.
      String anyLoss = "Shashu retrieval declaration was NOT sent";
      int before = logs.warnings(anyLoss).size();

      RetrievalTestAccess.reset();
      gateway = new MockGateway();
      callModel(client(), "no retrieval here");

      assertEquals(
          before, logs.warnings(anyLoss).size(), "a clean request must add no warning at all");
    }
  }

  // ------------------------------------------------------------------ 9

  @Test
  @DisplayName("9 oversize input is truncated to the caps and stays schema-valid")
  void case09_oversize_is_truncated_and_still_validates() throws Exception {
    List<Map<String, Object>> docs = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      docs.add(Map.of("id", "doc-" + i, "text", "x".repeat(4000)));
    }
    Retrieval.declareRetrieval(docs, "c".repeat(400), "q".repeat(9000), "manual");

    callModel(client(), "hello");

    JsonNode context = gateway.single().ragContext();
    assertEquals(List.of(), validate(context), "truncation must not produce an invalid payload");
    assertEquals(64, context.get("docs").size(), "docs.maxItems");
    assertEquals(256, context.get("corpus").asText().length(), "corpus.maxLength");
    assertEquals(4096, context.get("query").asText().length(), "query.maxLength");
    for (JsonNode doc : context.get("docs")) {
      assertEquals(2048, doc.get("preview").asText().length(), "preview.maxLength");
    }
    // Newest kept: the 100 documents are carried as the LAST 64.
    assertEquals("doc-36", context.get("docs").get(0).get("id").asText());
    assertEquals("doc-99", context.get("docs").get(63).get("id").asText());
  }

  // ----------------------------------------------------------------- 10

  @Test
  @DisplayName("10 zero cost: an empty bus sends bytes identical to the uninstrumented client")
  void case10_the_no_retrieval_path_is_byte_identical() throws Exception {
    // Uninstrumented: the mock gateway alone, no routing interceptor.
    MockGateway bare = new MockGateway();
    OkHttpClient plain = new OkHttpClient.Builder().addInterceptor(bare).build();
    post(
        plain, chatBody("what is the leave policy?"), "https://api.openai.com/v1/chat/completions");

    // Instrumented, nothing declared.
    callModel(client(), "what is the leave policy?");

    assertArrayEquals(
        bare.single().body(),
        gateway.single().body(),
        "with nothing declared the joiner must not re-serialise the body");
    assertEquals(bare.single().contentLengthHeader(), gateway.single().contentLengthHeader());
    assertEquals(bare.single().contentType(), gateway.single().contentType());
  }

  @Test
  @DisplayName("10b ...and it hands back the very same Request object")
  void case10b_the_joiner_returns_the_same_instance_when_nothing_is_declared() {
    Request request =
        new Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(
                RequestBody.create(
                    "{\"model\":\"gpt-4o-mini\"}", MediaType.get("application/json")))
            .build();

    assertSame(
        request,
        ai.shashu.sdk.internal.RetrievalJoiner.join(request),
        "identity is the strongest form of 'the body was not touched'");
  }

  // ----------------------------------------------------------------- 11

  @Test
  @DisplayName("11 SHASHU_DISABLE=1 makes the declaration, the bus and the joiner inert")
  void case11_the_kill_switch_makes_everything_a_noop() throws Exception {
    String nonce = nonce();
    RetrievalTestAccess.setEnv(Map.of("SHASHU_DISABLE", "1"));

    Retrieval.declareRetrieval(List.of("suppressed " + nonce), "disabled_corpus");
    try (var scope = Retrieval.retrievalScope(List.of("also suppressed " + nonce), "c")) {
      assertNotNull(scope);
      callModel(client(), "hello");
    }

    var seen = gateway.single();
    assertEquals("not_declared", seen.declaration());
    assertFalse(seen.hasShashuMetadata());
    assertFalse(seen.bodyText().contains(nonce));
  }

  @Test
  @DisplayName("11b a bus populated BEFORE the switch flipped is dropped, not sent")
  void case11b_the_switch_drops_an_already_carried_declaration() throws Exception {
    String nonce = nonce();
    Retrieval.declareRetrieval(List.of("declared before the flip " + nonce), "corpus_x");

    RetrievalTestAccess.setEnv(Map.of("SHASHU_DISABLE", "1"));
    callModel(client(), "hello");
    assertEquals("not_declared", gateway.single().declaration());

    // ...and it is gone, not merely skipped: switching back must not
    // release it onto the next request.
    gateway = new MockGateway();
    RetrievalTestAccess.setEnv(Map.of());
    callModel(client(), "hello again");
    assertEquals("not_declared", gateway.single().declaration());
    assertFalse(gateway.single().bodyText().contains(nonce));
  }

  // ----------------------------------------------------------------- 12
  // Case 12 (config) lives in RetrievalConfigTests, with the rest of the
  // address-resolution surface.

  // ------------------------------------------------------------- helpers

  /** A unique canary for one case. */
  private static String nonce() {
    return "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  /** An OkHttp client wired exactly as a customer's would be, plus the mock gateway. */
  private OkHttpClient client() {
    return new OkHttpClient.Builder()
        .addInterceptor(
            new ShashuRoutingInterceptor("sk-trustgate-test", MockGateway.BASE_URL, null))
        .addInterceptor(gateway)
        .build();
  }

  private static Map<String, Object> chatBody(String prompt) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", "gpt-4o-mini");
    body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
    return body;
  }

  private static void callModel(OkHttpClient http, String prompt) {
    post(http, chatBody(prompt));
  }

  private static void post(OkHttpClient http, Map<String, Object> body) {
    post(http, body, "https://api.openai.com/v1/chat/completions");
  }

  /** A vendor-shaped request, the way a patched customer client sends one. */
  private static void post(OkHttpClient http, Map<String, Object> body, String url) {
    try {
      byte[] encoded = MAPPER.writeValueAsBytes(body);
      Request request =
          new Request.Builder()
              .url(url)
              .header("Authorization", "Bearer sk-vendor-key")
              .post(RequestBody.create(encoded, MediaType.get("application/json")))
              .build();
      try (Response response = http.newCall(request).execute()) {
        assertEquals(200, response.code());
        assertTrue(isValidJson(response.body().string()));
      }
    } catch (IOException e) {
      throw new AssertionError("the mock gateway never does IO", e);
    }
  }

  private static boolean isValidJson(String text) {
    try {
      return MAPPER.readTree(text) != null;
    } catch (IOException e) {
      return false;
    }
  }
}
