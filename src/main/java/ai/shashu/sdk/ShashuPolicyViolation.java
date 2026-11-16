package ai.shashu.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Specialised {@link ShashuException} raised when the gateway returns HTTP 403. Parses the
 * gateway's error body to surface the human-readable reason via {@link #getMessage()}.
 *
 * <h2>What a 403 actually is</h2>
 *
 * <p>A 403 is an <em>access</em> decision: the request was refused before the model was ever asked.
 * Today three different producers can emit one, and they are not interchangeable:
 *
 * <ol>
 *   <li><b>A gateway access decision</b> — the model is not in the agent's allowed routes, the
 *       monthly budget is exhausted, a tool is denied, or a loop guardrail tripped. This is the
 *       case the class is named for.
 *   <li><b>A provider permission failure, passed through</b> — the gateway's stored provider
 *       credential is valid but has no access to the target model. Nothing about the customer's
 *       Shashu policy is wrong. These carry {@code error.type} = {@code "ProviderPermissionError"}
 *       and a message prefixed {@code "TrustGate Provider Error: "}.
 *   <li><b>An Anthropic-shaped envelope</b> — on {@code /v1/messages}, when the deployment sets
 *       {@code TRUSTGATE_WIRE_SHAPE_MESSAGES=enforce}, the gateway rewrites access-decision 403s
 *       into Anthropic's error shape and {@link #errorType()} reads {@code "permission_error"}
 *       instead.
 * </ol>
 *
 * <h2>A security block is NOT a 403</h2>
 *
 * <p><b>Catching this exception does not catch a DLP, prompt-injection, or content-scan block.</b>
 * A block has two encodings, and neither of them is a 403:
 *
 * <ul>
 *   <li><b>{@code explicit}</b> (the default at contract {@code 2026-09-01}, which this SDK
 *       declares by default since 0.3.0-alpha) — <b>HTTP 400</b> with {@code error.type
 *       "content_filter"}, raised as {@link ShashuContentFilterException}.
 *   <li><b>{@code compatible} / legacy</b> — <b>HTTP 200</b> carrying the refusal, with the verdict
 *       in {@code finish_reason "content_filter"} rather than the status code. Reached only through
 *       the {@code compatible} encoding or a caller that opted out of the version header. Raised as
 *       {@link ShashuContentFilterException}.
 * </ul>
 *
 * <p>A caller that handles only this exception will hand a refusal downstream as if it were the
 * model's answer. Both block encodings raise {@link ShashuContentFilterException}:
 *
 * <pre>{@code
 * try {
 *     var response = client.chat().completions().create(request);
 *     use(response.choices().get(0).message().content());
 * } catch (ShashuContentFilterException ex) {       // 400 (or 200) — security block
 *     handleBlocked(ex.getMessage());                  // ex.code() names the finding when present
 * } catch (ShashuPolicyViolation ex) {              // 403 — access decision
 *     handleDenied(ex.getMessage());
 * } catch (ShashuException ex) {
 *     if (ex.statusCode() >= 500) {
 *         // Network / upstream issue; retry.
 *     }
 * }
 * }</pre>
 *
 * <h2>The bodies, as captured from a live gateway (2026-09-01)</h2>
 *
 * <p>Under the contract this SDK declares by default, a 403 carries {@code error.type
 * "permission_error"}, a reason in {@code error.code} ({@code tool_policy}, {@code
 * feature_disabled}, {@code route_data_policy}, …) and the decision envelope nested in {@code
 * error.trustgate}. The body below is the legacy contract's shape, which a caller still sees after
 * opting out of the version header.
 *
 * <p>Access decision on the legacy contract, on {@code /v1/chat/completions}, {@code
 * /v1/completions}, {@code /v1/responses} and {@code /v1/conversations}:
 *
 * <pre>{@code
 * {
 *   "error": {
 *     "message": "Access Denied: Model 'claude-3-opus-20240229' is not in the allowed routes for this agent.",
 *     "type": "invalid_request_error",
 *     "param": null,
 *     "code": "403"
 *   },
 *   "choices": []
 * }
 * }</pre>
 *
 * <p>The same decision on {@code /v1/messages} with the Messages wire shape enforced:
 *
 * <pre>{@code
 * {
 *   "type": "error",
 *   "error": {
 *     "type": "permission_error",
 *     "message": "Access Denied: Model 'claude-3-opus-20240229' is not in the allowed routes for this agent."
 *   }
 * }
 * }</pre>
 *
 * <p><b>{@code error.type} is not a policy discriminator.</b> On the legacy OpenAI envelope it is
 * {@code "invalid_request_error"} for every 4xx the gateway raises itself — a 401, a 403 and a 422
 * are indistinguishable by it; under the contract it restates the status ({@code permission_error}
 * on a 403). Do not branch on {@link #errorType()}; branch on {@link #statusCode()} and, if you
 * must, on the message text. Sub-typing budget-vs-policy is tracked as {@code
 * TRUSTGATE-403-DISCRIMINATOR} and is not available today — <a
 * href="https://github.com/aisquad001/trustgate-ai/issues/736">trustgate-ai#736</a>.
 */
public class ShashuPolicyViolation extends ShashuException {

  private static final ObjectMapper PARSE_MAPPER = new ObjectMapper();

  /** The {@code error.type} field from the gateway's response, if present. */
  private final String errorType;

  ShashuPolicyViolation(int statusCode, String responseBody, String message, String errorType) {
    super(statusCode, responseBody, message);
    this.errorType = errorType;
  }

  /** The {@code error.type} field from the gateway envelope, if present. */
  public String errorType() {
    return errorType;
  }

  static ShashuPolicyViolation fromBody(String body) {
    String message = "Shashu policy violation (403)";
    String errorType = null;
    boolean memoryWriteBlocked = false;

    try {
      JsonNode root = PARSE_MAPPER.readTree(body);
      // A refused memory write is the one 403 with its own type, read off
      // the decision's memory block rather than off `code`.
      ShashuDecision decision = Shashu.decisionFrom(root, null);
      memoryWriteBlocked = decision != null && decision.memoryBlocked() > 0;
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
      } else {
        JsonNode topMsg = root.path("message");
        if (topMsg.isTextual() && !topMsg.asText().isEmpty()) {
          message = topMsg.asText();
        }
      }
    } catch (Exception ignored) {
      // Body wasn't valid JSON — keep the default message.
    }

    return memoryWriteBlocked
        ? new MemoryWriteBlocked(403, body, message, errorType)
        : new ShashuPolicyViolation(403, body, message, errorType);
  }
}
