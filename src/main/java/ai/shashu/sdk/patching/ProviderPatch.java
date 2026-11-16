package ai.shashu.sdk.patching;

import ai.shashu.sdk.routing.GatewayRouting;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

/**
 * @deprecated Renamed to {@link GatewayRouting} in {@code ai.shashu.sdk.routing}. The name was
 *     wrong: this class never patched anything. It builds an {@link OkHttpClient} that routes
 *     provider traffic through your gateway, which you pass to the vendor SDK at construction — no
 *     vendor library is modified at run time. Shashu does not ship run-time patching of another
 *     company's library in any SDK surface, so the name advertised a technique the code does not
 *     use. Every method here forwards to {@code GatewayRouting} and behaviour is unchanged; switch
 *     the import. This class will be removed in a future major release.
 * @see GatewayRouting
 */
@Deprecated
public final class ProviderPatch {

  private ProviderPatch() {}

  /**
   * @param apiKey Shashu API key, or {@code null} to read {@code TRUSTGATE_API_KEY}
   * @return an interceptor to add to an {@code OkHttpClient.Builder}
   * @deprecated Use {@link GatewayRouting#asInterceptor(String)}.
   */
  @Deprecated
  public static Interceptor asInterceptor(String apiKey) {
    return GatewayRouting.asInterceptor(apiKey);
  }

  /**
   * @param apiKey Shashu API key, or {@code null} to read {@code TRUSTGATE_API_KEY}
   * @param gatewayBaseUrl the gateway address; {@code null} falls back to {@code
   *     TRUSTGATE_BASE_URL}
   * @param organizationId optional {@code X-Org-Id} scoping, or {@code null}
   * @return an interceptor to add to an {@code OkHttpClient.Builder}
   * @deprecated Use {@link GatewayRouting#asInterceptor(String, String, String)}.
   */
  @Deprecated
  public static Interceptor asInterceptor(
      String apiKey, String gatewayBaseUrl, String organizationId) {
    return GatewayRouting.asInterceptor(apiKey, gatewayBaseUrl, organizationId);
  }

  /**
   * @param apiKey Shashu API key, or {@code null} to read {@code TRUSTGATE_API_KEY}
   * @return an {@code OkHttpClient} whose provider traffic goes to the gateway
   * @deprecated Use {@link GatewayRouting#newOkHttpClient(String)}.
   */
  @Deprecated
  public static OkHttpClient newOkHttpClient(String apiKey) {
    return GatewayRouting.newOkHttpClient(apiKey);
  }

  /**
   * @param apiKey Shashu API key, or {@code null} to read {@code TRUSTGATE_API_KEY}
   * @param gatewayBaseUrl the gateway address; {@code null} falls back to {@code
   *     TRUSTGATE_BASE_URL}
   * @param organizationId optional {@code X-Org-Id} scoping, or {@code null}
   * @return an {@code OkHttpClient} whose provider traffic goes to the gateway
   * @deprecated Use {@link GatewayRouting#newOkHttpClient(String, String, String)}.
   */
  @Deprecated
  public static OkHttpClient newOkHttpClient(
      String apiKey, String gatewayBaseUrl, String organizationId) {
    return GatewayRouting.newOkHttpClient(apiKey, gatewayBaseUrl, organizationId);
  }
}
