package ai.shashu.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the envelope's three optional blocks — {@code attachments}, {@code memory}, {@code
 * route_policy} — and the two count-only headers.
 *
 * <p>Tolerant by construction: a block that is not an object is absent, a field of the wrong type
 * is null (or zero, for a count), an unknown field is ignored, and an unknown attachment state is
 * {@link AttachmentState#UNKNOWN}. Nothing here throws on what the gateway sends.
 */
final class DecisionBlocks {

  static final String HEADER_ATTACHMENTS = "x-trustgate-attachments";
  static final String HEADER_MEMORY = "x-trustgate-memory";
  static final String HEADER_INSPECTION = "x-trustgate-inspection";

  private DecisionBlocks() {}

  static Attachments attachmentsFromBody(JsonNode block) {
    if (block == null || !block.isObject()) {
      return null;
    }
    List<Attachment> items = new ArrayList<>();
    for (JsonNode item : objects(block.get("items"))) {
      List<AttachmentFinding> findings = new ArrayList<>();
      for (JsonNode f : objects(item.get("findings"))) {
        findings.add(
            new AttachmentFinding(
                text(f, "detector"),
                text(f, "category"),
                text(f, "code"),
                text(f, "severity"),
                text(f, "locator")));
      }
      String rawState = text(item, "state");
      JsonNode bytes = item.get("bytes");
      JsonNode index = item.get("index");
      items.add(
          new Attachment(
              AttachmentState.fromWire(rawState),
              rawState,
              text(item, "reason"),
              text(item, "filename"),
              text(item, "declared_type"),
              text(item, "sniffed_type"),
              text(item, "sha256"),
              bytes != null && bytes.isIntegralNumber() ? bytes.asLong() : null,
              text(item, "source"),
              index != null && index.canConvertToInt() && index.isIntegralNumber()
                  ? index.asInt()
                  : null,
              text(item, "path"),
              findings));
    }
    return new Attachments(
        count(block, "total"),
        count(block, "scanned"),
        count(block, "scanned_partial"),
        count(block, "not_inspected"),
        count(block, "blocked"),
        items,
        "body");
  }

  static MemoryActivity memoryFromBody(JsonNode block) {
    if (block == null || !block.isObject()) {
      return null;
    }
    List<MemoryItem> items = new ArrayList<>();
    for (JsonNode i : objects(block.get("items"))) {
      JsonNode seen = i.get("seen_before");
      items.add(
          new MemoryItem(
              text(i, "kind"),
              text(i, "state"),
              text(i, "reason"),
              text(i, "tool"),
              text(i, "store"),
              text(i, "scope"),
              text(i, "source"),
              text(i, "enforcement"),
              text(i, "memory_ref"),
              text(i, "write_ref"),
              seen != null && seen.isBoolean() ? seen.asBoolean() : null));
    }
    return new MemoryActivity(
        count(block, "writes"),
        count(block, "writes_blocked"),
        count(block, "recalls"),
        count(block, "recalls_flagged"),
        text(block, "coverage"),
        items,
        "body");
  }

  static RoutePolicy routePolicyFromBody(JsonNode block) {
    if (block == null || !block.isObject()) {
      return null;
    }
    return new RoutePolicy(
        text(block, "route"),
        text(block, "category"),
        text(block, "reason"),
        text(block, "requested_route"),
        text(block, "degraded_route"));
  }

  static Attachments attachmentsFromHeader(String value) {
    Map<String, Integer> c = counts(value);
    if (c == null) {
      return null;
    }
    int total = c.getOrDefault("total", 0);
    int scanned = c.getOrDefault("scanned", 0);
    int notInspected = c.getOrDefault("not_inspected", 0);
    int blocked = c.getOrDefault("blocked", 0);
    // The header does not carry scanned_partial. The four states partition
    // `total`, so the remainder is scanned_partial — which errs, if the
    // header is ever wrong, toward counting a file as unverified.
    int partial = Math.max(0, total - scanned - notInspected - blocked);
    return new Attachments(total, scanned, partial, notInspected, blocked, List.of(), "header");
  }

  static MemoryActivity memoryFromHeader(String value) {
    Map<String, Integer> c = counts(value);
    if (c == null) {
      return null;
    }
    return new MemoryActivity(
        c.getOrDefault("writes", 0),
        c.getOrDefault("blocked", 0),
        c.getOrDefault("recalls", 0),
        c.getOrDefault("flagged", 0),
        null,
        List.of(),
        "header");
  }

  /** The body's {@code inspection} object; text slots and text detector states only. */
  static Map<String, Object> inspectionFromBody(JsonNode block) {
    if (block == null || !block.isObject()) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (String slot : new String[] {"input", "output", "reason"}) {
      String v = text(block, slot);
      if (v != null) {
        out.put(slot, v);
      }
    }
    JsonNode det = block.get("detectors");
    if (det != null && det.isObject()) {
      Map<String, String> detectors = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> e : det.properties()) {
        if (e.getValue().isTextual()) {
          detectors.put(e.getKey(), e.getValue().asText());
        }
      }
      if (!detectors.isEmpty()) {
        out.put("detectors", Collections.unmodifiableMap(detectors));
      }
    }
    return out.isEmpty() ? null : Collections.unmodifiableMap(out);
  }

  /**
   * {@code input=checked;output=skipped;reason=...;memory=partial} to the body's shape: {@code
   * input}, {@code output} and {@code reason} are named slots, every other pair a detector.
   */
  static Map<String, Object> inspectionFromHeader(String value) {
    if (value == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, String> detectors = new LinkedHashMap<>();
    for (String part : value.split(";")) {
      int eq = part.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String name = part.substring(0, eq).trim();
      String state = part.substring(eq + 1).trim();
      if (name.equals("input") || name.equals("output") || name.equals("reason")) {
        out.put(name, state);
      } else if (!name.isEmpty()) {
        detectors.put(name, state);
      }
    }
    if (!detectors.isEmpty()) {
      out.put("detectors", Collections.unmodifiableMap(detectors));
    }
    return out.isEmpty() ? null : Collections.unmodifiableMap(out);
  }

  /** {@code a=1;b=2} to a map; unknown names kept, junk dropped, null when nothing parsed. */
  private static Map<String, Integer> counts(String value) {
    if (value == null) {
      return null;
    }
    Map<String, Integer> out = new HashMap<>();
    for (String part : value.split(";")) {
      int eq = part.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String name = part.substring(0, eq).trim();
      String n = part.substring(eq + 1).trim();
      if (name.isEmpty()
          || n.isEmpty()
          || n.length() > 9
          || !n.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
        continue;
      }
      out.put(name, Integer.parseInt(n));
    }
    return out.isEmpty() ? null : out;
  }

  private static List<JsonNode> objects(JsonNode array) {
    if (array == null || !array.isArray()) {
      return List.of();
    }
    List<JsonNode> out = new ArrayList<>(array.size());
    for (JsonNode n : array) {
      if (n != null && n.isObject()) {
        out.add(n);
      }
    }
    return out;
  }

  private static String text(JsonNode obj, String field) {
    JsonNode n = obj.get(field);
    return n != null && n.isTextual() ? n.asText() : null;
  }

  private static int count(JsonNode obj, String field) {
    JsonNode n = obj.get(field);
    return n != null && n.isIntegralNumber() && n.canConvertToInt() && n.asInt() > 0
        ? n.asInt()
        : 0;
  }
}
