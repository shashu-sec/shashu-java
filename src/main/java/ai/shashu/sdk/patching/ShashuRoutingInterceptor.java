package ai.shashu.sdk.patching;

/**
 * @deprecated Moved to {@link ai.shashu.sdk.routing.ShashuRoutingInterceptor}. Only the package
 *     changed. The old package was named for run-time patching, which this interceptor has never
 *     done and which Shashu does not ship: you build an {@code OkHttpClient} carrying the
 *     interceptor and pass it to the vendor SDK at construction, and no vendor library is modified.
 *     This subclass adds nothing and exists so existing imports keep compiling; switch the import.
 *     It will be removed in a future major release.
 * @see ai.shashu.sdk.routing.ShashuRoutingInterceptor
 */
@Deprecated
public final class ShashuRoutingInterceptor extends ai.shashu.sdk.routing.ShashuRoutingInterceptor {

  /**
   * @param apiKey the Shashu API key
   * @throws ai.shashu.sdk.ShashuConfigException if no address resolves
   * @deprecated Use {@link
   *     ai.shashu.sdk.routing.ShashuRoutingInterceptor#ShashuRoutingInterceptor(String)}.
   */
  @Deprecated
  public ShashuRoutingInterceptor(String apiKey) {
    super(apiKey);
  }

  /**
   * @param apiKey the Shashu API key
   * @param gatewayBaseUrlOverride the gateway address; {@code null} falls back to {@code
   *     TRUSTGATE_BASE_URL}
   * @param organizationId optional {@code X-Org-Id} scoping
   * @throws ai.shashu.sdk.ShashuConfigException if no address resolves
   * @deprecated Use {@link
   *     ai.shashu.sdk.routing.ShashuRoutingInterceptor#ShashuRoutingInterceptor(String, String,
   *     String)}.
   */
  @Deprecated
  public ShashuRoutingInterceptor(
      String apiKey, String gatewayBaseUrlOverride, String organizationId) {
    super(apiKey, gatewayBaseUrlOverride, organizationId);
  }
}
