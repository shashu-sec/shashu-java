package ai.shashu.sdk.internal;

import ai.shashu.sdk.ShashuClient;
import ai.shashu.sdk.ShashuClientOptions;
import ai.shashu.sdk.ShashuContentFilterException;
import ai.shashu.sdk.ShashuException;
import ai.shashu.sdk.chat.ChatCompletionChunk;
import ai.shashu.sdk.chat.ChatCompletionResponse;
import ai.shashu.sdk.context.WorkflowContext;
import ai.shashu.sdk.internal.CredentialResolver.CredentialInterceptor;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

/**
 * Internal HTTP plumbing shared by all resource classes. Centralises retry policy, JSON
 * serialisation, and Shashu's error-shape parsing. Visible across the {@code ai.shashu.sdk}
 * packages but not part of the public API.
 */
public final class ShashuApi {

  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  /**
   * Field-based Jackson configuration. The SDK's POJOs use record-style accessors ({@code role()} /
   * {@code content()}) instead of JavaBean {@code getRole()} / {@code getContent()}, so Jackson's
   * default getter discovery doesn't find them. We tell Jackson to introspect fields directly —
   * fields are already annotated with {@code @JsonProperty} where the wire name differs from the
   * field name. The {@code SNAKE_CASE} strategy is kept for incoming responses (so {@code
   * first_seen} maps to {@code firstSeen}, etc.) but is overridden by explicit
   * {@code @JsonProperty} annotations.
   *
   * <p>FAIL_ON_UNKNOWN_PROPERTIES is DISABLED so the SDK tolerates response fields it doesn't yet
   * model — forward-compatibility (the gateway/providers add fields like {@code system_fingerprint}
   * / {@code service_tier} / {@code prompt_filter_results} over time, and a 200 must never surface
   * as a client-side parse error). This is the CENTRAL mapper every response type is read through
   * (non-streaming {@link #post} and the streaming {@link ChatCompletionChunkStream}), so all DTOs
   * inherit the tolerance — no per-DTO {@code @JsonIgnoreProperties} needed.
   */
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
          .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
          .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

  private final OkHttpClient http;
  private final ShashuClientOptions options;

  /**
   * This agent's OWN id (ADR-007 §4.1). Seeded from {@link ShashuClientOptions#agentId()} and
   * refreshed from each gateway response's {@code x-shashu-agent-id} header (read on EVERY
   * response, including non-2xx). Used by {@code ShashuClient.buildInvocationHeaders} to stamp
   * caller = self on a downstream invocation — never sent as the gateway call's caller. Advisory
   * lineage, never auth. {@code volatile} because a long-lived client is shared across threads.
   */
  private volatile String agentId;

  /**
   * Construction-time session default. Minted once when {@link ShashuClientOptions#sessionId()} is
   * omitted.
   */
  private final String sessionId;

  public ShashuApi(ShashuClientOptions options) {
    this.options = options;
    this.agentId = options.agentId();
    this.sessionId = mintSession(options.sessionId());
    this.http =
        withCredential(
            new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .callTimeout(options.timeoutMillis(), TimeUnit.MILLISECONDS)
                .build());
  }

  /** Used by tests so they can inject a MockWebServer-backed client. */
  public ShashuApi(ShashuClientOptions options, OkHttpClient http) {
    this.options = options;
    this.agentId = options.agentId();
    this.sessionId = mintSession(options.sessionId());
    this.http = withCredential(http);
  }

  private OkHttpClient withCredential(OkHttpClient http) {
    return http.newBuilder()
        .addNetworkInterceptor(SuppressOkHttpBusyFollowUp.INSTANCE)
        .addInterceptor(new CredentialInterceptor(options.credential()))
        .build();
  }

  /**
   * OkHttp's {@code RetryAndFollowUpInterceptor} immediately replays a 503 when {@code Retry-After}
   * is {@code 0}. That extra hop stacks on {@link #post}'s busy-retry loop (max_retries=0 → 2
   * requests; give-up → 6). Strip only the zero-delay header from the in-memory response so OkHttp
   * does not follow up; keep the invitation and the delay for this SDK via {@link
   * #SUPPRESSED_RETRY_AFTER}.
   */
  static final String SUPPRESSED_RETRY_AFTER = "OkHttp-Suppressed-Retry-After";

  static final class SuppressOkHttpBusyFollowUp implements Interceptor {
    static final SuppressOkHttpBusyFollowUp INSTANCE = new SuppressOkHttpBusyFollowUp();

