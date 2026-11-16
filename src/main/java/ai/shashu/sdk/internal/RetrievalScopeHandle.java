package ai.shashu.sdk.internal;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * What {@code Retrieval.retrievalScope(...)} hands back: the handle a try-with-resources closes,
 * and the handle that carries the declaration across a thread hand-off. Visible across the {@code
 * ai.shashu.sdk} packages but not part of the public API.
 *
 * <p>It lives here rather than as a nested type on {@code Retrieval} because {@code
 * contracts/sdk-surface.v1.md} freezes that holder at <b>exactly two public members and no
 * third</b>, and a public nested class is a third.
 *
 * <h2>{@code wrap} is the whole propagation mechanism</h2>
 *
 * <p>In Java a declaration does not cross a hand-off on its own &mdash; {@link RetrievalBus} holds
 * the measurement of why. So the scope does not try to guess which request a task belongs to; it
 * lets the caller say, at the one place that knows:
 *
 * <pre>{@code
 * try (var scope = Retrieval.retrievalScope(docs, "policy_docs")) {
 *     executor.submit(scope.wrap(() -> callModel()));
 * }
 * }</pre>
 *
 * <p>{@link #wrap(Runnable)}, {@link #wrap(Callable)} and {@link #wrapExecutor(Executor)} bind the
 * scope's holder onto whatever thread runs the task and put that thread back as they found it when
 * it finishes &mdash; the same shape as SLF4J's {@code MDC}, Micrometer's context propagation and
 * Spring's {@code DelegatingSecurityContextExecutor}. Nothing else propagates: a task handed off
 * without one of these arrives undeclared, loudly.
 *
 * <p><b>The scope waits for its wrapped tasks.</b> {@link #close()} on a scope that still has
 * wrapped work outstanding restores the closing thread's binding but does not yet report or discard
 * the declaration &mdash; the fire-and-forget shape above closes the scope before the pooled task
 * has run, and destroying a declaration that is about to be consumed would be the worse defect. The
 * last of the closing and the final wrapped task settles it.
 */
public final class RetrievalScopeHandle implements AutoCloseable {

  /** What {@code TRUSTGATE_DISABLE=1} hands back: a scope that does nothing. */
  static final RetrievalScopeHandle INERT = new RetrievalScopeHandle(null, null);

  private final RetrievalBus.Holder opened;
  private final RetrievalBus.Holder previous;

  private final Object lock = new Object();
  private boolean closed;
  private int outstanding;

  RetrievalScopeHandle(RetrievalBus.Holder opened, RetrievalBus.Holder previous) {
    this.opened = opened;
    this.previous = previous;
  }

  /**
   * Carry this scope's declaration into {@code task}, wherever it runs.
   *
   * <p>The returned {@code Runnable} does exactly what {@code task} does, with the scope bound for
   * its duration. Hand it to an {@code ExecutorService}, a {@code CompletableFuture}, a Spring
   * {@code TaskExecutor} &mdash; anything that will run it on another thread.
   *
   * <p>Wrapping after the scope has closed hands back {@code task} itself: the declaration has
   * already been settled, and silently binding an empty scope would only look like propagation.
   *
   * @param task the work to run inside this scope
   * @return {@code task}, bound to this scope
   * @throws NullPointerException if {@code task} is null
   */
  public Runnable wrap(Runnable task) {
    Objects.requireNonNull(task, "task");
    if (!enlist()) {
      return task;
    }
    return () -> {
      RetrievalBus.Holder restore = RetrievalBus.bind(opened);
      try {
        task.run();
      } finally {
        RetrievalBus.unbind(restore);
        retire();
      }
    };
  }

  /**
   * {@link #wrap(Runnable)} for work that returns a value or throws.
   *
   * @param <T> the task's result type
   * @param task the work to run inside this scope
   * @return {@code task}, bound to this scope
   * @throws NullPointerException if {@code task} is null
   */
  public <T> Callable<T> wrap(Callable<T> task) {
    Objects.requireNonNull(task, "task");
    if (!enlist()) {
      return task;
    }
    return () -> {
      RetrievalBus.Holder restore = RetrievalBus.bind(opened);
      try {
        return task.call();
      } finally {
        RetrievalBus.unbind(restore);
        retire();
      }
    };
  }

  /**
   * An {@link Executor} that wraps everything handed to it, for the case where the hand-off is not
   * yours to decorate &mdash; a framework, or a {@code CompletableFuture} chain you only get to
   * pass an executor to:
   *
   * <pre>{@code
   * try (var scope = Retrieval.retrievalScope(docs, corpus)) {
   *     CompletableFuture.supplyAsync(this::callModel, scope.wrapExecutor(pool)).join();
   * }
   * }</pre>
   *
   * <p>It is a decorator, not a pool: {@code delegate} still decides where and when the work runs.
   * Named rather than a third {@code wrap} overload because {@code Executor} is itself a functional
   * interface, and an overload set a lambda could fall into the wrong member of is not worth the
   * symmetry.
   *
   * @param delegate the executor that actually runs the work
   * @return {@code delegate}, wrapping each command in this scope
   * @throws NullPointerException if {@code delegate} is null
   */
  public Executor wrapExecutor(Executor delegate) {
    Objects.requireNonNull(delegate, "delegate");
    return command -> delegate.execute(wrap(command));
  }

  /**
   * End the scope, restoring the binding it replaced. Closing twice is harmless; never closing it
   * leaks nothing beyond the thread it was opened on, though the declaration it still holds is then
   * reported as discarded when it is eventually closed.
   */
  @Override
  public void close() {
    boolean settle;
    synchronized (lock) {
      if (closed || opened == null) {
        closed = true;
        return;
      }
      closed = true;
      settle = outstanding == 0;
    }
    RetrievalBus.unbind(previous);
    if (settle) {
      RetrievalBus.settleScope(opened);
    }
  }

  /** Register a wrapped task. False means there is nothing to carry. */
  private boolean enlist() {
    if (opened == null) {
      return false;
    }
    synchronized (lock) {
      if (closed) {
        return false;
      }
      outstanding++;
      return true;
    }
  }

  /** A wrapped task finished. The last one out of a closed scope settles it. */
  private void retire() {
    boolean settle;
    synchronized (lock) {
      outstanding--;
      settle = closed && outstanding == 0;
    }
    if (settle) {
      RetrievalBus.settleScope(opened);
    }
  }
}
