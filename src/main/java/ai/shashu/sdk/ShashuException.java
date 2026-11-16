package ai.shashu.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Thrown when the gateway returns a non-success status code or when the SDK fails to parse a
 * successful response. Wraps the raw response body so callers can inspect the Shashu-shape error
 * JSON.
 *
 * <p>Two responses raise a more-specific subclass — callers can catch the parent for generic
 * handling or the subclass for specific UX:
 *
 * <ul>
 *   <li><b>403</b> → {@link ShashuPolicyViolation}, an <em>access</em> decision (model allow-list,
 *       budget, tool, loop guardrail).
 *   <li><b>400 with {@code error.type "content_filter"}</b> or <b>200 with {@code finish_reason
 *       "content_filter"}</b> → {@link ShashuContentFilterException}, a <em>content</em> block. It
 *       is NOT a malformed request, and it must not be reported as one.
 * </ul>
 */
public class ShashuException extends RuntimeException {

  /** Parses `error.type` and `error.message`; see {@link #isContentFilter(String)}. */
  private static final ObjectMapper CONTENT_FILTER_MAPPER = new ObjectMapper();

  /** HTTP status code returned by the gateway, or 0 if the failure was client-side. */
  private final int statusCode;

  /** Raw response body as returned by the gateway, or empty if no response. */
  private final String responseBody;

  /** {@code x-trustgate-*} headers captured from the HTTP response. Empty when none. */
  private Map<String, String> responseHeaders = Map.of();

  /** Transport or HTTP error from the gateway. */
  public ShashuException(String message) {
    super(message);
    this.statusCode = 0;
    this.responseBody = "";
  }

  /** Transport or HTTP error from the gateway. */
  public ShashuException(int statusCode, String responseBody, String message) {
    super(message);
    this.statusCode = statusCode;
    this.responseBody = responseBody;
  }

  /** Transport or HTTP error from the gateway. */
  public ShashuException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
    this.responseBody = "";
  }

  /** HTTP status from the gateway, or 0 if the call never got a response. */
  public int statusCode() {
    return statusCode;
  }

  /** Raw response body, if any was received. */
  public String responseBody() {
    return responseBody;
  }

  /** Response headers captured for decision and header inspection. */
  public Map<String, String> responseHeaders() {
    return responseHeaders;
  }

  /**
   * Why a route was refused for the data in the request, off the decision; null when the response
   * carried none. Set on a {@code route_data_policy} refusal, so an app can say which route is not
   * approved for which category without parsing the message.
   */
  public RoutePolicy routePolicy() {
    ShashuDecision decision = Shashu.decisionOf(this);
    return decision == null ? null : decision.routePolicy();
  }

  /** Store response headers on this exception for later inspection. */
  public ShashuException attachResponseHeaders(Map<String, String> headers) {
    this.responseHeaders = (headers == null || headers.isEmpty()) ? Map.of() : Map.copyOf(headers);
    return this;
  }

  /**
   * Map an HTTP error response to the most-specific {@link ShashuException} subclass. Currently:
   *
   * <ul>
   *   <li>403 &rarr; {@link ShashuPolicyViolation}, with {@code error.message} parsed from the JSON
   *       body.
   *   <li>400 carrying {@code error.type} {@code "content_filter"} &rarr; {@link
   *       ShashuContentFilterException}.
   *   <li>everything else &rarr; {@link ShashuException}. When the body is the gateway's own
   *       refusal under a contract version (it carries the {@code error.trustgate} envelope: a 402
   *       plan limit, a 429 quota, a 503 fail-closed scan), the message is its plain-English {@code
   *       error.message}; otherwise it is the status and a body snippet.
   * </ul>
   *
   * <p><b>Dispatch is on the TRANSPORT STATUS, not on {@code error.code}.</b> That field is a §3
   * reason ({@code jailbreak}, {@code dlp}, {@code egress_denied}, {@code auth_expired}, …) on
   * every path served under contract version {@code 2026-09-01}, which this SDK declares by
   * default; the HTTP status as a string ({@code "403"}) on the legacy contract, which a caller
   * reaches only by opting out of the version header; the literal {@code "invalid_request"} on a
   * schema-invalid body; and absent altogether on the 500 crash handler and the Anthropic-shaped
   * {@code /v1/messages} envelope. Only the second of those four is a status, and where it IS a
   * status it always equals the transport status — so reading it could only ever introduce a
   * disagreement, never resolve one. The status is the one value present on every response.
   *
   * <p>{@code error.type} is read for exactly one value, {@code "content_filter"}, and only on a
   * 400. Every other 4xx the gateway raises itself carries {@code "invalid_request_error"} on the
   * legacy contract, and a value that merely restates the status ({@code permission_error} on a
   * 403, {@code authentication_error} on a 401) under the contract — neither refines what the
   * status already said.
   */
  public static ShashuException fromResponse(int statusCode, String body) {
    if (statusCode == 401) {
      String message = ai.shashu.sdk.internal.CredentialResolver.errorMessage(body);
      String code = ai.shashu.sdk.internal.CredentialResolver.errorCode(body);
      if (message == null) {
        String snippet = body.length() <= 256 ? body : body.substring(0, 256) + "...";
        message = "SHASHU request failed with HTTP 401: " + snippet;
      }
      return new ShashuAuthenticationException(statusCode, body, message, code);
    }
    if (statusCode == 403) {
      return ShashuPolicyViolation.fromBody(body);
    }
    if (statusCode == 400 && isContentFilter(body)) {
      return ShashuContentFilterException.fromBody(statusCode, body);
    }
    String sentence = contractSentence(body);
    if (sentence != null) {
      return new ShashuException(statusCode, body, sentence);
    }
    String snippet = body.length() <= 256 ? body : body.substring(0, 256) + "...";
    return new ShashuException(
        statusCode, body, "Shashu request failed with HTTP " + statusCode + ": " + snippet);
  }

  /**
   * The plain-English {@code error.message} of a gateway refusal served under a contract version,
   * or {@code null} when the body is not one.
   *
   * <p>Only a body carrying the nested {@code error.trustgate} envelope counts: that is the
   * gateway's own refusal (402 plan limit, 429 quota, 503 fail-closed, …), whose message was
   * written for the person reading it. Without the envelope the body could be anything, a provider
   * error or a proxy page, so the status-and-snippet message stays.
   */
  private static String contractSentence(String body) {
    if (body == null || body.isEmpty()) {
      return null;
    }
    try {
      JsonNode err = CONTENT_FILTER_MAPPER.readTree(body).path("error");
      if (!err.isObject() || !err.path("trustgate").isObject()) {
        return null;
      }
      String message = err.path("message").asText(null);
      return (message == null || message.isBlank()) ? null : message;
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * True when the body is the gateway's {@code explicit}-encoding content block: {@code error.type
   * == "content_filter"}.
   *
   * <p>Fails CLOSED in the direction that matters. An unparseable body, a missing {@code error}
   * object or any other {@code type} returns false, so the response stays a plain {@link
   * ShashuException} on its real status — a 400 that is genuinely a malformed request is never
   * reported as a block. The reverse mistake (a block reported as a malformed request) is the
   * defect this method exists to end, and it needs a positive {@code "content_filter"} to avoid.
   */
  private static boolean isContentFilter(String body) {
    if (body == null || body.isEmpty()) {
      return false;
    }
    try {
      JsonNode err = CONTENT_FILTER_MAPPER.readTree(body).path("error");
      return err.isObject() && "content_filter".equals(err.path("type").asText(null));
    } catch (Exception ignored) {
      return false;
    }
  }
}