    @Override
    public Response intercept(Chain chain) throws IOException {
      Response response = chain.proceed(chain.request());
      if (response.code() != 503) {
        return response;
      }
      String retryAfter = response.header("Retry-After");
      Long millis = parseRetryAfterMillis(retryAfter);
      if (millis == null || millis != 0L) {
        return response;
      }
      Response.Builder builder =
          response
              .newBuilder()
              .removeHeader("Retry-After")
              .header(SUPPRESSED_RETRY_AFTER, retryAfter);
      if (response.header("x-should-retry") == null) {
        builder.header("x-should-retry", "true");
      }
      return builder.build();
    }
  }

  /**
   * This agent's own id, as known so far — seeded from the {@code agentId} option and refreshed
   * from each gateway response. Null until known.
   */
  public String agentId() {
    return agentId;
  }

  /**
   * Construction-time session default. Minted once when {@link ShashuClientOptions#sessionId()} is
   * omitted.
   */
  public String sessionId() {
    return sessionId;
  }

  private static String mintSession(String configured) {
    if (configured == null || configured.isBlank()) {
      return UUID.randomUUID().toString().replace("-", "");
    }
    return configured.trim();
  }

  /**
   * Cache this agent's own id from the gateway's {@code x-shashu-agent-id} response header, when
   * present. Called on EVERY response (success + error) so even a blocked response seeds the
   * self-id for the next A2A invocation. Fail-soft: never throws.
   */
  private void captureAgentId(Response response) {
    String aid = response.header("x-shashu-agent-id");
    if (aid != null && !aid.isBlank()) {
      this.agentId = aid;
    }
  }

  /** Join declareRetrieval onto the payload ONCE, before the retry loop re-sends it. */
  private byte[] joinDeclarationOnce(String url, byte[] payload) {
    Request probe = new Request.Builder().url(url).post(RequestBody.create(payload, JSON)).build();
    Request joined = RetrievalJoiner.join(probe);
    if (joined == probe) {
      return payload;
    }
    RequestBody body = joined.body();
    if (body == null) {
      return payload;
    }
    try {
      Buffer buf = new Buffer();
      body.writeTo(buf);
      return buf.readByteArray();
    } catch (IOException e) {
      return payload;
    }
  }

  public <Req, Resp> Resp post(String path, Req body, Class<Resp> responseType) {
    return post(path, body, responseType, null);
  }

