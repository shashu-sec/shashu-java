package ai.shashu.sdk;

import ai.shashu.sdk.audit.AuditResource;
import ai.shashu.sdk.chat.Chat;
import ai.shashu.sdk.context.WorkflowContext;
import ai.shashu.sdk.internal.CredentialResolver;
import ai.shashu.sdk.internal.ShashuApi;
import ai.shashu.sdk.internal.ShashuConfig;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Top-level client for Shashu AI. Mirrors the resource-namespaced surface of the OpenAI Java client
 * + the Shashu Python/Node SDKs so a port from one to another is mechanical.
 *
 * <pre>{@code
 * ShashuClient client = ShashuClient.builder()
 *     .apiKey(System.getenv("SHASHU_API_KEY"))
 *     .build();
 *
 * ChatCompletionResponse resp = client.chat().completions().create(
 *     ChatCompletionRequest.builder()
 *         .model("gpt-4o")
 *         .addMessage(ChatMessage.user("Hello"))
 *         .build());
 *
 * System.out.println(resp.choices().get(0).message().content());
 * }</pre>
 *
 * <p>Long-lived clients are safe to share across threads (OkHttp's client is thread-safe). Call
 * {@link #close()} on shutdown to release the underlying connection pool.
 */
public final class ShashuClient implements AutoCloseable {

  /** Stamped into the User-Agent. Bump in sync with pom.xml &lt;version&gt;. */
  public static final String SDK_VERSION = "1.1.1";

  /**
   * The contract version this SDK declares on every request, as the {@code shashu-version} header.
   * The same value the Python SDK sends ({@code trustgate.context.DEFAULT_CONTRACT_VERSION}).
   *
   * <p>Without it the gateway serves its legacy contract: every refusal arrives as an HTTP 200 with
   * the sentence in the message content, and the body carries no {@code trustgate} envelope, so the
   * memory and inspection details are reduced to header counts. With it a refusal is an error
   * status the SDK raises on, and the full envelope is in the body.
   *
   * <p>A documented default, not a hardcode: {@link Builder#contractVersion(String)} pins another
   * date, and {@code contractVersion(null)} sends no header so the account's own default (or
   * legacy) applies.
   */
  public static final String DEFAULT_CONTRACT_VERSION = "2026-09-01";

  /** The request header {@link #DEFAULT_CONTRACT_VERSION} is sent under. */
  public static final String CONTRACT_VERSION_HEADER = "shashu-version";

  private final ShashuApi api;
  private final Chat chat;
  private final AuditResource audit;

  private ShashuClient(ShashuClientOptions opts) {
    this.api = new ShashuApi(opts);
    this.chat = new Chat(api);
    this.audit = new AuditResource(api);
  }

  /** Chat-completions resource namespace. */
  public Chat chat() {
    return chat;
  }

  /**
   * Audit-event ingestion ({@code POST /v1/audit/events}). For connector-tier callers pushing
   * vendor LLM events through the gateway's scanner pipeline.
   */
  public AuditResource audit() {
    return audit;
  }

  /**
   * This agent's own id, as known so far (ADR-007 §4.1) — seeded from {@link
   * ShashuClientOptions#agentId()} and refreshed from each gateway response's {@code
   * x-shashu-agent-id} header. Null until known.
   */
  public String agentId() {
    return api.agentId();
  }

  /**
   * Construction-time session this client joins. When {@link ShashuClientOptions#sessionId()} is
   * omitted, one is minted; a captured inbound A2A session overrides this default; a per-request
   * {@code sessionId} is an instruction that wins. See {@code contracts/sdk-surface.v1.md} "Which
   * session a request joins".
   */
  public String sessionId() {
    return api.sessionId();
  }

  /**
   * Build the lineage headers to stamp on a message THIS agent sends to a DOWNSTREAM agent (the
   * invoking side, ADR-007 §4.1). {@code caller-agent-id} = this client's own id (the {@code
   * agentId} option, refreshed from gateway responses); trace / hop / session default from the
   * tracer so the served flow is propagated. The downstream agent calls {@link
   * ai.shashu.sdk.tracing.Tracer#captureIncoming} on these headers.
   *
   * @throws IllegalStateException if the self-id is not known yet — set the {@code agentId} option
   *     or make one gateway call first so the {@code x-shashu-agent-id} response header can seed
   *     it.
   */
  public Map<String, String> buildInvocationHeaders() {
    return buildInvocationHeaders(null, null, null);
  }

  /**
   * Build the lineage headers for an inter-agent invocation with explicit overrides (see {@link
   * #buildInvocationHeaders()}). A null argument falls back to the tracer's value.
   */
  public Map<String, String> buildInvocationHeaders(
      String traceId, String sessionId, Integer hopDepth) {
    String self = api.agentId();
    if (self == null || self.isBlank()) {
      throw new IllegalStateException(
          "Self agent id is unknown — set ShashuClientOptions agentId or make one "
              + "gateway call first so the x-shashu-agent-id response header can seed it.");
    }
    return WorkflowContext.buildInvocationHeaders(self, traceId, sessionId, hopDepth);
  }

  /** Release the underlying HTTP connection pool. */
  @Override
  public void close() {
    api.close();
  }

  /**
   * Start building a client. The most concise path is via {@code .builder().apiKey(...).build()}.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder. Mirrors the Python SDK's keyword-argument constructor. */
  public static final class Builder {
    private String apiKey;
    private Supplier<String> tokenProvider;
    private String baseUrl;
    private Long timeoutMillis;
    private Integer maxRetries;
    private String organizationId;
    private String userAgent;
    private Boolean legacyRetryPolicy;
    private String agentId;
    private String sessionId;
    private String contractVersion = DEFAULT_CONTRACT_VERSION;

    private Builder() {}

    /** Required. Falls back to the SHASHU_API_KEY env var if unset. */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Cloud pass: a function that returns the current pass. SHASHU never calls Entra, Google or
     * AWS. Exactly one of {@link #apiKey(String)} or this.
     */
    public Builder tokenProvider(Supplier<String> tokenProvider) {
      this.tokenProvider = tokenProvider;
      return this;
    }

    /**
     * The gateway address. Falls back to the {@code SHASHU_BASE_URL} environment variable.
     *
     * <p>There is <b>no default</b>. Shashu is self-hosted, so the SDK has no address to guess and
     * will not guess one: with neither this nor the environment variable set, {@link #build()}
     * throws {@link ShashuConfigException}.
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /** Per-request timeout in ms. Default 60_000. */
    public Builder timeoutMillis(long timeoutMillis) {
      this.timeoutMillis = timeoutMillis;
      return this;
    }

    /**
     * Max retries on transient failures. Default 2; set to 0 to disable. By default only
     * connection-level failures and HTTP 429 (honoring {@code Retry-After}) are retried — 5xx is
     * NOT retried because the gateway already retries upstream providers. See {@link
     * #legacyRetryPolicy(boolean)} to opt back in to 5xx retries.
     */
    public Builder maxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
      return this;
    }

    /**
     * Opt back in to the pre-fix retry behavior: also retry 5xx responses and ignore the {@code
     * Retry-After} header on 429. Default {@code false} (the conservative policy). A temporary
     * escape hatch for callers who relied on the SDK swallowing transient 5xx.
     */
    public Builder legacyRetryPolicy(boolean legacyRetryPolicy) {
      this.legacyRetryPolicy = legacyRetryPolicy;
      return this;
    }

    /** Optional X-Org-Id header. */
    public Builder organizationId(String organizationId) {
      this.organizationId = organizationId;
      return this;
    }

    /** Override the default User-Agent. Used for proxy/CDN debugging. */
    public Builder userAgent(String userAgent) {
      this.userAgent = userAgent;
      return this;
    }

    /**
     * This agent's own id (ADR-007 §4.1), used as the {@code caller-agent-id} when this agent
     * INVOKES a downstream agent via {@link ShashuClient#buildInvocationHeaders()}. Optional — if
     * unset, the client caches it from the {@code x-shashu-agent-id} response header on the first
     * gateway call. Advisory lineage, never an auth credential.
     */
    public Builder agentId(String agentId) {
      this.agentId = agentId;
      return this;
    }

    /**
     * Default session this client joins. Omitted, a session is minted once at construction. A
     * captured inbound A2A session overrides this default; a per-request {@code sessionId} is an
     * instruction that wins.
     */
    public Builder sessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    /**
     * The contract version declared on every request, as the {@code shashu-version} header. Default
     * {@link ShashuClient#DEFAULT_CONTRACT_VERSION}. Pass another date to pin it, or {@code null}
     * (or blank) to send no such header and take whatever the account's default already does, which
     * is the gateway's legacy contract unless an operator has set one: refusals as HTTP 200 replies
     * and no body envelope.
     */
    public Builder contractVersion(String contractVersion) {
      this.contractVersion = contractVersion;
      return this;
    }

    /** Construct the immutable value from this builder. */
    public ShashuClient build() {
      CredentialResolver credential =
          new CredentialResolver(
              (apiKey != null && !apiKey.isBlank()) ? apiKey : null, tokenProvider, true);

      String resolvedBase =
          ShashuConfig.requireBaseUrl(baseUrl, "ShashuClient.builder().build()", ".baseUrl(...)");

      ShashuClientOptions opts =
          new ShashuClientOptions(
              credential,
              resolvedBase,
              timeoutMillis != null ? timeoutMillis : 60_000L,
              maxRetries != null ? maxRetries : 2,
              organizationId,
              userAgent != null ? userAgent : "shashu-java/" + SDK_VERSION,
              legacyRetryPolicy != null && legacyRetryPolicy,
              agentId,
              sessionId,
              contractVersion);

      Objects.requireNonNull(opts);
      return new ShashuClient(opts);
    }
  }
}
