package ai.shashu.sdk;

import ai.shashu.sdk.chat.ChatCompletionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;

/**
 * Decision reading and block recognition. Names from {@code contracts/sdk-surface.v1.md}: {@code
 * decisionOf}, {@code isBlock}, {@code decisionFrom}.
 *
 * <p>Absence returns nothing — not a synthesised {@code allow}, not a {@code fail}. An unrecognised
 * {@code decision} is a block. No copy of the {@code code} vocabulary is held; block-ness is
 * carried by {@code decision} alone.
 */
public final class Shashu {

  /** HTTP 401 code when no Agents identity provider is configured. */
  public static final String PASS_PROVIDER_NOT_SET_UP = PassCodes.PASS_PROVIDER_NOT_SET_UP;

  /** HTTP 401 code when pass sign-in is used without the Enterprise plan. */
  public static final String PASS_NOT_LICENSED = PassCodes.PASS_NOT_LICENSED;

  /** HTTP 401 code when the presented pass is past its expiry; the SDK retries once. */
  public static final String PASS_EXPIRED = PassCodes.PASS_EXPIRED;

  /** HTTP 401 code when the pass audience is not this install. */
  public static final String PASS_WRONG_AUDIENCE = PassCodes.PASS_WRONG_AUDIENCE;

  /** HTTP 401 code when the pass issuer is not the configured provider. */
  public static final String PASS_WRONG_ISSUER = PassCodes.PASS_WRONG_ISSUER;

  /** HTTP 401 code when no agent is registered for this identity. */
  public static final String PASS_UNKNOWN_AGENT = PassCodes.PASS_UNKNOWN_AGENT;

  /** The six {@code pass_*} refusal codes the gateway can return. */
  public static final java.util.Set<String> PASS_CODES = PassCodes.SET;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Decisions where the caller holds the provider's content. */
  private static final Set<String> DELIVERED = Set.of("allow", "allow_altered", "not_checked");

  /** The six §2 decisions. Frozen; the gateway's CI fails a seventh. */
  private static final Set<String> DECISIONS =
      Set.of("allow", "allow_altered", "not_checked", "block", "refuse", "fail");

  /** The one decision {@link #isBlock} is about. */
  private static final String BLOCK = "block";

  /**
   * A wire value reduced to a known decision, failing CLOSED.
   *
   * <p>Mirrors Python's {@code _coerce_decision} and the gateway's {@code envelope.decision_of}:
   * anything this cannot positively recognise — an unknown string, a null — is a block. Coercing
   * BEFORE the comparison is what lets {@link #isBlock} be a bare equality on {@code block} and
   * still fail closed on a decision invented after this jar shipped.
   */
  private static String coerceDecision(String raw) {
    return (raw != null && DECISIONS.contains(raw)) ? raw : BLOCK;
  }

  private Shashu() {}

  /** Read the decision off whatever the caller is holding. Absence returns {@code null}. */
  public static ShashuDecision decisionOf(Object resultOrError) {
    if (resultOrError == null) {
      return null;
    }
    if (resultOrError instanceof ShashuDecision already) {
      return already;
    }
    if (resultOrError instanceof ChatCompletionResponse chat) {
      return decisionFrom(chat, chat.trustGateHeaders());
    }
    if (resultOrError instanceof ShashuException ex) {
      return decisionFrom(ex.responseBody(), ex.responseHeaders());
    }
    return decisionFrom(resultOrError, null);
  }

  /**
   * True when the caller is holding a Shashu content BLOCK. Inspects; never patches or wraps.
   * Absence is {@code false}. A decision this method cannot fully parse — or an unrecognised {@code
   * decision} — is {@code true} (fail closed). Reads {@code decision} only.
   *
   * <p><b>It means {@code decision == "block"}, and nothing else.</b> Until 2026-09-17 it was
   * {@code !DELIVERED.contains(decision)}, so a {@code refuse} (a policy refusal — a missing API
   * key, a disabled feature, an address we do not serve) and a {@code fail} (we broke; nobody
   * decided) both answered {@code true}. That truthfully answers a different and useful question —
   * "did I get content at all" — but under THIS name it tells a customer refused on policy that a
   * guardrail fired on their content, which sends them to review a prompt nothing inspected. Python
   * and Node have always been {@code decision == "block"}; this is the reconciliation. No "was it
   * delivered" accessor is added here.
   *
   * <p>The opposite direction lives in {@link ShashuException#fromResponse}: {@code
   * isContentFilter} decides whether to REPLACE a generic / provider error with {@link
   * ShashuContentFilterException} and deliberately fails open as to block-ness (unparseable body →
   * not a content-filter replacement). Both are correct. The two directions have already caused
   * confusion twice — this method is fail-closed on block-ness because a wrong "no" treats blocked
   * content as delivered; that method is fail-open on block-ness because a wrong "yes" reports a
   * malformed request as a security block.
   */
  public static boolean isBlock(Object err) {
    if (err == null) {
      return false;
    }
    Probe probe = probe(err);
    if (probe.kind == ProbeKind.ABSENT) {
      return false;
    }
    if (probe.kind == ProbeKind.UNPARSEABLE) {
      return true;
    }
    return BLOCK.equals(coerceDecision(probe.decision));
  }

  /**
   * Parse the two plain carriers. Either argument may be absent. Absence in both returns {@code
   * null}.
   */
  public static ShashuDecision decisionFrom(Object body, Map<String, String> headers) {
    ShashuDecision fromBody = readBody(body);
    if (fromBody != null) {
      return fromBody;
    }
    return readHeaders(headers);
  }

  private enum ProbeKind {
    ABSENT,
    UNPARSEABLE,
    PRESENT
  }

