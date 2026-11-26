package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatCompletionResponse;
import ai.shashu.sdk.chat.ChatMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * Regression for the 0.1.1 forward-compat fix: a successful (200) gateway response that carries
 * fields the SDK doesn't model must deserialize cleanly — not throw a client-side parse error.
 * Pre-fix, the central ObjectMapper left FAIL_ON_UNKNOWN_PROPERTIES enabled, so a real provider
 * completion (with extras like {@code system_fingerprint} / {@code service_tier} / {@code
 * prompt_filter_results} and nested unknowns) surfaced as {@code ShashuException("Failed to parse
 * response body")}.
 *
 * <p>Exercised through the public {@code client.chat().completions().create(...)} path, so it
 * proves the CENTRAL mapper (which every response type is read through) tolerates unknowns — not
 * just one annotated DTO.
 */
class UnknownPropertiesTest {

  /** A gateway-style 200 with UNKNOWN top-level + nested fields the SDK doesn't model. */
  private static String bodyWithUnknownFields() {
    return "{"
        + "\"id\":\"chatcmpl-xyz\","
        + "\"object\":\"chat.completion\","
        + "\"created\":1234567890,"
        + "\"model\":\"gpt-4o-mini-2024-07-18\","
        + "\"system_fingerprint\":\"fp_unknown_123\"," // unknown top-level
        + "\"service_tier\":\"default\"," // unknown top-level
        + "\"prompt_filter_results\":[{\"index\":0}]," // unknown top-level (array of objects)
        + "\"choices\":[{"
        + "  \"index\":0,"
        + "  \"message\":{\"role\":\"assistant\",\"content\":\"hi back\",\"refusal\":null,\"annotations\":[]}," // unknown nested (refusal/annotations)
        + "  \"logprobs\":null," // unknown nested
        + "  \"finish_reason\":\"stop\""
        + "}],"
        + "\"usage\":{"
        + "  \"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7,"
        + "  \"prompt_tokens_details\":{\"cached_tokens\":0}," // unknown nested object
        + "  \"completion_tokens_details\":{\"reasoning_tokens\":0}" // unknown nested object
        + "}}";
  }

  @Test
  void unknown_response_fields_parse_cleanly_known_fields_populated() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody(bodyWithUnknownFields()));

      try (ShashuClient client =
          ShashuClient.builder()
              .apiKey("sk-shashu-test")
              .baseUrl(server.url("/").toString())
              .build()) {

        ChatCompletionResponse resp =
            assertDoesNotThrow(
                () ->
                    client
                        .chat()
                        .completions()
                        .create(
                            ChatCompletionRequest.builder()
                                .model("gpt-4o-mini")
                                .addMessage(ChatMessage.user("hi"))
                                .build()));

        // Known fields still populated despite the unknown extras.
        assertNotNull(resp);
        assertEquals("gpt-4o-mini-2024-07-18", resp.model());
        assertEquals(1, resp.choices().size());
        assertEquals("hi back", resp.choices().get(0).message().content());
        assertEquals("stop", resp.choices().get(0).finishReason());
        assertNotNull(resp.usage());
        assertEquals(7, resp.usage().totalTokens());
      }
    } finally {
      server.shutdown();
    }
  }
}
