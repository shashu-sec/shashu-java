package ai.shashu.sdk;

import ai.shashu.sdk.internal.CredentialResolver;

/**
 * Resolved client configuration. Constructed by {@link ShashuClient.Builder}; typically not used
 * directly by callers.
 */
public final class ShashuClientOptions {
  private final CredentialResolver credential;
  private final String apiKey;
  private final String baseUrl;
  private final long timeoutMillis;
  private final int maxRetries;
  private final String organizationId;
  private final String userAgent;
  private final boolean legacyRetryPolicy;
  private final String agentId;
  private final String sessionId;
  private final String contractVersion;

  /** Declares {@link ShashuClient#DEFAULT_CONTRACT_VERSION}. */
  public ShashuClientOptions(
      String apiKey,
      String baseUrl,
      long timeoutMillis,
      int maxRetries,
      String organizationId,
      String userAgent,
      boolean legacyRetryPolicy,
      String agentId,
      String sessionId) {
    this(
        apiKey,
        baseUrl,
        timeoutMillis,
        maxRetries,
        organizationId,
        userAgent,
        legacyRetryPolicy,
        agentId,
        sessionId,
        ShashuClient.DEFAULT_CONTRACT_VERSION);
  }

  /**
   * @param contractVersion the {@code shashu-version} to declare; {@code null} or blank sends no
   *     such header
   */
  public ShashuClientOptions(
      String apiKey,
      String baseUrl,
      long timeoutMillis,
      int maxRetries,
      String organizationId,
      String userAgent,
      boolean legacyRetryPolicy,
      String agentId,
      String sessionId,
      String contractVersion) {
    this.credential = new CredentialResolver(apiKey, null, false);
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.timeoutMillis = timeoutMillis;
    this.maxRetries = maxRetries;
    this.organizationId = organizationId;
    this.userAgent = userAgent;
    this.legacyRetryPolicy = legacyRetryPolicy;
    this.agentId = agentId;
    this.sessionId = sessionId;
    this.contractVersion =
        (contractVersion == null || contractVersion.isBlank()) ? null : contractVersion.trim();
  }

  /** Resolved options after builder validation; not constructed by callers. */
  public ShashuClientOptions(
      CredentialResolver credential,
      String baseUrl,
      long timeoutMillis,
      int maxRetries,
      String organizationId,
      String userAgent,
      boolean legacyRetryPolicy,
      String agentId,
      String sessionId,
      String contractVersion) {
    this.credential = java.util.Objects.requireNonNull(credential);
    this.apiKey = credential.staticCredential();
    this.baseUrl = baseUrl;
    this.timeoutMillis = timeoutMillis;
    this.maxRetries = maxRetries;
    this.organizationId = organizationId;
    this.userAgent = userAgent;
    this.legacyRetryPolicy = legacyRetryPolicy;
    this.agentId = agentId;
    this.sessionId = sessionId;
    this.contractVersion =
        (contractVersion == null || contractVersion.isBlank()) ? null : contractVersion.trim();
  }

  /** Resolved SHASHU key or token-provider used on every request. */
  public CredentialResolver credential() {
    return credential;
  }

  /** SHASHU key ({@code tg_sk_…}) when not using a token provider. */
  public String apiKey() {
    return apiKey;
  }

  /** Gateway origin this client sends requests to; there is no default. */
  public String baseUrl() {
    return baseUrl;
  }

  /** Per-request HTTP timeout in milliseconds. */
  public long timeoutMillis() {
    return timeoutMillis;
  }

  /** Extra attempts after the first on retryable failures. */
  public int maxRetries() {
    return maxRetries;
  }

  /** Optional {@code x-shashu-organization-id} header value. */
  public String organizationId() {
    return organizationId;
  }

  /** Value sent as the HTTP User-Agent. */
  public String userAgent() {
    return userAgent;
  }

  /**
   * This agent's OWN id (ADR-007 §4.1), used as the {@code caller-agent-id} when this agent INVOKES
   * a downstream agent via {@link ShashuClient#buildInvocationHeaders()}. Optional: if unset, the
   * client caches it from the {@code x-shashu-agent-id} response header on the first gateway call.
   * Advisory lineage, never an auth credential. May be null.
   */
  public String agentId() {
    return agentId;
  }

  /**
   * Default session this client joins. Omitted, a session is minted once at construction so every
   * request the client makes joins it. A captured inbound A2A session overrides this default; a
   * per-request {@code sessionId} is an instruction that wins. See {@code
   * contracts/sdk-surface.v1.md} "Which session a request joins".
   */
  public String sessionId() {
    return sessionId;
  }

  /**
   * The {@code shashu-version} header value sent on every request, or {@code null} when the caller
   * opted out. See {@link ShashuClient#DEFAULT_CONTRACT_VERSION}.
   */
  public String contractVersion() {
    return contractVersion;
  }

  /**
   * When {@code true}, restore the pre-fix retry behavior: also retry 5xx responses and ignore the
   * {@code Retry-After} header on 429 (use exponential backoff instead). Defaults to {@code false}
   * — the conservative policy that only retries connection-level failures and 429 (honoring {@code
   * Retry-After}). Provided as a temporary escape hatch for callers who relied on the SDK
   * swallowing transient 5xx; prefer leaving it off, because the gateway already retries upstream
   * providers with backoff + circuit breakers, and SDK-side 5xx retries stack on top.
   */
  public boolean legacyRetryPolicy() {
    return legacyRetryPolicy;
  }
}
