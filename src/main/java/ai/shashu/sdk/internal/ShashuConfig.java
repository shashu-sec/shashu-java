package ai.shashu.sdk.internal;

import ai.shashu.sdk.ShashuConfigException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Gateway address resolution and the SDK kill switch. Visible across the {@code ai.shashu.sdk}
 * packages but not part of the public API.
 *
 * <p>One place, because four entry points need the same two decisions and a second copy of either
 * is a copy that drifts.
 *
 * <p><b>There is no default gateway address.</b> Resolution is explicit option &rarr; {@code
 * TRUSTGATE_BASE_URL} &rarr; {@link ShashuConfigException}. See that class for why.
 *
 * <p><b>{@code TRUSTGATE_DISABLE=1} turns the SDK off</b> without a code change, so a platform team
 * can stop the instrumentation of a deployed service from its environment. It is checked at every
 * entry point that patches a third-party client, carries a retrieval declaration, or sends
 * telemetry.
 *
 * <p>Sibling of the Python SDK's {@code trustgate/_config.py}, the Node SDK's {@code src/config.ts}
 * and the .NET SDK's {@code Configuration/ShashuConfig.cs}. The thrown message is the frozen
 * template in {@code contracts/sdk-surface.v1.md}.
 */
public final class ShashuConfig {

  private ShashuConfig() {}

  /** Environment variable holding the gateway address. {@code SHASHU_*} wins. */
  public static final String BASE_URL_ENV = "SHASHU_BASE_URL";

  public static final String BASE_URL_ENV_LEGACY = "TRUSTGATE_BASE_URL";
  public static final String API_KEY_ENV = "SHASHU_API_KEY";
  public static final String API_KEY_ENV_LEGACY = "TRUSTGATE_API_KEY";

  /** Set to one of {@link #TRUTHY} to make every entry point a no-op. */
  public static final String DISABLE_ENV = "SHASHU_DISABLE";

  public static final String DISABLE_ENV_LEGACY = "TRUSTGATE_DISABLE";

  // Documented as "=1"; the rest are accepted because platform tooling
  // emits them and a kill switch that silently ignores "true" is worse
  // than a lenient one.
  private static final Set<String> TRUTHY = new HashSet<>(Arrays.asList("1", "true", "yes", "on"));

  /**
   * Environment reader, swappable so tests can drive resolution without mutating the JVM's real
   * environment (which Java cannot do portably, and which would leak across tests if it could).
   * Package-private, and reached only from {@code RetrievalTestAccess} in the test tree: a mutable
   * process-wide switch has no business on a shipped surface.
   */
  interface Env {
    String get(String name);
  }

  private static volatile Env env = System::getenv;
  private static final Set<String> FALLBACK_WARNED = new HashSet<>();

  /** Replace the environment reader. Test seam. */
  static void setEnv(Env replacement) {
    env = replacement != null ? replacement : (Env) System::getenv;
  }

  private static String read(String name) {
    Env current = env;
    return current != null ? current.get(name) : null;
  }

  public static String envGet(String current, String legacy) {
    String value = blankToNull(read(current));
    if (value != null) {
      return value;
    }
    String legacyValue = blankToNull(read(legacy));
    if (legacyValue != null) {
      warnLegacyOnce(legacy, current);
      return legacyValue;
    }
    return null;
  }

  public static String envApiKey() {
    return envGet(API_KEY_ENV, API_KEY_ENV_LEGACY);
  }

  private static String blankToNull(String raw) {
    return (raw == null || raw.isBlank()) ? null : raw.trim();
  }

  private static void warnLegacyOnce(String legacy, String current) {
    synchronized (FALLBACK_WARNED) {
      if (!FALLBACK_WARNED.add(legacy)) {
        return;
      }
    }
    System.err.println(
        legacy
            + " is deprecated; set "
            + current
            + " instead. The TRUSTGATE_* name still works for this release.");
  }

  /** True when the operator has switched the SDK off via the environment. */
  public static boolean isDisabled() {
    String raw = envGet(DISABLE_ENV, DISABLE_ENV_LEGACY);
    return raw != null && TRUTHY.contains(raw.trim().toLowerCase(Locale.ROOT));
  }

  /**
   * Resolve an address from the explicit value then the environment, or {@code null} when the
   * caller may proceed without one.
   */
  public static String optionalBaseUrl(String explicit) {
    if (explicit != null && !explicit.isBlank()) {
      return explicit.trim();
    }
    return envGet(BASE_URL_ENV, BASE_URL_ENV_LEGACY);
  }

  /**
   * Resolve the gateway address, or throw naming how to supply one.
   *
   * @param explicit the explicit option, which always wins
   * @param entryPoint what the customer called, named as they would write it &mdash; {@code
   *     ShashuClient.builder().build()}
   * @param argument the argument that would have supplied the address, rendered the way Java passes
   *     it &mdash; {@code .baseUrl(...)}
   * @throws ShashuConfigException when nothing resolves. The message names {@code
   *     TRUSTGATE_BASE_URL} and the argument, and never a hostname &mdash; there is no address to
   *     suggest, so suggesting one is a bug.
   */
  public static String requireBaseUrl(String explicit, String entryPoint, String argument) {
    String resolved = optionalBaseUrl(explicit);
    if (resolved != null) {
      return resolved;
    }
    throw new ShashuConfigException(
        "SHASHU gateway address is not configured, so "
            + entryPoint
            + " has nothing to route to. Pass "
            + argument
            + " to "
            + entryPoint
            + ", or set the "
            + BASE_URL_ENV
            + " or "
            + BASE_URL_ENV_LEGACY
            + " environment variable. SHASHU is"
            + " self-hosted: the SDK has no default address and will not guess one.");
  }
}
