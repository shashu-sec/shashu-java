package ai.shashu.sdk.routing;

import ai.shashu.sdk.ShashuClient;
import ai.shashu.sdk.internal.RetrievalJoiner;
import ai.shashu.sdk.internal.ShashuConfig;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OkHttp interceptor that reroutes requests destined for known LLM provider hosts (OpenAI,
 * Anthropic, Google) through the Shashu gateway. The customer's existing vendor-SDK code stays
 * unchanged; only the underlying OkHttp client changes.
 *
 * <p>Nothing is rewired at run time. This SDK never modifies another vendor's library: you build an
 * {@link okhttp3.OkHttpClient} carrying this interceptor and hand that client to the vendor SDK at
 * construction. Every call site downstream of that constructor is unchanged, and the routing is
 * visible in the code that built the client rather than installed behind it.
 *
 * <p>What gets rewritten on intercepted requests:
 *
 * <ul>
 *   <li>Host: rewritten to the configured gateway base URL.
 *   <li>Path: provider-specific paths mapped to the gateway's OpenAI-compatible {@code
 *       /v1/chat/completions} equivalent.
 *   <li>{@code Authorization}: replaced with the Shashu Bearer token (the original vendor key is
 *       dropped — the gateway holds upstream provider keys server-side).
 *   <li>Vendor-specific auth headers ({@code x-api-key}, {@code x-goog-api-key}, {@code
 *       anthropic-version}) are stripped.
 * </ul>
 *
 * <p>Usage — wire the interceptor into any OkHttpClient:
 *
 * <pre>{@code
 * OkHttpClient http = new OkHttpClient.Builder()
 *     .addInterceptor(new ShashuRoutingInterceptor("sk-trustgate-..."))
 *     .build();
 *
 * // Any vendor SDK that lets you inject OkHttpClient now routes through Shashu.
 * OpenAiService openai = new OpenAiService("placeholder", Duration.ofSeconds(60), http);
 * }</pre>
 */
public class ShashuRoutingInterceptor implements Interceptor {

  /**
   * Hosts the interceptor recognises as upstream LLM providers and reroutes to the gateway. Keep in
   * sync with the .NET handler's list.
   */
  private static final Set<String> PROVIDER_HOSTS;

  static {
    Set<String> hosts = new HashSet<>();
    hosts.add("api.openai.com");
    hosts.add("api.anthropic.com");
    hosts.add("generativelanguage.googleapis.com");
    // Future: api.cohere.ai, api.mistral.ai, api.together.xyz —
    // extend when those vendors land in customer requests.
    PROVIDER_HOSTS = Collections.unmodifiableSet(hosts);
  }

  private final ai.shashu.sdk.internal.CredentialResolver credential;
  private final HttpUrl gatewayBaseUrl;
  private final String organizationId; // nullable
  private final String contractVersion; // nullable: caller opted out

  /**
   * @param apiKey the Shashu API key. The gateway address comes from {@code TRUSTGATE_BASE_URL};
   *     there is no default.
   * @throws ai.shashu.sdk.ShashuConfigException if no address resolves
   */
  public ShashuRoutingInterceptor(String apiKey) {
    this(apiKey, null, null);
  }

  /**
   * @param apiKey the Shashu API key
   * @param gatewayBaseUrlOverride the gateway address, which always wins; {@code null} falls back
   *     to {@code TRUSTGATE_BASE_URL}. Shashu is self-hosted, so there is no default and the SDK
   *     will not guess one.
   * @param organizationId optional {@code X-Org-Id} scoping
   * @throws ai.shashu.sdk.ShashuConfigException if no address resolves
   */
  public ShashuRoutingInterceptor(
      String apiKey, String gatewayBaseUrlOverride, String organizationId) {
    this(apiKey, gatewayBaseUrlOverride, organizationId, ShashuClient.DEFAULT_CONTRACT_VERSION);
  }

