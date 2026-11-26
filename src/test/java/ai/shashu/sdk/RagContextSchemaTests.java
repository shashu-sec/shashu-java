package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.harness.RagContextSchema;
import ai.shashu.sdk.internal.RagContextBuilder;
import ai.shashu.sdk.internal.RetrievalTestAccess;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds the test harness's schema validator to the real contract, and the SDK's restated {@code
 * source} enum and caps to the schema's.
 *
 * <p>The validator is the instrument every other retrieval test measures with, so it is calibrated
 * first: the contract ships a fixture corpus naming exactly what must be accepted and what must be
 * rejected, and a validator that silently ignored a keyword fails here rather than passing a
 * malformed payload somewhere it matters.
 */
@DisplayName("The contract, and the instrument that measures against it")
class RagContextSchemaTests {

  static List<String> validFixtures() {
    return RagContextSchema.fixtureNames("valid");
  }

  static List<String> invalidFixtures() {
    return RagContextSchema.fixtureNames("invalid");
  }

  @Test
  @DisplayName("the contract is found by walking up from the module, not vendored into it")
  void the_contract_is_read_where_it_lives() {
    assertEquals(
        "https://shashu.ai/contracts/rag_context.v1.schema.json",
        RagContextSchema.document().get("$id").asText());
  }

  @Test
  @DisplayName("the fixture corpus is not empty")
  void the_fixture_corpus_is_not_empty() {
    // A validator proved against zero fixtures is proved against nothing -
    // and a mis-resolved path would otherwise read as a clean pass.
    assertTrue(validFixtures().size() >= 8, "only " + validFixtures().size() + " valid fixtures");
    assertTrue(
        invalidFixtures().size() >= 15, "only " + invalidFixtures().size() + " invalid fixtures");
  }

  @ParameterizedTest(name = "valid/{0}")
  @MethodSource("validFixtures")
  void every_valid_fixture_is_accepted(String name) {
    var payload = RagContextSchema.fixture("valid", name);
    assertEquals(List.of(), RagContextSchema.validate(payload), name + " should validate");
  }

  @ParameterizedTest(name = "invalid/{0}")
  @MethodSource("invalidFixtures")
  void every_invalid_fixture_is_rejected(String name) {
    var payload = RagContextSchema.fixture("invalid", name);
    assertFalse(RagContextSchema.isValid(payload), name + " should have been rejected");
  }

  @Test
  @DisplayName("the SDK's restated source enum matches the contract's")
  void the_restated_source_enum_matches_the_schema() {
    // The SDK restates the enum so it needs no JSON file on disk to make a
    // call. This is the fence that keeps the copy honest.
    assertEquals(RagContextSchema.sourceEnum(), RagContextBuilder.SOURCES);
  }

  @Test
  @DisplayName("the SDK's compiled caps match the contract's maxima")
  void the_caps_match_the_schema() {
    Map<String, Integer> sdk = RetrievalTestAccess.caps();
    Map<String, Integer> schema = RagContextSchema.schemaCaps();
    for (Map.Entry<String, Integer> entry : schema.entrySet()) {
      assertEquals(
          entry.getValue(),
          sdk.get(entry.getKey()),
          entry.getKey() + " has drifted from the schema");
    }
  }
}
