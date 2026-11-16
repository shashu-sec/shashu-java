package ai.shashu.sdk.internal;

import ai.shashu.sdk.PassCodes;
import ai.shashu.sdk.ShashuConfigException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * SHASHU key vs cloud-pass credentials.
 *
 * <p>The SDK never talks to Entra, Google or AWS. A token provider returns the current pass; this
 * class caches it, refreshes it 60 seconds before {@code exp}, and retries a request ONCE — after
 * fetching a new pass — when the gateway refuses the pass as expired.
 */
public final class CredentialResolver {

  public static final String BOTH_OR_NEITHER =
      "Give either a SHASHU key or a token provider, not both.";
  public static final int REFRESH_SKEW_SECONDS = 60;

  public static final String PASS_EXPIRED = PassCodes.PASS_EXPIRED;
  public static final Set<String> PASS_CODES = PassCodes.SET;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static volatile LongSupplier nowSeconds = () -> System.currentTimeMillis() / 1000L;

  /** Test seam. Package-private; reached from {@code RetrievalTestAccess}. */
  static void setNowSeconds(LongSupplier clock) {
    nowSeconds = clock != null ? clock : () -> System.currentTimeMillis() / 1000L;
  }

  private final Supplier<String> provider;
  private final String staticKey;
  private final Object lock = new Object();
  private String cached;

  public CredentialResolver(String apiKey, Supplier<String> tokenProvider, boolean requireOne) {
    String envKey = ShashuConfig.envApiKey();
    boolean hasKeyArg = apiKey != null;
    boolean hasProvider = tokenProvider != null;
    if (hasKeyArg && hasProvider) {
      throw new ShashuConfigException(BOTH_OR_NEITHER);
    }
    if (hasProvider && envKey != null && !hasKeyArg) {
      throw new ShashuConfigException(BOTH_OR_NEITHER);
    }
    if (requireOne && !hasProvider && !hasKeyArg && envKey == null) {
      throw new ShashuConfigException(BOTH_OR_NEITHER);
    }
    this.provider = tokenProvider;
    this.staticKey = hasProvider ? null : (hasKeyArg ? apiKey : envKey);
  }

  public boolean usesProvider() {
    return provider != null;
  }

  /** Key mode only; never calls the token provider. */
  public String staticCredential() {
    return provider == null ? staticKey : null;
  }

  public String get() {
    if (provider == null) {
      return staticKey;
    }
    synchronized (lock) {
      if (cached != null && !needsRefresh(cached)) {
        return cached;
      }
      cached = String.valueOf(provider.get());
      return cached;
    }
  }

  public void invalidate() {
    synchronized (lock) {
      cached = null;
    }
  }

  public static Long jwtExpUnverified(String token) {
    if (token == null) {
      return null;
    }
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      return null;
    }
    try {
      String payload = parts[1];
      int pad = (4 - (payload.length() % 4)) % 4;
      byte[] json = Base64.getUrlDecoder().decode(payload + "=".repeat(pad));
      JsonNode data = MAPPER.readTree(new String(json, StandardCharsets.UTF_8));
      if (data == null || !data.has("exp")) {
        return null;
      }
      JsonNode exp = data.get("exp");
      if (exp == null || !exp.isNumber()) {
        return null;
      }
      return exp.asLong();
    } catch (Exception e) {
      return null;
    }
  }

  public static String passRefusalCodeFromBody(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      JsonNode err = MAPPER.readTree(body).path("error");
      if (!err.isObject() || !err.path("code").isTextual()) {
        return null;
      }
      String code = err.path("code").asText();
      return PASS_CODES.contains(code) ? code : null;
    } catch (Exception e) {
      return null;
    }
  }

  public static String errorCode(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      JsonNode err = MAPPER.readTree(body).path("error");
      if (err.isObject() && err.path("code").isTextual()) {
        String code = err.path("code").asText();
        return (code == null || code.isBlank()) ? null : code;
      }
    } catch (Exception ignored) {
      // fall through
    }
    return null;
  }

  public static String errorMessage(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      JsonNode err = MAPPER.readTree(body).path("error");
      if (err.isObject() && err.path("message").isTextual()) {
        String m = err.path("message").asText();
        return (m == null || m.isBlank()) ? null : m;
      }
    } catch (Exception ignored) {
      // fall through
    }
    return null;
  }

  public static boolean shouldRetryPassRefused(
      boolean usesProvider, int status, String code, boolean bytesDelivered) {
    if (!usesProvider || bytesDelivered || status != 401) {
      return false;
    }
    return PASS_EXPIRED.equals(code);
  }

  private boolean needsRefresh(String token) {
    Long exp = jwtExpUnverified(token);
    if (exp == null) {
      return true;
    }
    return nowSeconds.getAsLong() >= (exp - REFRESH_SKEW_SECONDS);
  }

  /** Sets Bearer per request; retries once on {@code pass_expired} in pass mode. */
  public static final class CredentialInterceptor implements Interceptor {
    private final CredentialResolver resolver;

    public CredentialInterceptor(CredentialResolver resolver) {
      this.resolver = resolver;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
      Request request = apply(chain.request(), resolver.get());
      Response response = chain.proceed(request);
      if (!resolver.usesProvider()) {
        return response;
      }
      if (!shouldRetry(response, false)) {
        return response;
      }
      response.close();
      resolver.invalidate();
      return chain.proceed(apply(request, resolver.get()));
    }

    private static Request apply(Request original, String token) {
      if (token == null || token.isBlank()) {
        return original;
      }
      return original.newBuilder().header("Authorization", "Bearer " + token).build();
    }

    private boolean shouldRetry(Response response, boolean bytesDelivered) {
      if (!resolver.usesProvider() || bytesDelivered || response.code() != 401) {
        return false;
      }
      String body = "";
      try {
        ResponseBody peek = response.peekBody(1_000_000);
        if (peek != null) {
          body = peek.string();
        }
      } catch (IOException ignored) {
        return false;
      }
      return shouldRetryPassRefused(true, 401, passRefusalCodeFromBody(body), false);
    }
  }
}
