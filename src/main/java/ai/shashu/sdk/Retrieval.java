package ai.shashu.sdk;

import ai.shashu.sdk.internal.RagContextBuilder;
import ai.shashu.sdk.internal.RetrievalBus;
import ai.shashu.sdk.internal.RetrievalScopeHandle;
import ai.shashu.sdk.internal.ShashuConfig;

/**
 * The retrieval declaration surface: tell Shashu what was bound into the prompt, wherever that
 * happened.
 *
 * <p>There are exactly two members here and there is no third, per {@code
 * contracts/sdk-surface.v1.md}. Both are no-ops when {@code SHASHU_DISABLE=1}. The overloads exist
 * because Java has no default arguments; the contract names that form explicitly.
 *
 * <p><b>Calling it.</b> The type is a holder &mdash; Java has no free functions, so the two entry
 * points need one. It is named for what it does rather than for the product, so the qualifier does
 * not stutter:
 *
 * <pre>{@code
 * Retrieval.declareRetrieval(hits, "policy_docs_v2");
 * }</pre>
 *
 * <h2>Which form to use &mdash; in Java this is not a nicety</h2>
 *
 * <p>Read this before choosing. Java's answer differs from every other Shashu SDK's, and the
 * difference is not cosmetic:
 *
 * <ul>
 *   <li><b>{@link #declareRetrieval} is enough when the retrieval and the model call happen on the
 *       same thread</b> &mdash; a servlet method, a CLI, a test. That is the common shape and it
 *       needs nothing extra.
 *   <li><b>The moment the work crosses a thread, a bare declaration is gone.</b> Not delayed, not
 *       attached to the wrong request: gone. Java's {@link ThreadLocal} does not follow an {@code
 *       ExecutorService.submit}, a {@code CompletableFuture}, or a Spring {@code @Async} call, and
 *       {@link InheritableThreadLocal} does not help because a pooled thread was created before the
 *       declaration was made. The bus documents the full measurement.
 *   <li><b>{@link #retrievalScope} plus {@code scope.wrap(...)} is how you cross that.</b> The
 *       scope binds the declaration to a unit of work rather than to the thread that declared it,
 *       and {@code wrap} is what carries it onto the thread the work actually runs on. Java has no
 *       carrier that does this by itself below JDK 21, so the hand-off is decorated at the hand-off
 *       &mdash; the same thing you already do for SLF4J's {@code MDC} or a {@code
 *       DelegatingSecurityContextExecutor}. Nothing propagates without a {@code wrap}, and that is
 *       deliberate: an SDK that guessed which request an undeclared call belonged to would
 *       sometimes stamp one request's documents onto another's audit record, and a customer can see
 *       a missing declaration but cannot see a wrong one.
 * </ul>
 *
 * <p>The frozen guarantee, in customer terms: <b>if the retrieval and the model call might not be
 * on the same thread, open a scope around them and hand the work off through {@code
 * scope.wrap(...)}.</b>
 *
 * <p><b>You will hear about it if you get this wrong.</b> When a model call goes out while a
 * declaration is stranded on another thread, the SDK logs one WARNING naming the corpus and the
 * document count, through {@link System.Logger} so it lands in whatever logging the host app
 * already configures. A silent loss and a request nobody declared anything for look identical at
 * the gateway, so the SDK refuses to let them look identical in your logs.
 *
 * <p>Nothing is sent until a model call happens. A declaration that no model call follows never
 * reaches the network.
 */
public final class Retrieval {

  private Retrieval() {}

  /**
   * Declare the documents bound into the prompt, wherever the retrieval happened.
   *
   * <p>The declaration is attached to the NEXT outgoing model call <b>from this thread</b>, by
   * whichever {@link ai.shashu.sdk.routing.ShashuRoutingInterceptor} sends it &mdash; you do not
   * have to be anywhere near that call:
   *
   * <pre>{@code
   * var hits = vectorStore.search(question, 5);
   * Retrieval.declareRetrieval(hits, "policy_docs_v2", question);
   * // ... any amount of unrelated code, on this thread ...
   * var answer = openAi.createChatCompletion(req);   // attached here
   * }</pre>
   *
   * <p>Cross a thread and it is lost, loudly &mdash; see the class docs and {@link
   * #retrievalScope}.
   *
   * @param docs the documents bound into the prompt: plain strings, Maps, records, arrays, or any
   *     object whose accessors spell the usual field names ({@code text}, {@code content}, {@code
   *     pageContent}, {@code preview}, plus {@code id}, {@code score}, {@code sourceUri}, {@code
   *     hash} and a {@code metadata} bag). A single document may be passed unwrapped. Anything the
   *     table cannot read text out of is dropped rather than stringified.
   * @param corpus the index the documents came from. Required by the contract &mdash; it is what
   *     the gateway records the documents as having come from.
   * @throws IllegalArgumentException on a blank {@code corpus}. A call-site mistake, and throwing
   *     here is the only place it can be seen.
   */
  public static void declareRetrieval(Object docs, String corpus) {
    declareRetrieval(docs, corpus, null, "manual");
  }

