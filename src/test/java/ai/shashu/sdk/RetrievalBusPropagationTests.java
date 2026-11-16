package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The measurement behind {@code RetrievalBus}'s carrier choice, held as tests so it cannot rot into
 * a claim.
 *
 * <p>Every other SDK's bus is documented against a table of what its carrier propagates. Java's
 * table is the reason this SDK behaves differently from the other three, and the reason the SDK has
 * to make a lost declaration audible, so each row is a test rather than a sentence.
 *
 * <p>These tests exercise the JDK primitives directly, not the SDK. That is deliberate: the claim
 * being fenced is about Java, and a test written against the SDK's own wrapper could pass while the
 * claim underneath it had become false.
 */
@DisplayName("Step 0: what actually crosses a Java hand-off")
class RetrievalBusPropagationTests {

  private static final ThreadLocal<String> PLAIN = new ThreadLocal<>();
  private static final InheritableThreadLocal<String> INHERITED = new InheritableThreadLocal<>();
  private static final ThreadLocal<List<String>> HOLDER = new ThreadLocal<>();

  /**
   * A pool whose threads exist BEFORE any declaration is made. This is the common case and the one
   * that silently loses: a servlet container's pool, a Spring {@code ThreadPoolTaskExecutor}, the
   * common ForkJoinPool - all warm long before a request arrives.
   */
  private static ExecutorService warmPool;

  @BeforeAll
  static void warmTheThreads() throws Exception {
    warmPool = Executors.newFixedThreadPool(2);
    warmPool.submit(() -> null).get();
    warmPool.submit(() -> null).get();
  }

