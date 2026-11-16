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
          "166630b34367bfec8499c996badaece5250102d4aa71f68be6519b243e75008f",
          "decision-envelope.v1.md",
          "fa98318efb952ff586116adaf5cd2b6f067f5847bbfd475f16b46dd38e56ca78",
          "rag_context.v1.schema.json",
          "8c9729f6c243061710387d63f8f43301bb2472abffdf3aa2808979dce6305b26",
          "memory_context.v1.schema.json",
          "b77034e097648bed41d44d1733dd673b45133cc15fbbf937338231bc9d9edd49");

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