  /**
   * @param apiKey the Shashu API key
   * @param gatewayBaseUrlOverride the gateway address; {@code null} falls back to {@code
   *     TRUSTGATE_BASE_URL}
   * @param organizationId optional {@code X-Org-Id} scoping
   * @param contractVersion the {@code trustgate-version} to declare on rerouted requests; {@code
   *     null} or blank sends none. A value the caller already set on the request is kept.
   * @throws ai.shashu.sdk.ShashuConfigException if no address resolves
   */
  public ShashuRoutingInterceptor(
      String apiKey, String gatewayBaseUrlOverride, String organizationId, String contractVersion) {
    this.credential =
        new ai.shashu.sdk.internal.CredentialResolver(
            (apiKey == null || apiKey.isBlank()) ? null : apiKey, null, true);

    String resolvedBase =
        ShashuConfig.requireBaseUrl(
            gatewayBaseUrlOverride, "new ShashuRoutingInterceptor(...)", "gatewayBaseUrlOverride");
    HttpUrl parsed = HttpUrl.parse(resolvedBase.endsWith("/") ? resolvedBase : resolvedBase + "/");
    if (parsed == null) {
      throw new IllegalArgumentException("Invalid gateway base URL: " + resolvedBase);
    }
    this.gatewayBaseUrl = parsed;
    this.organizationId = organizationId;
    this.contractVersion =
        (contractVersion == null || contractVersion.isBlank()) ? null : contractVersion.trim();
  }

  /** OkHttp interceptor that points provider SDKs at this gateway. */
  public ShashuRoutingInterceptor(
      ai.shashu.sdk.internal.CredentialResolver credential,
      String gatewayBaseUrlOverride,
      String organizationId,
      String contractVersion) {
    this.credential = java.util.Objects.requireNonNull(credential);
    String resolvedBase =
        ShashuConfig.requireBaseUrl(
            gatewayBaseUrlOverride, "new ShashuRoutingInterceptor(...)", "gatewayBaseUrlOverride");
    HttpUrl parsed = HttpUrl.parse(resolvedBase.endsWith("/") ? resolvedBase : resolvedBase + "/");
    if (parsed == null) {
      throw new IllegalArgumentException("Invalid gateway base URL: " + resolvedBase);
    }
    this.gatewayBaseUrl = parsed;
    this.organizationId = organizationId;
    this.contractVersion =
        (contractVersion == null || contractVersion.isBlank()) ? null : contractVersion.trim();
  }

  /** OkHttp interceptor that points provider SDKs at this gateway. */
  public ShashuRoutingInterceptor(
      java.util.function.Supplier<String> tokenProvider,
      String gatewayBaseUrlOverride,
      String organizationId) {
    this(
        tokenProvider,
        gatewayBaseUrlOverride,
        organizationId,
        ShashuClient.DEFAULT_CONTRACT_VERSION);
  }

  /** OkHttp interceptor that points provider SDKs at this gateway. */
  public ShashuRoutingInterceptor(
      java.util.function.Supplier<String> tokenProvider,
      String gatewayBaseUrlOverride,
      String organizationId,
      String contractVersion) {
    this.credential = new ai.shashu.sdk.internal.CredentialResolver(null, tokenProvider, true);
    String resolvedBase =
        ShashuConfig.requireBaseUrl(
            gatewayBaseUrlOverride, "new ShashuRoutingInterceptor(...)", "gatewayBaseUrlOverride");
    HttpUrl parsed = HttpUrl.parse(resolvedBase.endsWith("/") ? resolvedBase : resolvedBase + "/");
    if (parsed == null) {
      throw new IllegalArgumentException("Invalid gateway base URL: " + resolvedBase);
    }
    this.gatewayBaseUrl = parsed;
    this.organizationId = organizationId;
    this.contractVersion =
        (contractVersion == null || contractVersion.isBlank()) ? null : contractVersion.trim();
  }

  /** True if this URL points at a known upstream LLM provider host. */
  public static boolean isProviderHost(HttpUrl url) {
    return url != null && PROVIDER_HOSTS.contains(url.host().toLowerCase());
  }

