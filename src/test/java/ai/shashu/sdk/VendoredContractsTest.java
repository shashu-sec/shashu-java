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
          "0ee154022de6014efbe6e008460ed8d5f1d07e860ff2bfed726f3a02f5ebbfef",
          "decision-envelope.v1.md",
          "ada3a1771bf593f19c2edf3bf2ec578c6fb69c75399f4007317aedd8f0ca691f",
          "rag_context.v1.schema.json",
          "4cf08786149c40c3a046787f5bcbf1cde9e780bb25008a0abbc894237c8a4b3f",
          "memory_context.v1.schema.json",
          "afe38fb5e45525e94f2285c15ed0de60e614f4661577e7e43af3131743ca7bdf");

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
