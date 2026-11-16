package ai.shashu.sdk.internal;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ambient retrieval bus: what was retrieved, waiting for the model call. Visible across the
 * {@code ai.shashu.sdk} packages but not part of the public API.
 *
 * <h2>Why Java's carrier is different from the other three SDKs'</h2>
 *
 * <p>Python needed a mutable carrier, Node needed a callback wrap, .NET needed a synchronous
 * declare plus an explicit scope. <b>Java needs something none of them did, because in Java nothing
 * crosses the hand-off.</b> Measured on the pom's baseline (JDK 17); {@code
 * RetrievalBusPropagationTests} re-asserts every row:
 *
 * <table border="1">
 *   <caption>What survives a hand-off to a warm pool thread</caption>
 *   <tr><th>carrier</th><th>across {@code ExecutorService.submit}</th></tr>
 *   <tr><td>plain {@link ThreadLocal}</td>
 *       <td><b>LOST</b> &mdash; null on the worker</td></tr>
 *   <tr><td>{@link InheritableThreadLocal}, pool already warm</td>
 *       <td><b>LOST</b> &mdash; it copies at thread CREATION, and a pooled
 *           thread was created before the declaration</td></tr>
 *   <tr><td>{@code InheritableThreadLocal}, thread born after the set</td>
 *       <td>seen &mdash; and then <b>STALE</b>: the pooled thread keeps that
 *           first copy and serves a later, unrelated request with it</td></tr>
 *   <tr><td>a mutable holder reached through either ThreadLocal</td>
 *       <td><b>LOST</b> &mdash; the holder is fine, the lookup is what
 *           fails; it is reachable only if the task itself captured the
 *           reference</td></tr>
 *   <tr><td>{@code CompletableFuture.supplyAsync}, Spring {@code @Async}</td>
 *       <td><b>LOST</b> for a plain ThreadLocal &mdash; the same warm-pool
 *           shape</td></tr>
 *   <tr><td>{@code InheritableThreadLocal} on the common ForkJoinPool</td>
 *       <td><b>the environment changed under it</b>: lost on 17, and
 *           inherited-then-stale on 21 at 12 CPUs &mdash; but at one
 *           available processor the common pool drops to parallelism 0 and
 *           runs thread-per-task, so every call gets a freshly born worker
 *           and a different answer again. A carrier whose answer depends on
 *           the customer's JDK <i>and</i> their CPU count is not a
 *           carrier</td></tr>
 * </table>
 *
 * <p>{@code java.lang.ScopedValue} would change this and is deliberately NOT used: it needs JDK
 * 21-preview / 25, and this SDK's floor is 17.
 *
 * <p><b>The failure direction is loss, not leakage</b> &mdash; the request goes out undeclared
 * rather than carrying someone else's documents. That is the safer direction, and it is why {@code
 * InheritableThreadLocal} is rejected outright: its one "working" row is the stale one, which is
 * misattribution. But a silent loss and a clean inspection look identical at the gateway, so this
 * class makes the loss audible &mdash; see {@link #reportLostDeclarations()}.
 *
 * <p><b>How a declaration reaches a hand-off at all.</b> By being carried there explicitly, and by
 * nothing else. {@code retrievalScope} hands back a handle whose {@code wrap(Runnable)} / {@code
 * wrap(Callable)} bind the scope's holder onto whatever thread runs the task and restore that
 * thread's previous binding when it finishes. That is the pattern Java already uses for this exact
 * problem &mdash; SLF4J's {@code MDC}, Micrometer's context propagation, Spring's {@code
 * DelegatingSecurityContextExecutor}: the hand-off is decorated where the hand-off happens, because
 * that is the only place the runtime can be told which unit of work a task belongs to. Taking
 * consumes, so a given declaration reaches at most one request.
 *
 * <p><b>There is no ambient adoption, and there was.</b> An earlier build of this class let a
 * request whose own thread declared nothing adopt the events of the only open scope in the process.
 * That rule reads as safe and is not. In a service where endpoint A opens a scope and hands off, an
 * <i>unrelated</i> undeclared model call on another thread also satisfies "exactly one scope is
 * open" &mdash; so it adopts A's documents, and because adoption consumes, A's own call then
 * arrives undeclared. One request is attributed documents it never retrieved and one loses
 * documents it did, from a single unrelated call. It also misfires only under LIGHT load, when one
 * scope really is the only one open, so no load test finds it. This SDK prefers a miss to a merge:
 * a customer can see a miss and fix it, and cannot see a merge at all.
 *
 * <p><b>What this class will not do.</b> No disk, no network, no logging of document text &mdash;
 * not at DEBUG, not in an error path, and not in the lost-declaration warning, which carries only
 * the corpus and a count.
 *
 * <p>Caps are the contract's, not the gateway's current behaviour: previews are bound at 2048
 * characters ({@code rag_context.v1}'s {@code maxLength}) rather than the 600 the gateway truncates
 * at today.
 *
 * <p>Sibling of the Python SDK's {@code trustgate/_bus.py}, the Node SDK's {@code src/_bus.ts} and
 * the .NET SDK's {@code Internal/RetrievalBus.cs}.
 */
public final class RetrievalBus {

  private RetrievalBus() {}

  private static final Logger LOG = System.getLogger(RetrievalBus.class.getName());

  /**
   * At most this many separate declarations are carried at once. A caller that retrieves in a loop
   * would otherwise grow the bus without bound.
   */
  public static final int MAX_EVENTS = 8;

  /**
   * {@code rag_context.v1} {@code docs.maxItems}. Counted across ALL carried events, not per event,
   * because the joiner emits one {@code docs} array.
   */
  public static final int MAX_DOCS = 64;

  /** {@code rag_context.v1} {@code docs.items.properties.preview.maxLength}. */
  public static final int MAX_PREVIEW_CHARS = 2048;

  /** {@code rag_context.v1} {@code corpus.maxLength}. */
  public static final int MAX_CORPUS_CHARS = 256;

  /** {@code rag_context.v1} {@code query.maxLength}. */
  public static final int MAX_QUERY_CHARS = 4096;

  /**
   * How many distinct corpora may each raise one lost-declaration warning. A bound, not a budget:
   * on a service where every request loses its declaration the warning has to say so and then stop,
   * or the signal becomes the noise it was meant to cut through.
   */
  static final int MAX_WARNED_CORPORA = 64;

  /** How many unconsumed holders are tracked for the warning. */
  static final int MAX_TRACKED = 64;

  // ---------------------------------------------------------------- state

  /** The declaring thread's own holder. Plain, NOT inheritable - see the class table. */
  private static final ThreadLocal<Holder> CURRENT = new ThreadLocal<>();

  /**
   * Weakly-held holders carrying unconsumed events, oldest first. Weak so a holder on a thread that
   * has died is collectable; bounded so the tracking can never itself grow without limit.
   */
  private static final ConcurrentLinkedQueue<WeakReference<Holder>> TRACKED =
      new ConcurrentLinkedQueue<>();

  private static final AtomicInteger TRACKED_SIZE = new AtomicInteger();

  /** Corpora already warned about, so the warning cannot repeat. */
  private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

  // --------------------------------------------------------------- holder

  /**
   * A mutable list of declarations with a stable identity, so a {@code retrievalScope} can hand the
   * same holder to the thread that opened it and to the adoption path.
   */
  static final class Holder {
    private final List<RetrievalEvent> events = new ArrayList<>();
    private final List<MemoryEvent> memoryEvents = new ArrayList<>();

    synchronized boolean isEmpty() {
      return events.isEmpty() && memoryEvents.isEmpty();
    }

    synchronized void add(RetrievalEvent event) {
      events.add(event);
      trim(events);
    }

    synchronized void addMemory(MemoryEvent event) {
      memoryEvents.add(event);
      trimMemory(memoryEvents);
    }

    synchronized List<RetrievalEvent> takeAll() {
      if (events.isEmpty()) {
        return Collections.emptyList();
      }
      List<RetrievalEvent> taken = new ArrayList<>(events);
      events.clear();
      return taken;
    }

    synchronized List<MemoryEvent> takeMemory() {
      if (memoryEvents.isEmpty()) {
        return Collections.emptyList();
      }
      List<MemoryEvent> taken = new ArrayList<>(memoryEvents);
      memoryEvents.clear();
      return taken;
    }

    synchronized void clear() {
      events.clear();
      memoryEvents.clear();
    }

    synchronized void clearRetrieval() {
      events.clear();
    }

    synchronized void clearMemory() {
      memoryEvents.clear();
    }

    /** Corpus and document count only. Never any document text. */
    synchronized String[] summary() {
      if (events.isEmpty() && memoryEvents.isEmpty()) {
        return null;
      }
      List<String> corpora = new ArrayList<>();
      int docs = 0;
      for (RetrievalEvent event : events) {
        if (!corpora.contains(event.corpus())) {
          corpora.add(event.corpus());
        }
        docs += event.docs().size();
      }
      for (MemoryEvent event : memoryEvents) {
        if (!corpora.contains(event.store())) {
          corpora.add(event.store());
        }
        docs += event.items().size();
      }
      return new String[] {String.join("+", corpora), Integer.toString(docs)};
    }
  }

  // ------------------------------------------------------------ push/take

  /**
   * Append one declaration to the calling thread's holder, seeding one if this is the first
   * declaration here. A declaration with no readable documents is dropped rather than carried
   * &mdash; {@code docs} has {@code minItems: 1}.
   */
  public static void pushEvent(RetrievalEvent event) {
    if (event.docs().isEmpty()) {
      return;
    }
    Holder holder = CURRENT.get();
    if (holder == null) {
      holder = new Holder();
      CURRENT.set(holder);
    }
    holder.add(event);
    track(holder);
  }

  public static void pushMemoryEvent(MemoryEvent event) {
    if (event.items().isEmpty()) {
      return;
    }
    Holder holder = CURRENT.get();
    if (holder == null) {
      holder = new Holder();
      CURRENT.set(holder);
    }
    holder.addMemory(event);
    track(holder);
  }

  /**
   * The cheap guard the joiner runs first: is there anything at all that could make this request
   * differ from the uninstrumented one? Two reads, touching no request, no header and no body
   * &mdash; which is what makes the no-retrieval path free.
   *
   * <p>The second read is the tracking counter rather than this thread's holder, because a
   * declaration stranded on another thread is exactly what {@link #reportLostDeclarations()} has to
   * be reachable to report.
   */
  static boolean anythingPending() {
    Holder mine = CURRENT.get();
    if (mine != null && !mine.isEmpty()) {
      return true;
    }
    return TRACKED_SIZE.get() > 0;
  }

  /**
   * Whether this request has events it can actually attach: whether a declaration is bound to the
   * thread the model call is being made on, by having been declared here or carried here by {@code
   * RetrievalScopeHandle.wrap}. Nothing else counts &mdash; see the class docs on why there is no
   * ambient adoption.
   */
  static boolean hasEventsToAttach() {
    Holder mine = CURRENT.get();
    return mine != null && !mine.isEmpty();
  }

  /**
   * Take everything this request should carry and empty its source in one step, so retrieval
   * declared for request N can never reach request N+1 and a concurrent declaration cannot be lost
   * between a read and a clear.
   */
  static List<RetrievalEvent> takeForRequest() {
    Holder mine = CURRENT.get();
    if (mine != null) {
      List<RetrievalEvent> taken = mine.takeAll();
      if (mine.isEmpty()) {
        untrack(mine);
      }
      return taken;
    }
    return Collections.emptyList();
  }

  static List<MemoryEvent> takeMemoryForRequest() {
    Holder mine = CURRENT.get();
    if (mine != null) {
      List<MemoryEvent> taken = mine.takeMemory();
      if (mine.isEmpty()) {
        untrack(mine);
      }
      return taken;
    }
    return Collections.emptyList();
  }

  static void clearRetrievalForRequest() {
    Holder mine = CURRENT.get();
    if (mine != null) {
      mine.clearRetrieval();
      if (mine.isEmpty()) {
        untrack(mine);
      }
    }
  }

  static void clearMemoryForRequest() {
    Holder mine = CURRENT.get();
    if (mine != null) {
      mine.clearMemory();
      if (mine.isEmpty()) {
        untrack(mine);
      }
    }
  }

  /**
   * Drop everything this request would have carried. Used by the kill switch and by the
   * explicit-context path, where the ambient declaration lost this call and must not turn up on the
   * next one.
   */
  static void clearForRequest() {
    Holder mine = CURRENT.get();
    if (mine != null && !mine.isEmpty()) {
      mine.clear();
      untrack(mine);
    }
  }

  // --------------------------------------------------------------- scopes

  /**
   * Open a scope carrying {@code seed}, and hand back the handle that ends it. The public door
   * {@code Retrieval.retrievalScope} goes through.
   */
  public static RetrievalScopeHandle openScope(RetrievalEvent seed) {
    Holder previous = enterScope();
    Holder opened = CURRENT.get();
    pushEvent(seed);
    return new RetrievalScopeHandle(opened, previous);
  }

  public static RetrievalScopeHandle openMemoryScope(MemoryEvent seed) {
    Holder previous = enterScope();
    Holder opened = CURRENT.get();
    if (seed != null) {
      pushMemoryEvent(seed);
    }
    return new RetrievalScopeHandle(opened, previous);
  }

  /** The handle {@code TRUSTGATE_DISABLE=1} hands back. */
  public static RetrievalScopeHandle inertScope() {
    return RetrievalScopeHandle.INERT;
  }

  /**
   * Open a scope: install a fresh holder on this thread and return the previous binding so {@code
   * close()} can put it back.
   */
  static Holder enterScope() {
    Holder previous = CURRENT.get();
    CURRENT.set(new Holder());
    return previous;
  }

  /**
   * Bind {@code holder} to the calling thread and hand back what was bound before, so it can be
   * restored. This is what {@code RetrievalScopeHandle.wrap} runs on the worker thread: the
   * declaration is carried to the hand-off by the task itself, which is the only carrier Java
   * offers below JDK 21.
   */
  static Holder bind(Holder holder) {
    Holder previous = CURRENT.get();
    CURRENT.set(holder);
    return previous;
  }

  /** Undo a {@link #bind(Holder)}, putting the thread back as it was found. */
  static void unbind(Holder previous) {
    if (previous != null) {
      CURRENT.set(previous);
    } else {
      CURRENT.remove();
    }
  }

  /**
   * The scope is closed and no wrapped task is still running. Anything still in it was never
   * consumed by a model call, so it is reported before it is dropped &mdash; that is one of the two
   * shapes of loss this SDK has to make audible.
   */
  static void settleScope(Holder opened) {
    if (opened != null && !opened.isEmpty()) {
      warnDiscarded(opened, "the scope ended without a model call");
      opened.clear();
    }
    untrack(opened);
  }

  // ------------------------------------------------------ the audible loss

  /**
   * Called when a model call went out carrying no declaration while an unconsumed declaration
   * existed on some other thread. That is the hand-off loss, and it is the one thing a customer
   * cannot see from outside: at the gateway the request is indistinguishable from one where nobody
   * declared anything.
   *
   * <p>The declaration is <b>not</b> discarded here. On a busy service the other thread may be
   * about to make its own model call, and destroying a valid declaration to tidy up a warning would
   * be the worse defect. This is a report, not a purge, and it is rate-limited to one warning per
   * corpus so it cannot become noise.
   */
  static void reportLostDeclarations() {
    Holder mine = CURRENT.get();
    for (WeakReference<Holder> ref : TRACKED) {
      Holder holder = ref.get();
      if (holder == null || holder == mine || holder.isEmpty()) {
        continue;
      }
      warnDiscarded(holder, "a model call was made on another thread, which could not see it");
    }
  }

  private static void warnDiscarded(Holder holder, String how) {
    String[] summary = holder.summary();
    if (summary == null) {
      return;
    }
    String corpus = summary[0];
    if (WARNED.size() >= MAX_WARNED_CORPORA || !WARNED.add(corpus)) {
      return;
    }
    LOG.log(
        Level.WARNING,
        "Shashu retrieval declaration was NOT sent: corpus="
            + corpus
            + " docs="
            + summary[1]
            + " - "
            + how
            + ". Java's ThreadLocal does"
            + " not cross a thread-pool hand-off, so the request reached the"
            + " gateway marked as undeclared. Open"
            + " Retrieval.retrievalScope(docs, corpus) around the work and hand"
            + " the task off through scope.wrap(...), which carries the"
            + " declaration onto the thread that runs it."
            + " (warned once per corpus)");
  }

  // ------------------------------------------------------------- tracking

  private static void track(Holder holder) {
    for (WeakReference<Holder> ref : TRACKED) {
      if (ref.get() == holder) {
        return;
      }
    }
    TRACKED.add(new WeakReference<>(holder));
    if (TRACKED_SIZE.incrementAndGet() > MAX_TRACKED) {
      prune();
    }
  }

  private static void untrack(Holder holder) {
    for (Iterator<WeakReference<Holder>> it = TRACKED.iterator(); it.hasNext(); ) {
      Holder tracked = it.next().get();
      if (tracked == null || tracked == holder) {
        it.remove();
        TRACKED_SIZE.decrementAndGet();
      }
    }
  }

  /** Drop cleared and emptied references, then the oldest, until under the cap. */
  private static void prune() {
    for (Iterator<WeakReference<Holder>> it = TRACKED.iterator(); it.hasNext(); ) {
      Holder holder = it.next().get();
      if (holder == null || holder.isEmpty()) {
        it.remove();
        TRACKED_SIZE.decrementAndGet();
      }
    }
    while (TRACKED_SIZE.get() > MAX_TRACKED && TRACKED.poll() != null) {
      TRACKED_SIZE.decrementAndGet();
    }
  }

  // ------------------------------------------------------------ test seam

  /**
   * Return every carrier to its start state. Test seam: the bus is process-wide by nature, so a
   * test that did not reset it would measure the previous test's declarations.
   */
  static void resetForTesting() {
    Holder mine = CURRENT.get();
    if (mine != null) {
      mine.clear();
    }
    CURRENT.remove();
    TRACKED.clear();
    TRACKED_SIZE.set(0);
    WARNED.clear();
  }

  // ------------------------------------------------------------- trimming

  /**
   * Enforce {@link #MAX_EVENTS} then {@link #MAX_DOCS}, keeping the newest of each: the declaration
   * nearest the model call is the one that describes the prompt being sent. Applied uniformly, that
   * means an oversized single declaration keeps its tail &mdash; 100 documents in one call are
   * carried as the last 64.
   */
  private static void trimMemory(List<MemoryEvent> events) {
    while (events.size() > MAX_EVENTS) {
      events.remove(0);
    }
    int budget = MAX_DOCS;
    for (int i = events.size() - 1; i >= 0; i--) {
      MemoryEvent event = events.get(i);
      int size = event.items().size();
      if (budget <= 0) {
        events.remove(i);
      } else if (size > budget) {
        events.set(i, event.withItems(new ArrayList<>(event.items().subList(size - budget, size))));
        budget = 0;
      } else {
        budget -= size;
      }
    }
  }

  private static void trim(List<RetrievalEvent> events) {
    while (events.size() > MAX_EVENTS) {
      events.remove(0);
    }

    int budget = MAX_DOCS;
    for (int i = events.size() - 1; i >= 0; i--) {
      RetrievalEvent event = events.get(i);
      int size = event.docs().size();
      if (budget <= 0) {
        events.remove(i);
      } else if (size > budget) {
        events.set(i, event.withDocs(new ArrayList<>(event.docs().subList(size - budget, size))));
        budget = 0;
      } else {
        budget -= size;
      }
    }
  }

  /** Cap names to their values, for the contract fences. */
  static Map<String, Integer> caps() {
    Map<String, Integer> caps = new LinkedHashMap<>();
    caps.put("MAX_EVENTS", MAX_EVENTS);
    caps.put("MAX_DOCS", MAX_DOCS);
    caps.put("MAX_PREVIEW_CHARS", MAX_PREVIEW_CHARS);
    caps.put("MAX_CORPUS_CHARS", MAX_CORPUS_CHARS);
    caps.put("MAX_QUERY_CHARS", MAX_QUERY_CHARS);
    return caps;
  }
}
