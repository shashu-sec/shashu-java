package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.*;

import ai.shashu.sdk.chat.ChatCompletionChunk;
import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.internal.ChatCompletionChunkStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Hermetic tests for streaming chat-completions. MockWebServer emits SSE frames; the SDK's parser
 * is verified end-to-end against the same shape OpenAI / the gateway emit in production.
 */
class StreamingTest {

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
  void stream_yields_each_data_chunk_then_stops_at_DONE() throws Exception {
    String sse =
        "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse));

    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .build()) {

      List<ChatCompletionChunk> collected = new ArrayList<>();
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

      assertEquals(3, collected.size());
      assertEquals("assistant", collected.get(0).choices().get(0).delta().role());
      assertEquals("Hel", collected.get(1).choices().get(0).delta().content());
      assertEquals("lo", collected.get(2).choices().get(0).delta().content());
      assertEquals("stop", collected.get(2).choices().get(0).finishReason());
    }
  }

  @Test
  void stream_force_sets_stream_true_even_if_caller_omitted_it() throws Exception {
    // Caller passes Stream=null/false; the SDK must override to true so
    // the gateway opens an SSE channel.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"id\":\"c1\",\"object\":\"chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[]}\n\n"
                    + "data: [DONE]\n\n"));

    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .build()) {

      try (ChatCompletionChunkStream stream =
          client
              .chat()
              .completions()
              .createStream(
                  ChatCompletionRequest.builder()
                      .model("gpt-4o")
                      .addMessage(ChatMessage.user("hi"))
                      // stream omitted → null → SDK forces to true
                      .build())) {
        for (ChatCompletionChunk ignored : stream) {
          /* drain */
        }
      }
    }

    RecordedRequest req = server.takeRequest();
    assertTrue(req.getBody().readUtf8().contains("\"stream\":true"));
    assertEquals("text/event-stream", req.getHeader("Accept"));
  }

  @Test
  void stream_5xx_throws_ShashuException_with_status_and_body() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(502)
            .setBody("{\"error\":{\"message\":\"upstream provider unavailable\"}}"));

    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .build()) {

      ShashuException ex =
          assertThrows(
              ShashuException.class,
              () ->
                  client
                      .chat()
                      .completions()
                      .createStream(
                          ChatCompletionRequest.builder()
                              .model("gpt-4o")
                              .addMessage(ChatMessage.user("hi"))
                              .stream(true)
                              .build()));

      assertEquals(502, ex.statusCode());
      assertTrue(ex.responseBody().contains("upstream provider unavailable"));
    }
  }

  @Test
  void stream_handles_data_with_or_without_space_after_colon() throws Exception {
    // SSE spec: separator is `:` followed by an OPTIONAL single space.
    String sse =
        "data: {\"id\":\"c1\",\"object\":\"chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"with-space\"}}]}\n\n"
            + "data:{\"id\":\"c2\",\"object\":\"chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"no-space\"}}]}\n\n"
            + "data: [DONE]\n\n";

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse));

    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .build()) {

      List<String> contents = new ArrayList<>();
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
          if (!chunk.choices().isEmpty() && chunk.choices().get(0).delta() != null) {
            String c = chunk.choices().get(0).delta().content();
            if (c != null) contents.add(c);
          }
        }
      }

      assertEquals(List.of("with-space", "no-space"), contents);
    }
  }

  @Test
  void create_with_stream_true_throws_routing_caller_to_streaming_method() throws Exception {
    // The synchronous method must guard against accidental streaming
    // use — silently missing SSE chunks would be hard to debug.
    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .build()) {

      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class,
              () ->
                  client
                      .chat()
                      .completions()
                      .create(
                          ChatCompletionRequest.builder()
                              .model("gpt-4o")
                              .addMessage(ChatMessage.user("hi"))
                              .stream(true)
                              .build()));
      assertTrue(ex.getMessage().contains("createStream"));
    }
  }
}
