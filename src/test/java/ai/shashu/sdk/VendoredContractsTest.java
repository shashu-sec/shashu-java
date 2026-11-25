package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The vendored contract pages are the gateway's pages, byte for byte. Digests are taken over
 * LF-normalised bytes so a CRLF checkout cannot change them.
 */
class VendoredContractsTest {

  private static final Map<String, String> PINNED =
      Map.of(
          "sdk-surface.v1.md",
          "3984c1c3527f515e01a89b1635d3c4f4d42439c74c742c4871de9e555fb9051b",
          "decision-envelope.v1.md",
          "b47bfc39bb02515e68d1b693661b6e7605b921bc7cbe2fae940bcac2f5ff65f6",
          "rag_context.v1.schema.json",
          "25894f3aedbfe96e7e1cdd12ab56d1a316eed8b8fb4dcad603ed4c0d67574fee",
          "memory_context.v1.schema.json",
          "155a01d2263421eeb4376ba54b98ce9e12d660df86ccfc2fb5f54619189ee40e");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sdk-surface.v1.md",
        "decision-envelope.v1.md",
        "rag_context.v1.schema.json",
        "memory_context.v1.schema.json"
      })
  void vendored_contract_is_unmodified(String name) throws Exception {
    Path path = Path.of("contracts").resolve(name);
    assertTrue(Files.isRegularFile(path), name + " is missing from contracts/");
    assertEquals(PINNED.get(name), digest(path), name + " is not the copy that was vendored");
  }

  @Test
  void the_surface_page_names_the_current_retrieval_holder() throws Exception {
    String text = Files.readString(Path.of("contracts/sdk-surface.v1.md"));
    assertTrue(text.contains("Retrieval.retrievalScope"));
    assertTrue(text.contains("Retrieval.RetrievalScope"));
  }

  private static String digest(Path path) throws Exception {
    byte[] raw = Files.readAllBytes(path);
    byte[] lf =
        new String(raw, StandardCharsets.ISO_8859_1)
            .replace("\r\n", "\n")
            .getBytes(StandardCharsets.ISO_8859_1);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(lf);
    return HexFormat.of().formatHex(digest);
  }
}
