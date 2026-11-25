package ai.shashu.sdk.routing;

import ai.shashu.sdk.ShashuClient;
import ai.shashu.sdk.internal.CredentialResolver;
import java.util.function.Supplier;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

/**
 * Static factory for an OkHttp client — or a bare interceptor — that routes provider traffic
 * through your Shashu gateway.
 *
 * <p><strong>Nothing is rewired at run time.</strong> This SDK does not modify, replace or re-enter
 * another vendor's library. It builds an {@link OkHttpClient} whose transport points at your
 * gateway, and you pass that client to the vendor SDK at construction. Every call site downstream
 * of that constructor is unchanged, and the routing is visible in the code that built the client.
 *
 * <p><strong>1. Pre-built OkHttpClient (pass to any vendor SDK)</strong>
 *
 * <pre>{@code
 * import ai.shashu.sdk.routing.GatewayRouting;
 *
 * OkHttpClient http = GatewayRouting.newOkHttpClient("sk-trustgate-...");
 *
 * // Pass to whatever vendor SDK lets you supply OkHttpClient:
 * OpenAiService openai = new OpenAiService("placeholder", Duration.ofSeconds(60), http);
 * AnthropicClient anthropic = AnthropicClient.builder().httpClient(http).build();
 * }</pre>
 *
 * <p><strong>2. Just the interceptor (compose with your own OkHttp config)</strong>
 *
 * <pre>{@code
 * OkHttpClient http = new OkHttpClient.Builder()
 *     .addInterceptor(GatewayRouting.asInterceptor("sk-trustgate-..."))
 *     .addInterceptor(yourLoggingInterceptor)
 *     .connectTimeout(Duration.ofSeconds(30))
 *     .build();
 * }</pre>
 *
 * <p>API keys can be passed explicitly or read from the {@code SHASHU_API_KEY} environment variable
 * as a fallback (matches the rest of the SDK).
 *
 * <p>Class name kept symmetric with the .NET SDK's {@code Shashu.Routing.GatewayRouting}.
 *
 * @since 0.2.0
 */
public final class GatewayRouting {

  private GatewayRouting() {}

  /**
   * Build an {@link Interceptor} that routes provider traffic (api.openai.com / api.anthropic.com /
   * generativelanguage.googleapis.com) through the Shashu gateway. Pass to any
   * OkHttpClient.Builder.
   *
   * @param apiKey Shashu API key. If {@code null} or blank, falls back to the {@code
   *     SHASHU_API_KEY} environment variable.
   * @return an interceptor to add to an {@code OkHttpClient.Builder}
   */
  public static Interceptor asInterceptor(String apiKey) {
    return asInterceptor(apiKey, null, null);
  }

  /**
   * Build an interceptor with an explicit gateway URL + optional {@code X-Org-Id} header for
   * admin-tier callers.
   *
   * @param apiKey Shashu API key, or {@code null} to read {@code SHASHU_API_KEY}
   * @param gatewayBaseUrl the gateway address; {@code null} falls back to {@code SHASHU_BASE_URL}
   * @param organizationId optional {@code X-Org-Id} scoping, or {@code null}
   * @return an interceptor to add to an {@code OkHttpClient.Builder}
   */
  public static Interceptor asInterceptor(
      String apiKey, String gatewayBaseUrl, String organizationId) {
    return asInterceptor(
        apiKey, gatewayBaseUrl, organizationId, ShashuClient.DEFAULT_CONTRACT_VERSION);
  }

  /**
   * Build an interceptor that declares a chosen contract version.
   *
   * @param apiKey Shashu API key, or {@code null} to read {@code SHASHU_API_KEY}
   * @param gatewayBaseUrl the gateway address; {@code null} falls back to {@code SHASHU_BASE_URL}
   * @param organizationId optional {@code X-Org-Id} scoping, or {@code null}
   * @param contractVersion the {@code shashu-version} header; default {@link
   *     ShashuClient#DEFAULT_CONTRACT_VERSION}, {@code null} to send none
   * @return an interceptor to add to an {@code OkHttpClient.Builder}
   */
  public static Interceptor asInterceptor(
      String apiKey, String gatewayBaseUrl, String organizationId, String contractVersion) {
    String keyArg = (apiKey != null && !apiKey.isBlank()) ? apiKey : null;
    CredentialResolver resolver = new CredentialResolver(keyArg, null, true);
    return new ShashuRoutingInterceptor(resolver, gatewayBaseUrl, organizationId, contractVersion);
  }

  /** Route provider traffic using a cloud pass. Exactly one of an API key or a token provider. */
  public static Interceptor asInterceptor(Supplier<String> tokenProvider) {
    return asInterceptor(tokenProvider, null, null, ShashuClient.DEFAULT_CONTRACT_VERSION);
  }

  /** OkHttp interceptor that rewrites provider hosts onto this gateway. */
  public static Interceptor asInterceptor(
      Supplier<String> tokenProvider,
      String gatewayBaseUrl,
      String organizationId,
      String contractVersion) {
    CredentialResolver resolver = new CredentialResolver(null, tokenProvider, true);
    return new ShashuRoutingInterceptor(resolver, gatewayBaseUrl, organizationId, contractVersion);
  }

  /**
   * Build an OkHttpClient pre-wired with the routing interceptor. Convenient one-liner for vendor
   * SDKs that accept a pre-built client.
   *
   * @param apiKey Shashu API key, or {@code null} to read {@code SHASHU_API_KEY}
   * @return an {@code OkHttpClient} whose provider traffic goes to the gateway
   */
  public static OkHttpClient newOkHttpClient(String apiKey) {
    return newOkHttpClient(apiKey, null, null);
  }

  /**
   * Build an OkHttpClient with explicit gateway URL + org-id override.
   *
   * @param apiKey Shashu API key, or {@code null} to read {@code SHASHU_API_KEY}
   * @param gatewayBaseUrl the gateway address; {@code null} falls back to {@code SHASHU_BASE_URL}
   * @param organizationId optional {@code X-Org-Id} scoping, or {@code null}
   * @return an {@code OkHttpClient} whose provider traffic goes to the gateway
   */
  public static OkHttpClient newOkHttpClient(
      String apiKey, String gatewayBaseUrl, String organizationId) {
    return newOkHttpClient(
        apiKey, gatewayBaseUrl, organizationId, ShashuClient.DEFAULT_CONTRACT_VERSION);
  }

  /**
   * Build an OkHttpClient that declares a chosen contract version; see {@link
   * #asInterceptor(String, String, String, String)}.
   *
   * @param apiKey Shashu API key, or {@code null} to read {@code SHASHU_API_KEY}
   * @param gatewayBaseUrl the gateway address; {@code null} falls back to {@code SHASHU_BASE_URL}
   * @param organizationId optional {@code X-Org-Id} scoping, or {@code null}
   * @param contractVersion the {@code shashu-version} header, or {@code null} to send none
   * @return an {@code OkHttpClient} whose provider traffic goes to the gateway
   */
  public static OkHttpClient newOkHttpClient(
      String apiKey, String gatewayBaseUrl, String organizationId, String contractVersion) {
    return new OkHttpClient.Builder()
        .addInterceptor(asInterceptor(apiKey, gatewayBaseUrl, organizationId, contractVersion))
        .build();
  }
}
