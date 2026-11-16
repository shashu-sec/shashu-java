package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.shashu.sdk.chat.ChatCompletionChunk;
import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatCompletionResponse;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.internal.ChatCompletionChunkStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Live allowed SSE (and the same body buffered) emits {@code "created": null}. Captured verbatim
 * from the live gateway ({@code docs/contracts/PARKED-SDK-WORK.md} §3.1). A primitive {@code long}
 * on {@link ChatCompletionChunk} / {@link ChatCompletionResponse} is stricter than that frame:
 * Jackson does not throw (FAIL_ON_NULL_FOR_PRIMITIVES is off) — it coerces the null to {@code 0}.
 * Red-first: {@code expected: <null> but was: <0>} on both the stream and the buffered body.
 *
 * <p>Field sweep: 32 JSON-bound fields on the chat response types ({@code ChatCompletionChunk} 5,
 * its {@code Choice} 3, {@code Delta} 2, {@code ChatCompletionResponse} 6, its {@code Choice} 3,
 * {@code Usage} 3, {@code ChatMessage} 5, {@code ToolCall} 3, {@code FunctionCall} 2) probed
 * against live-shaped frames (this SSE, the same body buffered, goldens {@code chat_allowed_basic}
 * / {@code chat_blocked_content_filter} / {@code chat_tool_call} / {@code chat_usage_detail}). 2
 * changed (the two {@code created} fields).
 */
class CreatedNullCrashTest {

  /** Live allowed stream — created is JSON null on every chunk. */
  static final String LIVE_ALLOWED_SSE =
      "data: {\"id\":\"chatcmpl-fab1e5c0ffee\",\"object\":\"chat.completion.chunk\",\"created\":null,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n\n"
          + "data: {\"id\":\"chatcmpl-fab1e5c0ffee\",\"object\":\"chat.completion.chunk\",\"created\":null,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"The capital of France is Paris.\"},\"finish_reason\":null}]}\n\n"
          + "data: {\"id\":\"chatcmpl-fab1e5c0ffee\",\"object\":\"chat.completion.chunk\",\"created\":null,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
          + "data: [DONE]\n\n";

  /** Same live body, buffered. */
  static final String LIVE_ALLOWED_BUFFERED =
      "{\"id\":\"chatcmpl-fab1e5c0ffee\",\"object\":\"chat.completion\","
          + "\"created\":null,\"model\":\"gpt-4o\","
          + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
          + "\"content\":\"The capital of France is Paris.\"},"
          + "\"finish_reason\":\"stop\"}]}";

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void stream_allowed_reply_with_created_null_does_not_throw() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(LIVE_ALLOWED_SSE));

    try (ShashuClient client = client()) {
      List<ChatCompletionChunk> collected = new ArrayList<>();
      assertDoesNotThrow(
          () -> {
            try (ChatCompletionChunkStream stream =
                client
                    .chat()
                    .completions()
                    .createStream(
                        ChatCompletionRequest.builder()
                            .model("gpt-4o")
                            .addMessage(ChatMessage.user("hi"))
                            .stream(true)
                            .build())) {
              for (ChatCompletionChunk chunk : stream) {
                collected.add(chunk);
              }
            }
          });
      assertEquals(3, collected.size());
      assertNull(collected.get(0).created());
      assertEquals(
          "The capital of France is Paris.", collected.get(1).choices().get(0).delta().content());
    }
  }

  @Test
  void create_allowed_reply_with_created_null_does_not_throw() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(LIVE_ALLOWED_BUFFERED));

    try (ShashuClient client = client()) {
      ChatCompletionResponse resp =
          assertDoesNotThrow(
              () ->
                  client
                      .chat()
                      .completions()
                      .create(
                          ChatCompletionRequest.builder()
                              .model("gpt-4o")
                              .addMessage(ChatMessage.user("hi"))
                              .build()));
      assertNull(resp.created());
      assertEquals("The capital of France is Paris.", resp.choices().get(0).message().content());
      assertEquals("stop", resp.choices().get(0).finishReason());
    }
  }

  private ShashuClient client() {
    return ShashuClient.builder()
        .apiKey("sk-trustgate-test")
        .baseUrl(server.url("/").toString())
        .build();
  }
}
