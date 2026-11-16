package ai.shashu.sdk;

import java.util.List;
import java.util.Set;

/**
 * The gateway's six cloud-pass refusal codes ({@code error.code} on HTTP 401). Only {@link
 * #PASS_EXPIRED} is retried, once, after fetching a new pass.
 */
public final class PassCodes {

  private PassCodes() {}

  /** HTTP 401 code when no Agents identity provider is configured. */
  public static final String PASS_PROVIDER_NOT_SET_UP = "pass_provider_not_set_up";

  /** HTTP 401 code when pass sign-in is used without the Enterprise plan. */
  public static final String PASS_NOT_LICENSED = "pass_not_licensed";

  /** HTTP 401 code when the presented pass is past its expiry; the SDK retries once. */
  public static final String PASS_EXPIRED = "pass_expired";

  /** HTTP 401 code when the pass audience is not this install. */
  public static final String PASS_WRONG_AUDIENCE = "pass_wrong_audience";

  /** HTTP 401 code when the pass issuer is not the configured provider. */
  public static final String PASS_WRONG_ISSUER = "pass_wrong_issuer";

  /** HTTP 401 code when no agent is registered for this identity. */
  public static final String PASS_UNKNOWN_AGENT = "pass_unknown_agent";

  /** Insertion order matches the Python SDK / the gateway's surface page. */
  public static final List<String> ALL =
      List.of(
          PASS_PROVIDER_NOT_SET_UP,
          PASS_NOT_LICENSED,
          PASS_EXPIRED,
          PASS_WRONG_AUDIENCE,
          PASS_WRONG_ISSUER,
          PASS_UNKNOWN_AGENT);

  /** Same codes as {@link #ALL}, as a set. */
  public static final Set<String> SET = Set.copyOf(ALL);
}