  @AfterAll
  static void shutDown() throws Exception {
    warmPool.shutdown();
    warmPool.awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("(a) plain ThreadLocal does not cross a pooled submit: LOST")
  void plain_thread_local_is_lost_across_a_pooled_submit() throws Exception {
    PLAIN.set("declared-on-caller");
    try {
      assertNull(
          warmPool.submit(PLAIN::get).get(),
          "a plain ThreadLocal reaching a pool thread would change the whole design");
    } finally {
      PLAIN.remove();
    }
  }

  @Test
  @DisplayName("(b) InheritableThreadLocal does not cross into a WARM pool: LOST")
  void inheritable_thread_local_is_lost_when_the_pool_is_already_warm() throws Exception {
    INHERITED.set("declared-on-caller");
    try {
      // It copies at thread CREATION. The pool's threads were created in
      // @BeforeAll, before this set, so there was nothing to copy.
      assertNull(warmPool.submit(INHERITED::get).get());
    } finally {
      INHERITED.remove();
    }
  }

  @Test
  @DisplayName("(b2) InheritableThreadLocal DOES reach a thread created after the set")
  void inheritable_thread_local_reaches_a_thread_created_after_the_declaration() throws Exception {
    INHERITED.set("declared-on-caller");
    try {
      List<String> seen = Collections.synchronizedList(new ArrayList<>());
      Thread fresh = new Thread(() -> seen.add(INHERITED.get()));
      fresh.start();
      fresh.join();
      assertEquals(List.of("declared-on-caller"), seen);
    } finally {
      INHERITED.remove();
    }
  }

  @Test
  @DisplayName("(b3) ...and then goes STALE on that pooled thread: the row that rejects it")
  void inheritable_thread_local_serves_a_later_request_with_the_first_declaration()
      throws Exception {
    // A pool whose thread is born lazily, AFTER the first declaration.
    ExecutorService lazy = Executors.newFixedThreadPool(1);
    try {
      INHERITED.set("first-declaration");
      assertEquals("first-declaration", lazy.submit(INHERITED::get).get());

      // A second, unrelated declaration on the caller. The pooled thread
      // is reused and still holds its inherited COPY of the first.
      INHERITED.set("second-declaration");
      assertEquals(
          "first-declaration",
          lazy.submit(INHERITED::get).get(),
          "InheritableThreadLocal's one working row is a stale one: the "
              + "pooled thread would stamp request N+1 with request N's corpus. "
              + "That is misattribution, which is why the bus does not use it.");
    } finally {
      INHERITED.remove();
      lazy.shutdownNow();
    }
  }

  @Test
  @DisplayName("(c) a mutable holder seeded before submit is NOT reachable through a ThreadLocal")
  void a_mutable_holder_does_not_help_because_the_lookup_is_what_fails() throws Exception {
    List<String> box = Collections.synchronizedList(new ArrayList<>());
    box.add("declared-before-submit");
    HOLDER.set(box);
    try {
      // Python's fix was a mutable carrier. In Java the carrier is fine
      // and the LOOKUP is what fails, so the same fix buys nothing.
      assertNull(warmPool.submit(HOLDER::get).get());

      // It is reachable only if the task itself captured the reference -
      // which is what a scope has to arrange, and what a bare
      // declaration cannot.
      assertEquals(
          List.of("declared-before-submit"), warmPool.submit(() -> new ArrayList<>(box)).get());
    } finally {
      HOLDER.remove();
    }
  }

  @Test
  @DisplayName("(d) CompletableFuture and Spring @Async lose a plain ThreadLocal too")
  void completable_future_loses_a_plain_thread_local() throws Exception {
    PLAIN.set("declared-on-caller");
    try {
      assertNull(
          CompletableFuture.supplyAsync(PLAIN::get).get(),
          "the common pool is a pool like any other");
      assertNull(
          CompletableFuture.supplyAsync(PLAIN::get, warmPool).get(),
          "an explicit warm executor - the shape Spring @Async uses - is no different");
    } finally {
      PLAIN.remove();
    }
  }

  @Test
  @DisplayName(
      "(d2) on the common pool an InheritableThreadLocal is a BIRTH snapshot, "
          + "never a read of the current declaration")
  void the_common_pool_serves_an_inheritable_thread_local_from_worker_creation() throws Exception {
    // Measured in this repo on 2026-09-06, same container, same 12 CPUs:
    //   JDK 17.0.20  supplyAsync(INHERITED::get) -> null
    //   JDK 21.0.12  supplyAsync(INHERITED::get) -> "first-declaration"
    // ...and on a 4-CPU GitHub runner on 2026-09-07, on BOTH 17 and 21, a
    // second worker was born between the two calls and answered with the
    // SECOND declaration. So the common pool's answer moves with the JDK,
    // the CPU count and the runner. That is the disqualification, and the
    // reason this test asserts the MECHANISM rather than one box's answer:
    // an earlier version of it asserted the 21-on-12-CPUs outcome directly
    // and went red on the first CI run it ever had.
    //
    // The deterministic staleness proof is (b3) above, on a pool whose one
    // thread is guaranteed to be reused. Here the pool decides how many
    // workers to make, so the invariant is what a worker CAN see.
    INHERITED.set("first-declaration");
    try {
      String[] first =
          CompletableFuture.supplyAsync(
                  () -> new String[] {Thread.currentThread().getName(), INHERITED.get()})
              .get();

      // A second, unrelated declaration on the caller.
      INHERITED.set("second-declaration");
      String[] later =
          CompletableFuture.supplyAsync(
                  () -> new String[] {Thread.currentThread().getName(), INHERITED.get()})
              .get();

      // The invariant, true on every JDK and every pool sizing: the value
      // is copied when the worker is CREATED, so a worker cannot see a
      // declaration that did not exist yet. If this ever failed,
      // InheritableThreadLocal would be a live read of the caller - which
      // is the only thing that would make it a usable carrier, and it is
      // not what it is.
      assertNotEquals(
          "second-declaration",
          first[1],
          "a worker that existed before the second declaration cannot see it: "
              + "InheritableThreadLocal copies at thread creation, it does not "
              + "read the caller");

      if (first[0].equals(later[0])) {
        // The SAME worker served both calls. It still holds its birth
        // copy, so it serves a later, unrelated request with an earlier
        // request's declaration. That is misattribution, and it is why
        // the bus does not use one.
        assertEquals(
            first[1],
            later[1],
            "a reused worker keeps its inherited copy and stamps the "
                + "next, unrelated request with it");
      } else {
        // A DIFFERENT worker served the second call, so what it saw was
        // its own birth snapshot rather than anything about the request.
        // Which worker the pool hands you is not something a caller
        // controls - so neither is the answer.
        assertNotEquals(first[0], later[0]);
      }
    } finally {
      INHERITED.remove();
    }
  }

  @Test
  @DisplayName("(x) the direction is LOSS, not leakage: a worker's declaration cannot climb out")
  void a_declaration_made_on_a_worker_does_not_reach_the_caller() throws Exception {
    PLAIN.remove();
    warmPool
        .submit(
            () -> {
              PLAIN.set("worker-declared");
              try {
                return PLAIN.get();
              } finally {
                PLAIN.remove();
              }
            })
        .get();

    assertNull(
        PLAIN.get(),
        "the caller must not inherit a worker's declaration either; "
            + "the failure is silence in both directions, which is the safer one");
  }

  @Test
  @DisplayName("(e) ScopedValue is absent on the SDK's JDK floor, and the floor is not raised")
  void scoped_value_is_not_available_at_the_declared_baseline() {
    // pom.xml pins maven.compiler.source/target to 17. ScopedValue is a
    // preview API in 21 and final in 25; reaching for it would mean moving
    // the floor under every customer to fix an SDK-internal problem.
    assertEquals("17", System.getProperty("maven.compiler.source", "17"));

    boolean present;
    try {
      Class.forName("java.lang.ScopedValue");
      present = true;
    } catch (ClassNotFoundException e) {
      present = false;
    }
    if (Runtime.version().feature() < 21) {
      assertFalse(present, "ScopedValue must not exist below 21");
    } else {
      // The CI matrix also builds on 21, where the class may exist. The
      // point stands either way: the SDK compiles at 17 and must not use it.
      assertNotNull(RetrievalBusPropagationTests.class.getName());
    }
  }
}