  @Override
  /** Rewrite the request URL and Authorization, then proceed the chain. */
  public Response intercept(Chain chain) throws IOException {
    Request original = chain.request();
    HttpUrl url = original.url();

    if (!isProviderHost(url)) {
      // Not a known LLM provider — pass through unchanged.
      return chain.proceed(original);
    }

    Request rewritten = rewrite(original, url);

    // The retrieval joiner runs LAST, on the already-rewritten request,
    // so it only ever sees traffic that is actually going to the gateway.
    // With nothing declared it returns `rewritten` itself, untouched.
    Response response = chain.proceed(RetrievalJoiner.join(rewritten));
    if (!credential.usesProvider()) {
      return response;
    }
    String body = "";
    try {
      okhttp3.ResponseBody peek = response.peekBody(1_000_000);
      if (peek != null) {
        body = peek.string();
      }
    } catch (IOException ignored) {
      return response;
    }
    if (!ai.shashu.sdk.internal.CredentialResolver.shouldRetryPassRefused(
        true,
        response.code(),
        ai.shashu.sdk.internal.CredentialResolver.passRefusalCodeFromBody(body),
        false)) {
      return response;
    }
    response.close();
    credential.invalidate();
    Request retried = rewrite(original, url);
    return chain.proceed(RetrievalJoiner.join(retried));
  }

  private Request rewrite(Request original, HttpUrl originalUrl) {
    // 1. URL: swap host + path mapping. Preserve query string.
    String mappedPath = mapProviderPath(originalUrl.host(), originalUrl.encodedPath());
    HttpUrl.Builder urlBuilder =
        gatewayBaseUrl
            .newBuilder()
            .encodedPath(mappedPath)
            .encodedQuery(originalUrl.encodedQuery());
    HttpUrl newUrl = urlBuilder.build();

    // 2. Headers: drop vendor auth, inject Shashu Bearer.
    Request.Builder reqBuilder =
        original
            .newBuilder()
            .url(newUrl)
            .removeHeader("Authorization")
            .removeHeader("x-api-key")
            .removeHeader("x-goog-api-key")
            .removeHeader("anthropic-version")
            .header("Authorization", "Bearer " + credential.get());

    // 3. User-Agent annotation. Don't replace — append. Vendor SDK's UA
    //    is preserved for upstream telemetry attribution.
    String existingUa = original.header("User-Agent");
    String trustgateUa = "shashu-java/" + ShashuClient.SDK_VERSION;
    if (existingUa == null || existingUa.isBlank()) {
      reqBuilder.header("User-Agent", trustgateUa);
    } else if (!existingUa.contains("shashu-java/")) {
      reqBuilder.header("User-Agent", existingUa + " " + trustgateUa);
    }

    // 4. Optional org scoping.
    if (organizationId != null
        && !organizationId.isBlank()
        && original.header("X-Org-Id") == null) {
      reqBuilder.header("X-Org-Id", organizationId);
    }

    // 5. Contract version, as the Python SDK's patched clients declare
    //    it. A value the caller already set wins.
    if (contractVersion != null && original.header(ShashuClient.CONTRACT_VERSION_HEADER) == null) {
      reqBuilder.header(ShashuClient.CONTRACT_VERSION_HEADER, contractVersion);
    }

    return reqBuilder.build();
  }

  /**
   * Maps a vendor-specific request path to the gateway's OpenAI-compatible equivalent. The gateway
   * handles the provider mux internally based on the {@code model} field in the request body.
   *
   * <p>Known mappings:
   *
   * <ul>
   *   <li>{@code api.openai.com/v1/*} → unchanged
   *   <li>{@code api.anthropic.com/v1/messages} → unchanged (gateway accepts Anthropic shape)
   *   <li>{@code generativelanguage.googleapis.com/v1beta/models/*:generateContent} → {@code
   *       /v1/chat/completions} (gateway has a Google-shape adapter)
   * </ul>
   */
  static String mapProviderPath(String host, String originalPath) {
    String h = host.toLowerCase();
    if (h.equals("api.openai.com") || h.equals("api.anthropic.com")) {
      return originalPath;
    }
    if (h.equals("generativelanguage.googleapis.com")) {
      return "/v1/chat/completions";
    }
    return originalPath;
  }
}