  public <Req, Resp> Resp post(String path, Req body, Class<Resp> responseType, String sessionId) {
    String url = options.baseUrl().replaceAll("/$", "") + path;
    byte[] payload;
    try {
      payload = MAPPER.writeValueAsBytes(body);
    } catch (IOException e) {
      throw new ShashuException("Failed to serialise request body", e);
    }
    payload = joinDeclarationOnce(url, payload);
    RequestBody frozenBody = RequestBody.create(payload, JSON);
    Request.Builder proto =
        new Request.Builder()
            .url(url)
            .header("User-Agent", options.userAgent())
            .header("Content-Type", "application/json")
            .post(frozenBody);
    if (options.organizationId() != null && !options.organizationId().isBlank()) {
      proto.header("X-Org-Id", options.organizationId());
    }
    applyContractVersion(proto);
    applyWorkflowContext(proto, sessionId);
    Request frozen = proto.build();

    int attempt = 0;
    while (true) {

      // Delay to wait before the NEXT attempt. Assigned on every path
      // that falls through to the retry, then consumed below.
      long delayMillis;
      try (Response response = http.newCall(frozen.newBuilder().build()).execute()) {
        // Cache self-id from EVERY response (incl. non-2xx) before any throw, so a
        // blocked response still seeds caller-agent-id for the next A2A invocation.
        captureAgentId(response);
        int status = response.code();
        String responseBody = response.body() != null ? response.body().string() : "";
        Map<String, String> headers = captureShashuHeaders(response);

        if (response.isSuccessful()) {
          if (responseBody.isEmpty()) {
            throw new ShashuException(
                "Server returned an empty body where a JSON object was expected.");
          }
          try {
            Resp parsed = MAPPER.readValue(responseBody, responseType);
            if (parsed instanceof ChatCompletionResponse chat) {
              chat.attachShashuHeaders(headers);
              throwIfCompatibleBlock(chat, responseBody, headers);
            }
            return parsed;
          } catch (ShashuException e) {
            throw e;
          } catch (IOException e) {
            throw new ShashuException("Failed to parse response body", e);
          }
        }

        // Conservative retry policy (aligned with the Python/Node/.NET
        // SDKs): retry 429 always; retry 5xx ONLY under the legacy
        // opt-out. 5xx is excluded by default because the gateway
        // already retries upstream providers with backoff + circuit
        // breakers — SDK-side 5xx retries stack on top and amplify load
        // during an incident. 4xx (incl. 403 policy blocks) never retry.
        boolean is5xx = status >= 500 && status < 600;
        boolean invited =
            truthy(response.header("x-should-retry"))
                || parseRetryAfterMillis(
                        firstHeader(response, "Retry-After", SUPPRESSED_RETRY_AFTER))
                    != null;
        boolean busy = (status == 429 || status == 503) && invited;
        boolean retryable = busy || (options.legacyRetryPolicy() && is5xx && status != 503);
        if (options.maxRetries() <= 0 || !retryable || attempt >= options.maxRetries()) {
          throw ShashuException.fromResponse(status, responseBody).attachResponseHeaders(headers);
        }

        Long retryAfterMillis =
            (busy && !options.legacyRetryPolicy())
                ? parseRetryAfterMillis(
                    firstHeader(response, "Retry-After", SUPPRESSED_RETRY_AFTER))
                : null;
        delayMillis =
            withJitter(retryAfterMillis != null ? retryAfterMillis : backoffMillis(attempt));
      } catch (IOException e) {
        // Retry only if the request never left (connect refused / DNS /
        // connect timeout). A drop after the bytes were sent is raised:
        // the gateway may already have run the call.
        if (!requestNeverLeft(e) || attempt >= options.maxRetries()) {
          throw new ShashuException("HTTP call failed", e);
        }
        delayMillis = backoffMillis(attempt);
      }

      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new ShashuException("Interrupted while backing off before retry", ie);
      }
      attempt++;
    }
  }

  static boolean requestNeverLeft(IOException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ConnectException
          || t instanceof UnknownHostException
          || t instanceof NoRouteToHostException) {
        return true;
      }
      if (t instanceof SocketTimeoutException) {
        String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
        return m.contains("connect");
      }
      String m = t.getMessage() == null ? "" : t.getMessage();
      if (m.contains("Failed to connect")) {
        return true;
      }
    }
    return false;
  }

  /** Exponential backoff with jitter: 250ms, 500ms, 1s, capped at 2s. */
  private static long backoffMillis(int attempt) {
    return Math.min(2000L, 250L * (1L << attempt)) + ThreadLocalRandom.current().nextLong(0, 100);
  }

  private static String firstHeader(Response response, String... names) {
    for (String name : names) {
      String value = response.header(name);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static boolean truthy(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String v = value.trim().toLowerCase(java.util.Locale.ROOT);
    return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
  }

  /** Retry-After (or backoff) plus 0–20% jitter, capped at 30s. */
  private static long withJitter(long millis) {
    long capped = Math.min(30_000L, Math.max(0L, millis));
    long extra = (long) (capped * 0.2 * ThreadLocalRandom.current().nextDouble());
    return Math.min(30_000L, capped + extra);
  }

  /**
   * Parse an HTTP {@code Retry-After} header into milliseconds, supporting both the delta-seconds
   * form ({@code "7"}) and the HTTP-date form ({@code "Wed, 21 Oct 2026 07:28:00 GMT"}). The result
   * is clamped to {@code [0, 30_000]}ms so a hostile or stale header can't park the caller for
   * minutes. Returns {@code null} when the header is absent or unparseable, in which case the
   * caller falls back to exponential backoff.
   */
  private static Long parseRetryAfterMillis(String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      return null;
    }
    String value = headerValue.trim();
    try {
      return clampRetryAfter(Long.parseLong(value) * 1000L);
    } catch (NumberFormatException ignore) {
      // Not delta-seconds — try the HTTP-date form below.
    }
    try {
      java.time.ZonedDateTime when =
          java.time.ZonedDateTime.parse(
              value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
      long deltaMillis = java.time.Duration.between(java.time.ZonedDateTime.now(), when).toMillis();
      return clampRetryAfter(deltaMillis);
    } catch (java.time.format.DateTimeParseException ignore) {
      return null;
    }
  }

  private static long clampRetryAfter(long millis) {
    if (millis < 0L) {
      return 0L;
    }
    return Math.min(millis, 30_000L);
  }

  /**
   * POST a JSON body and return a stream of {@link ai.shashu.sdk.chat.ChatCompletionChunk} parsed
   * from the Server-Sent Events response. Caller MUST close the returned stream
   * (try-with-resources) so OkHttp can return the underlying connection to the pool.
   *
   * <p>HTTP-level retry is intentionally NOT applied — once the stream has started, replaying would
   * mean the customer receives parts of two responses concatenated. Callers wanting retry must
   * restart the call themselves.
   */
  public ai.shashu.sdk.internal.ChatCompletionChunkStream postStream(String path, Object body) {
    return postStream(path, body, null);
  }

  public ai.shashu.sdk.internal.ChatCompletionChunkStream postStream(
      String path, Object body, String sessionId) {
    String url = options.baseUrl().replaceAll("/$", "") + path;
    byte[] payload;
    try {
      payload = MAPPER.writeValueAsBytes(body);
    } catch (IOException e) {
      throw new ShashuException("Failed to serialise streaming request body", e);
    }

    Request.Builder req =
        new Request.Builder()
            .url(url)
            .header("User-Agent", options.userAgent())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(RequestBody.create(payload, JSON));
    if (options.organizationId() != null && !options.organizationId().isBlank()) {
      req.header("X-Org-Id", options.organizationId());
    }
    applyContractVersion(req);
    applyWorkflowContext(req, sessionId);

    Response response;
    try {
      response = http.newCall(req.build()).execute();
    } catch (IOException e) {
      throw new ShashuException("Streaming HTTP call failed", e);
    }

    // Seed self-id even on a blocked stream (read before raising).
    captureAgentId(response);

    if (!response.isSuccessful()) {
      int status = response.code();
      Map<String, String> headers = captureShashuHeaders(response);
      String responseBody = "";
      try {
        if (response.body() != null) responseBody = response.body().string();
      } catch (IOException ignore) {
        // Best-effort; surface what we have.
      } finally {
        response.close();
      }
      throw ShashuException.fromResponse(status, responseBody).attachResponseHeaders(headers);
    }

    // Ownership of `response` transfers to the stream — close on its close().
    return new ai.shashu.sdk.internal.ChatCompletionChunkStream(response, MAPPER);
  }

  /**
   * Apply per-request workflow-context headers (Shadow AI source, trace id, runtime metadata,
   * source-tool blob) to a request just before it goes on the wire. Mirrors the .NET SDK's {@code
   * ShashuApi.ApplyWorkflowContext} and the Python SDK's {@code build_shashu_headers()} contract.
   */
  /**
   * Declare the contract version ({@link ShashuClientOptions#contractVersion()}). Absent when the
   * caller opted out, so the account default applies.
   */
  private void applyContractVersion(Request.Builder req) {
    String version = options.contractVersion();
    if (version != null) {
      req.header(ShashuClient.CONTRACT_VERSION_HEADER, version);
    }
  }

  private void applyWorkflowContext(Request.Builder req, String requestSessionId) {
    for (Map.Entry<String, String> e :
        WorkflowContext.build(null, null, resolveSession(requestSessionId)).entrySet()) {
      // OkHttp's Request.Builder.header(...) replaces. We use it
      // intentionally — workflow-context headers are computed fresh
      // each request, never user-overridden today.
      req.header(e.getKey(), e.getValue());
    }
  }

  /**
   * Per-request is an instruction that wins; a captured inbound session overrides the
   * construction-time default; otherwise the minted/passed client session. The rule itself lives in
   * exactly ONE place — {@link WorkflowContext#resolveSession} — matching {@code
   * contracts/sdk-surface.v1.md}.
   */
  private String resolveSession(String requestSessionId) {
    return WorkflowContext.resolveSession(requestSessionId, sessionId);
  }

  private static Map<String, String> captureShashuHeaders(Response response) {
    Map<String, String> out = new LinkedHashMap<>();
    Headers h = response.headers();
    for (int i = 0; i < h.size(); i++) {
      String name = h.name(i);
      if (name.regionMatches(true, 0, "x-shashu-", 0, "x-shashu-".length())
          || name.equalsIgnoreCase("x-should-retry")
          || name.equalsIgnoreCase("x-request-id")) {
        out.put(name, h.value(i));
      }
    }
    return Map.copyOf(out);
  }

  private static void throwIfCompatibleBlock(
      ChatCompletionResponse chat, String raw, Map<String, String> headers) {
    for (ChatCompletionResponse.Choice choice : chat.choices()) {
      if ("content_filter".equals(choice.finishReason())) {
        throw ShashuContentFilterException.fromCompatible(raw, headers);
      }
    }
  }

  static void throwIfStreamBlocked(ChatCompletionChunk chunk) {
    for (ChatCompletionChunk.Choice choice : chunk.choices()) {
      if ("content_filter".equals(choice.finishReason())) {
        throw ShashuContentFilterException.fromStreamChunk();
      }
    }
  }

  public void close() {
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }
}