  /**
   * {@link #declareRetrieval(Object, String)} with the retrieval query, for the gateway's audit
   * record.
   *
   * @param docs as {@link #declareRetrieval(Object, String)}
   * @param corpus as {@link #declareRetrieval(Object, String)}
   * @param query the retrieval query that produced these documents
   */
  public static void declareRetrieval(Object docs, String corpus, String query) {
    declareRetrieval(docs, corpus, query, "manual");
  }

  /**
   * {@link #declareRetrieval(Object, String)} naming what produced the declaration.
   *
   * @param docs as {@link #declareRetrieval(Object, String)}
   * @param corpus as {@link #declareRetrieval(Object, String)}
   * @param query as {@link #declareRetrieval(Object, String, String)}
   * @param source a {@code rag_context.v1} {@code source} enum value
   * @throws IllegalArgumentException on a blank {@code corpus} or a {@code source} outside the
   *     contract's enum. Both are call-site mistakes, and the alternative is a payload the gateway
   *     rejects at the far end of a customer's stack.
   */
  public static void declareRetrieval(Object docs, String corpus, String query, String source) {
    if (ShashuConfig.isDisabled()) {
      return;
    }
    RetrievalBus.pushEvent(RagContextBuilder.newEvent(docs, corpus, query, source));
  }

  /**
   * {@link #declareRetrieval} bound to a unit of work rather than to the thread that called it.
   *
   * <pre>{@code
   * try (var scope = Retrieval.retrievalScope(hits, "policy_docs_v2", question)) {
   *     var answer = executor.submit(scope.wrap(() -> chain.invoke(question))).get();
   * }
   * }</pre>
   *
   * <p><b>The {@code wrap} is not optional.</b> The scope owns the declaration; {@code
   * scope.wrap(Runnable)}, {@code scope.wrap(Callable)} and {@code scope.wrapExecutor(Executor)}
   * are what carry it onto the thread that runs the work. Hand a task off without one and its model
   * call arrives undeclared &mdash; and says so in your logs.
   *
   * <p>That is the honest half of a trade the SDK makes deliberately. Nothing here infers which
   * unit of work an undeclared call belongs to, so two units of work in flight at once cannot bleed
   * into one another: every request carries the documents its own flow declared, or none. Losing a
   * declaration is a defect a customer can see in their own logs and fix with a {@code wrap};
   * attributing one request's documents to another request's audit record is a defect nobody can
   * see at all.
   *
   * <p>A declaration no model call consumed is reported when the scope closes &mdash; or, if
   * wrapped work is still running, when the last of it finishes.
   *
   * @param docs as {@link #declareRetrieval(Object, String)}
   * @param corpus as {@link #declareRetrieval(Object, String)}
   * @return a handle whose {@code close()} ends the scope and whose {@code wrap} methods carry it
   *     across a thread. Closing twice is harmless.
   */
  public static RetrievalScopeHandle retrievalScope(Object docs, String corpus) {
    return retrievalScope(docs, corpus, null, "manual");
  }

  /**
   * {@link #retrievalScope(Object, String)} with the retrieval query.
   *
   * @param docs as {@link #declareRetrieval(Object, String)}
   * @param corpus as {@link #declareRetrieval(Object, String)}
   * @param query as {@link #declareRetrieval(Object, String, String)}
   * @return as {@link #retrievalScope(Object, String)}
   */
  public static RetrievalScopeHandle retrievalScope(Object docs, String corpus, String query) {
    return retrievalScope(docs, corpus, query, "manual");
  }

  /**
   * {@link #retrievalScope(Object, String)} naming what produced the declaration.
   *
   * @param docs as {@link #declareRetrieval(Object, String)}
   * @param corpus as {@link #declareRetrieval(Object, String)}
   * @param query as {@link #declareRetrieval(Object, String, String)}
   * @param source as {@link #declareRetrieval(Object, String, String, String)}
   * @return as {@link #retrievalScope(Object, String)}
   * @throws IllegalArgumentException as {@link #declareRetrieval(Object, String, String, String)}
   */
  public static RetrievalScopeHandle retrievalScope(
      Object docs, String corpus, String query, String source) {
    if (ShashuConfig.isDisabled()) {
      return RetrievalBus.inertScope();
    }
    // Validate BEFORE seeding, so a bad call site throws without leaving
    // a scope the customer's try-with-resources would then unwind.
    var event = RagContextBuilder.newEvent(docs, corpus, query, source);
    return RetrievalBus.openScope(event);
  }
}
