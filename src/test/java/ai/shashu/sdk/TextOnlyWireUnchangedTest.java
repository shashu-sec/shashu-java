package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.chat.FunctionCall;
import ai.shashu.sdk.chat.ToolCall;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * Text-only messages keep the 1.2.0 wire bytes. The golden file was captured from the 1.2.0 sources
 * before messages learned to carry parts.
 */
class TextOnlyWireUnchangedTest {

  private static final String TEXT = "Résumé — a+b <x> & 'q' \"d\" / \\ 😀\n\tend \u001f";

  @Test
  void text_only_request_is_byte_identical_to_1_2_0() throws Exception {
    byte[] expected;
    try (InputStream in = getClass().getResourceAsStream("/golden/text_only.java-1.2.0.json")) {
      expected = in.readAllBytes();
    }
    ChatCompletionRequest request =
        ChatCompletionRequest.builder()
            .model("gpt-4o-mini")
            .addMessage(ChatMessage.system("sys"))
            .addMessage(ChatMessage.user(TEXT))
            .addMessage(
                ChatMessage.assistant(
                    null,
                    List.of(new ToolCall("c1", "function", new FunctionCall("f", "{\"a\":1}")))))
            .addMessage(ChatMessage.tool("c1", "r", "f"))
            .build();
    assertArrayEquals(expected, send(request));
  }

  @Test
  void a_literal_null_still_compiles_against_the_string_forms() {
    // Source compatibility: no new overload may make these calls ambiguous.
    assertNull(ChatMessage.user(null).content());
    assertNull(new ChatMessage("assistant", null).content());
    assertNull(ChatMessage.assistant(null).content());
    assertEquals("hi", ChatMessage.user("hi").content());
  }

  private static byte[] send(ChatCompletionRequest request)
      throws IOException, InterruptedException {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody("{\"id\":\"x\",\"choices\":[]}"));
      server.start();
      try (ShashuClient client =
          ShashuClient.builder()
              .apiKey("shashu_sk_golden")
              .baseUrl(server.url("/").toString())
              .build()) {
        client.chat().completions().create(request);
      }
      return server.takeRequest().getBody().readByteArray();
    }
  }
}
