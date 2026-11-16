package ai.shashu.sdk.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A validator for {@code contracts/rag_context.v1.schema.json}, written against the keywords that
 * schema actually uses.
 *
 * <p>Hand-rolled rather than pulled from a dependency, matching the .NET harness: the SDK ships no
 * JSON-Schema library and a test-only one would be supply-chain surface bought for one file. The
 * risk that buys is a validator that silently ignores a keyword, so it is not trusted on its own
 * &mdash; {@code RagContextSchemaTests} calibrates it against the contract's own fixture corpus,
 * which names exactly what must be accepted and what must be rejected, before any other test
 * measures with it.
 */
public final class RagContextSchema {

  private RagContextSchema() {}

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{8,64}$");

  private static Path directory;
  private static JsonNode document;

  /**
   * The {@code contracts/} directory, found by walking up from the module. The contract is shared
   * by every SDK and is not vendored into any of them, so it is read where it lives.
   */
  public static synchronized Path directory() {
    if (directory == null) {
      Path here = Paths.get("").toAbsolutePath();
      for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
        Path contracts = candidate.resolve("contracts");
        if (Files.isRegularFile(contracts.resolve("rag_context.v1.schema.json"))) {
          directory = contracts;
          break;
        }
      }
      if (directory == null) {
        throw new IllegalStateException(
            "contracts/rag_context.v1.schema.json not found walking up from " + here);
      }
    }
    return directory;
  }

  /** The schema itself. */
  public static synchronized JsonNode document() {
    if (document == null) {
      document = read(directory().resolve("rag_context.v1.schema.json"));
    }
    return document;
  }

  /** Read any JSON file under {@code contracts/}. */
  public static JsonNode read(Path path) {
    try {
      return MAPPER.readTree(Files.readAllBytes(path));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Fixture file names of one kind, sorted, from the contract's corpus. */
  public static List<String> fixtureNames(String kind) {
    Path dir = directory().resolve("fixtures").resolve("rag_context").resolve(kind);
    try (var stream = Files.list(dir)) {
      return stream
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> p.getFileName().toString())
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** One fixture's content. */
  public static JsonNode fixture(String kind, String name) {
    return read(directory().resolve("fixtures").resolve("rag_context").resolve(kind).resolve(name));
  }

  /** Whether {@code payload} satisfies the contract. */
  public static boolean isValid(JsonNode payload) {
    return validate(payload).isEmpty();
  }

  /** Every way {@code payload} fails the contract, empty when it does not. */
  public static List<String> validate(JsonNode payload) {
    List<String> errors = new ArrayList<>();
    JsonNode schema = document();
    if (payload == null || !payload.isObject()) {
      errors.add("rag_context must be an object");
      return errors;
    }

    JsonNode properties = schema.get("properties");
    Set<String> known = new HashSet<>();
    for (Iterator<String> it = properties.fieldNames(); it.hasNext(); ) {
      known.add(it.next());
    }
    for (Iterator<String> it = payload.fieldNames(); it.hasNext(); ) {
      String key = it.next();
      if (!known.contains(key)) {
        errors.add("unknown top-level key: " + key);
      }
    }
    for (JsonNode required : schema.get("required")) {
      if (!payload.hasNonNull(required.asText())) {
        errors.add("missing required key: " + required.asText());
      }
    }

    if (payload.has("v")) {
      JsonNode v = payload.get("v");
      if (!v.isIntegralNumber() || v.asInt() != 1) {
        errors.add("v must be the integer 1");
      }
    }

    checkString(errors, payload.get("corpus"), "corpus", 1, 256);
    checkString(errors, payload.get("query"), "query", 0, 4096);

    JsonNode source = payload.get("source");
    if (source != null) {
      List<String> allowed = new ArrayList<>();
      for (JsonNode value : properties.get("source").get("enum")) {
        allowed.add(value.asText());
      }
      if (!source.isTextual() || !allowed.contains(source.asText())) {
        errors.add("source is not one of " + allowed);
      }
    }

    JsonNode docs = payload.get("docs");
    if (docs != null) {
      if (!docs.isArray()) {
        errors.add("docs must be an array");
      } else if (docs.size() < 1) {
        errors.add("docs must have at least 1 item");
      } else if (docs.size() > 64) {
        errors.add("docs must have at most 64 items, had " + docs.size());
      } else {
        int index = 0;
        for (JsonNode doc : docs) {
          checkDoc(errors, doc, index++);
        }
      }
    }
    return errors;
  }

  private static void checkDoc(List<String> errors, JsonNode doc, int index) {
    String at = "docs[" + index + "]";
    if (!doc.isObject()) {
      errors.add(at + " must be an object");
      return;
    }
    Set<String> known = Set.of("id", "score", "preview", "source_uri", "hash");
    for (Iterator<String> it = doc.fieldNames(); it.hasNext(); ) {
      String key = it.next();
      if (!known.contains(key)) {
        errors.add(at + " has unknown key: " + key);
      }
    }
    for (String required : List.of("id", "preview")) {
      if (!doc.hasNonNull(required)) {
        errors.add(at + " is missing " + required);
      }
    }
    checkString(errors, doc.get("id"), at + ".id", 1, 512);
    checkString(errors, doc.get("preview"), at + ".preview", 1, 2048);
    checkString(errors, doc.get("source_uri"), at + ".source_uri", 0, Integer.MAX_VALUE);

    JsonNode score = doc.get("score");
    if (score != null) {
      if (!score.isNumber()) {
        errors.add(at + ".score must be a number");
      } else {
        double value = score.asDouble();
        if (value < -1.0d || value > 1000000.0d) {
          errors.add(at + ".score out of range: " + value);
        }
      }
    }

    JsonNode hash = doc.get("hash");
    if (hash != null && (!hash.isTextual() || !HEX.matcher(hash.asText()).matches())) {
      errors.add(at + ".hash is not 8-64 hex characters");
    }
  }

  private static void checkString(
      List<String> errors, JsonNode node, String at, int minLength, int maxLength) {
    if (node == null) {
      return;
    }
    if (!node.isTextual()) {
      errors.add(at + " must be a string");
      return;
    }
    int length = node.asText().length();
    if (length < minLength) {
      errors.add(at + " is shorter than " + minLength);
    }
    if (length > maxLength) {
      errors.add(at + " is longer than " + maxLength + " (" + length + ")");
    }
  }

  /** The {@code source} enum as the schema spells it. */
  public static List<String> sourceEnum() {
    List<String> values = new ArrayList<>();
    for (JsonNode value : document().get("properties").get("source").get("enum")) {
      values.add(value.asText());
    }
    return values;
  }

  /** The caps the schema states, for the fence that holds the SDK's copies to them. */
  public static Map<String, Integer> schemaCaps() {
    JsonNode properties = document().get("properties");
    JsonNode doc = properties.get("docs").get("items").get("properties");
    return Map.of(
        "MAX_DOCS", properties.get("docs").get("maxItems").asInt(),
        "MAX_PREVIEW_CHARS", doc.get("preview").get("maxLength").asInt(),
        "MAX_CORPUS_CHARS", properties.get("corpus").get("maxLength").asInt(),
        "MAX_QUERY_CHARS", properties.get("query").get("maxLength").asInt());
  }
}
