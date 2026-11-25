package ai.shashu.sdk.chat;

import ai.shashu.sdk.internal.ChatCompletionChunkStream;
import ai.shashu.sdk.internal.ShashuApi;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chat-completions endpoint. {@code POST /v1/chat/completions}.
 *
 * <p>Two methods:
 *
 * <ul>
 *   <li>{@link #create(ChatCompletionRequest)} — non-streaming, returns the full {@link
 *       ChatCompletionResponse}.
 *   <li>{@link #createStream(ChatCompletionRequest)} — streaming, returns a closeable {@link
 *       ChatCompletionChunkStream} that yields chunks as they arrive over SSE.
 * </ul>
 */
public final class ChatCompletions {

  private static final Logger LOG = System.getLogger(ChatCompletions.class.getName());

  /** One-time process-wide guard for the inert-streaming warning. */
  private static final AtomicBoolean STREAMING_WARNED = new AtomicBoolean(false);

  private final ShashuApi api;

  /** Resource that posts {@code /v1/chat/completions}. */
  public ChatCompletions(ShashuApi api) {
    this.api = api;
  }

  /**
   * Send a non-streaming chat completion request. Returns the OpenAI-compatible response shape —
   * drop-in replacement for the OpenAI Java client's chat-completion call.
   *
   * <p>If {@code request.stream() == true} this method throws — use {@link #createStream} instead.
   */
  public ChatCompletionResponse create(ChatCompletionRequest request) {
    return create(request, null);
  }

  /**
   * Send a non-streaming chat completion request.
   *
   * @param sessionId per-request session. An instruction that wins over a captured inbound A2A
   *     session and the construction-time default; applies to this request only. See {@code
   *     contracts/sdk-surface.v1.md} "Which session a request joins".
   */
  public ChatCompletionResponse create(ChatCompletionRequest request, String sessionId) {
    Objects.requireNonNull(request, "request");
    if (Boolean.TRUE.equals(request.stream())) {
      throw new IllegalStateException(
          "request.stream is true — call createStream(...) instead of create(...).");
    }
    return api.post("/v1/chat/completions", request, ChatCompletionResponse.class, sessionId);
  }

  /**
   * Send a streaming chat completion request. The returned {@link ChatCompletionChunkStream} is an
   * {@link Iterable} of {@link ChatCompletionChunk} that pulls chunks from the underlying
   * Server-Sent Events response as the caller iterates.
   *
   * <p><strong>NOTICE — streaming is INERT against a Shashu gateway today.</strong> The gateway
   * always calls the upstream provider in non-streaming mode and buffers the full response so its
   * deferred-deanonymize / egress-scan step can run on the complete body; it emits no chat SSE
   * regardless of the {@code stream} flag or DLP settings. This method therefore does not currently
   * deliver incremental tokens — against today's gateway the buffered JSON arrives as a single (or
   * no) chunk. The method is retained (not removed) for forward compatibility once gateway
   * streaming lands. A one-time warning is logged via {@link System.Logger} on first use.
   *
   * <p><strong>Always close the returned stream</strong> — preferably via try-with-resources — to
   * release the underlying OkHttp connection. Failing to close leaks a connection from the pool.
   *
   * <pre>{@code
   * try (ChatCompletionChunkStream stream = client.chat().completions().createStream(request)) {
   *     for (ChatCompletionChunk chunk : stream) {
   *         System.out.print(chunk.choices().get(0).delta().content());
   *     }
   * }
   * }</pre>
   *
   * <p>Mid-stream errors from the gateway surface as {@link ai.shashu.sdk.ShashuException} thrown
   * from the iterator's {@code next()}.
   */
  public ChatCompletionChunkStream createStream(ChatCompletionRequest request) {
    return createStream(request, null);
  }

  /**
   * Send a streaming chat completion request.
   *
   * @param sessionId per-request session. An instruction that wins over a captured inbound A2A
   *     session and the construction-time default; applies to this request only.
   */
  public ChatCompletionChunkStream createStream(ChatCompletionRequest request, String sessionId) {
    Objects.requireNonNull(request, "request");
    warnStreamingInert();
    // Force stream=true so the gateway opens an SSE channel even if the
    // caller forgot to set it. Idempotent if already true. We carry
    // over rag_context + free-form shashu_metadata extras since
    // they're scanner-critical control-plane fields.
    ChatCompletionRequest.Builder b =
        ChatCompletionRequest.builder()
            .model(request.model())
            .messages(request.messages())
            .temperature(request.temperature())
            .topP(request.topP())
            .maxTokens(request.maxTokens())
            .stop(request.stop())
            .stream(true);
    if (request.ragContext() != null) {
      b.ragContext(request.ragContext());
    }
    if (request.trustGateMetadataExtras() != null) {
      for (var e : request.trustGateMetadataExtras().entrySet()) {
        b.trustGateMetadata(e.getKey(), e.getValue());
      }
    }
    return api.postStream("/v1/chat/completions", b.build(), sessionId);
  }

  /**
   * Log a one-time, process-wide warning that streaming is not yet supported end-to-end by the
   * gateway. Routed through {@link System.Logger} (WARNING) so it surfaces without an SLF4J/log4j
   * binding and integrates with whatever logging the host app configures.
   */
  private static void warnStreamingInert() {
    if (STREAMING_WARNED.compareAndSet(false, true)) {
      LOG.log(
          Level.WARNING,
          "Streaming is not yet supported end-to-end: the gateway buffers "
              + "/v1/chat/completions and returns full JSON regardless of the stream flag, so no "
              + "incremental tokens are produced. createStream(...) parses Server-Sent Events; "
              + "it does not fall back to buffered JSON. (warned once per process)");
    }
  }
}
