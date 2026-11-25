package ai.shashu.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * The gateway blocked the content. ONE type, both encodings.
 *
 * <h2>Two encodings, one event</h2>
 *
 * <p>A security block reaches the caller in one of two shapes, and which one depends on the
 * contract version the request declares and on an account default the caller may not control:
 *
 * <ul>
 *   <li><b>{@code explicit}</b> (the default at contract {@code 2026-09-01}) — <b>HTTP 400</b>,
 *       {@code error.type "content_filter"} and {@code error.code} naming the finding. This
 *       exception, with the plain-English sentence as its message. Since 0.3.0-alpha the SDK
 *       declares {@code shashu-version: 2026-09-01} on every request ({@link
 *       ShashuClient#DEFAULT_CONTRACT_VERSION}), so this is the default path.
 *   <li><b>{@code compatible} / legacy</b> — <b>HTTP 200</b>, {@code finish_reason
 *       "content_filter"} on the choice. This exception. Reached when the account's operator chose
 *       the {@code compatible} encoding, or when the caller opted out of the version header ({@code
 *       contractVersion(null)}) on an account served the legacy contract.
 * </ul>
 *
 * <p><b>Why this type exists.</b> The 400 encoding used to raise a bare {@link ShashuException}
 * carrying "Shashu request failed with HTTP 400", which is indistinguishable from a malformed
 * request — so a caller with correct code was pointed at a payload that was never the problem, and
 * the block never reached whatever handles blocks. A refusal must not present as the caller's
 * mistake.
 *
 * <h2>What to catch</h2>
 *
 * <p><b>What to catch.</b> Both encodings raise this exception:
 *
 * <pre>{@code
 * try {
 *     var response = client.chat().completions().create(request);
 *     use(response.choices().get(0).message().content());
 * } catch (ShashuContentFilterException ex) {       // 400 (or 200) — security block
 *     handleBlocked(ex.getMessage());                  // ex.code() names the finding when present
 * } catch (ShashuPolicyViolation ex) {              // 403 — access decision
 *     handleDenied(ex.getMessage());
 * }
 * }</pre>
 *
 * <h2>The body, as the gateway's own builders emit it</h2>
 *
 * <pre>{@code
 * {
 *   "error": {
 *     "message": "I cannot fulfill this request because it violates the organization's security policy.",
 *     "type": "content_filter",
 *     "param": null,
 *     "code": "jailbreak",
 *     "shashu": { "decision": "block", "code": "jailbreak", ... }
 *   },
 *   "choices": []
 * }
 * }</pre>
 *
 * <p>{@link #code()} is the §3 finding: {@code jailbreak}, {@code dlp}, {@code pii_output}, {@code
 * secret} or {@code prompt_injection}. It is the field that says WHICH detector refused, and it is
 * why {@code error.code} is no longer the HTTP status restated.
 */
public final class ShashuContentFilterException extends ShashuException {

  private static final ObjectMapper PARSE_MAPPER = new ObjectMapper();

  /** The {@code error.type} field from the gateway's response, if present. */
  private final String errorType;

  /** The §3 finding from {@code error.code}, if present. */
  private final String code;

  private ShashuContentFilterException(
      int statusCode, String responseBody, String message, String errorType, String code) {
    super(statusCode, responseBody, message);
    this.errorType = errorType;
    this.code = code;
  }

  /** The {@code error.type} field from the gateway envelope, if present. */
  public String errorType() {
    return errorType;
  }

  /** The §3 finding — {@code jailbreak}, {@code dlp}, … — or null if absent. */
  public String code() {
    return code;
  }

  static ShashuContentFilterException fromBody(int statusCode, String body) {
    String message = "Shashu blocked this request's content";
    String errorType = null;
    String code = null;

    try {
      JsonNode root = PARSE_MAPPER.readTree(body);
      JsonNode err = root.path("error");
      if (err.isObject()) {
        JsonNode msgNode = err.path("message");
        if (msgNode.isTextual() && !msgNode.asText().isEmpty()) {
          message = msgNode.asText();
        }
        JsonNode typeNode = err.path("type");
        if (typeNode.isTextual()) {
          errorType = typeNode.asText();
        }
        JsonNode codeNode = err.path("code");
        if (codeNode.isTextual()) {
          code = codeNode.asText();
        }
      }
    } catch (Exception ignored) {
      // Body wasn't valid JSON — keep the default message. Never a reason
      // to downgrade the exception TYPE: the status and error.type already
      // said this was a block.
    }

    return new ShashuContentFilterException(statusCode, body, message, errorType, code);
  }

  /**
   * The {@code compatible} / legacy encoding: HTTP 200, {@code finish_reason == "content_filter"}.
   * Same exception as the 400.
   */
  public static ShashuContentFilterException fromCompatible(
      String body, Map<String, String> headers) {
    String message = "Shashu blocked this request's content";
    String code = null;
    try {
      JsonNode root = PARSE_MAPPER.readTree(body);
      JsonNode choices = root.path("choices");
      if (choices.isArray() && choices.size() > 0) {
        JsonNode content = choices.get(0).path("message").path("content");
        if (content.isTextual() && !content.asText().isEmpty()) {
          message = content.asText();
        }
      }
      JsonNode codeNode = root.path("shashu").path("code");
      if (codeNode.isTextual()) {
        code = codeNode.asText();
      }
    } catch (Exception ignored) {
      // Keep the default message. The finish_reason already said this was a block.
    }
    ShashuContentFilterException ex =
        new ShashuContentFilterException(200, body, message, "content_filter", code);
    if (headers != null) {
      ex.attachResponseHeaders(headers);
    }
    return ex;
  }

  /**
   * A streamed {@code finish_reason == "content_filter"} chunk. Same event as {@link
   * #fromCompatible}; the body is the stream, not a JSON object we can re-parse for the sentence.
   */
  public static ShashuContentFilterException fromStreamChunk() {
    return new ShashuContentFilterException(
        200, "", "Shashu blocked this request's content", "content_filter", null);
  }
}