  private static final class Probe {
    final ProbeKind kind;
    final String decision;

    Probe(ProbeKind kind, String decision) {
      this.kind = kind;
      this.decision = decision;
    }
  }

  private static Probe probe(Object err) {
    if (err instanceof ShashuContentFilterException cf) {
      ShashuDecision parsed = decisionFrom(cf.responseBody(), cf.responseHeaders());
      if (parsed != null) {
        return new Probe(ProbeKind.PRESENT, parsed.decision());
      }
      return new Probe(ProbeKind.UNPARSEABLE, null);
    }
    ShashuDecision decision = decisionOf(err);
    if (decision != null) {
      return new Probe(ProbeKind.PRESENT, decision.decision());
    }
    if (carrierPresentButUnreadable(err)) {
      return new Probe(ProbeKind.UNPARSEABLE, null);
    }
    return new Probe(ProbeKind.ABSENT, null);
  }

  private static boolean carrierPresentButUnreadable(Object err) {
    if (err instanceof ChatCompletionResponse chat && chat.extraFields().containsKey("trustgate")) {
      return true;
    }
    if (err instanceof ShashuException ex && envelopeObjectPresent(ex.responseBody())) {
      return true;
    }
    if (err instanceof String s && envelopeObjectPresent(s)) {
      return true;
    }
    return false;
  }

  private static boolean envelopeObjectPresent(String json) {
    if (json == null || json.isBlank()) {
      return false;
    }
    try {
      JsonNode env = locateEnvelope(MAPPER.readTree(json));
      return env != null && env.isObject();
    } catch (Exception ignored) {
      return false;
    }
  }

  private static ShashuDecision readBody(Object body) {
    if (body == null) {
      return null;
    }
    if (body instanceof ShashuDecision d) {
      return d;
    }
    if (body instanceof ChatCompletionResponse chat) {
      return readFromExtra(chat.extraFields());
    }
    if (body instanceof JsonNode node) {
      return readFromNode(node);
    }
    if (body instanceof String s) {
      if (s.isBlank()) {
        return null;
      }
      try {
        return readFromNode(MAPPER.readTree(s));
      } catch (Exception ignored) {
        return null;
      }
    }
    return null;
  }

  private static ShashuDecision readFromExtra(Map<String, JsonNode> extra) {
    if (extra == null) {
      return null;
    }
    JsonNode tg = extra.get("trustgate");
    return tg == null ? null : readEnvelope(tg);
  }

  private static ShashuDecision readFromNode(JsonNode root) {
    if (root == null || !root.isObject()) {
      return null;
    }
    JsonNode env = locateEnvelope(root);
    return env == null ? null : readEnvelope(env);
  }

  private static JsonNode locateEnvelope(JsonNode root) {
    JsonNode top = root.get("trustgate");
    if (top != null && top.isObject()) {
      return top;
    }
    JsonNode err = root.get("error");
    if (err != null && err.isObject()) {
      JsonNode nested = err.get("trustgate");
      if (nested != null && nested.isObject()) {
        return nested;
      }
    }
    return null;
  }

  private static ShashuDecision readEnvelope(JsonNode env) {
    if (env == null || !env.isObject()) {
      return null;
    }
    JsonNode dec = env.get("decision");
    if (dec == null || !dec.isTextual() || dec.asText().isEmpty()) {
      return null;
    }
    String code = null;
    JsonNode codeNode = env.get("code");
    if (codeNode != null && codeNode.isTextual()) {
      code = codeNode.asText();
    }
    Boolean retryable = null;
    JsonNode retryNode = env.get("retryable");
    if (retryNode != null && retryNode.isBoolean()) {
      retryable = retryNode.asBoolean();
    }
    String id = null;
    JsonNode idNode = env.get("id");
    if (idNode != null && idNode.isTextual()) {
      id = idNode.asText();
    }
    return new ShashuDecision(
        dec.asText(),
        code,
        retryable,
        id,
        DecisionBlocks.attachmentsFromBody(env.get("attachments")),
        DecisionBlocks.memoryFromBody(env.get("memory")),
        DecisionBlocks.routePolicyFromBody(env.get("route_policy")),
        DecisionBlocks.inspectionFromBody(env.get("inspection")));
  }

  private static ShashuDecision readHeaders(Map<String, String> headers) {
    if (headers == null) {
      return null;
    }
    String decision = null;
    String code = null;
    String attachments = null;
    String memory = null;
    String inspection = null;
    for (Map.Entry<String, String> e : headers.entrySet()) {
      if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) {
        continue;
      }
      // equalsIgnoreCase, not a lowered copy of every key: it rejects on
      // length before it compares a character, and allocates nothing.
      String k = e.getKey();
      if ("x-trustgate-decision".equalsIgnoreCase(k)) {
        decision = e.getValue().trim();
      } else if ("x-trustgate-code".equalsIgnoreCase(k)) {
        code = e.getValue().trim();
      } else if (DecisionBlocks.HEADER_ATTACHMENTS.equalsIgnoreCase(k)) {
        attachments = e.getValue();
      } else if (DecisionBlocks.HEADER_MEMORY.equalsIgnoreCase(k)) {
        memory = e.getValue();
      } else if (DecisionBlocks.HEADER_INSPECTION.equalsIgnoreCase(k)) {
        inspection = e.getValue();
      }
    }
    if (decision == null) {
      return null;
    }
    // Counts only: nothing per-file and no route policy ever rides a header.
    return new ShashuDecision(
        decision,
        code,
        null,
        null,
        DecisionBlocks.attachmentsFromHeader(attachments),
        DecisionBlocks.memoryFromHeader(memory),
        null,
        DecisionBlocks.inspectionFromHeader(inspection));
  }
}
