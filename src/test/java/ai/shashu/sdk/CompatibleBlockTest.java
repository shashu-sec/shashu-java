package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatCompletionResponse;
import ai.shashu.sdk.chat.ChatMessage;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The 200-encoded block. Since 0.3.0-alpha this SDK declares {@code shashu-version: 2026-09-01}, so
 * a block arrives as a 400 by default; the 200 shape is still reachable when a caller opts out
 * ({@code contractVersion(null)}) on an account served the legacy contract, or on an account whose
 * operator set the {@code compatible} encoding. It fires, and it must throw. Body is {@code
 * tests/golden_wire/chat_blocked_content_filter.golden.json}.
 */
class CompatibleBlockTest {

  private static final String BLOCKED_200 =
      "{\"id\":\"chatcmpl-deadbeefcafe4321aabbccdd\",\"object\":\"chat.completion\","
          + "\"created\":1750000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
          + "\"message\":{\"role\":\"assistant\",\"content\":\"I cannot fulfill this request "
          + "because it violates the organization's security policy.\"},"
          + "\"finish_reason\":\"content_filter\"}],"
          + "\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}";

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
  void create_200_content_filter_throws() throws Exception {
    enqueue(BLOCKED_200);
    try (ShashuClient client = client()) {
      ShashuContentFilterException ex =
          assertThrows(
              ShashuContentFilterException.class, () -> client.chat().completions().create(req()));
      assertEquals(200, ex.statusCode());
      assertEquals("content_filter", ex.errorType());
      assertTrue(ex.getMessage().contains("security policy"));
    }
  }

  @Test
  void create_200_stop_does_not_throw() throws Exception {
    enqueue(
        "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
            + "\"finish_reason\":\"stop\"}]}");
    try (ShashuClient client = client()) {
      ChatCompletionResponse resp = client.chat().completions().create(req());
      assertEquals("stop", resp.choices().get(0).finishReason());
      assertEquals("ok", resp.choices().get(0).message().content());
    }
  }

  @Test
  void sdk_declares_the_contract_version_and_no_block_encoding_by_default() throws Exception {
    enqueue(
        "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
            + "\"finish_reason\":\"stop\"}]}");
    try (ShashuClient client = client()) {
      client.chat().completions().create(req());
    }
    var seen = server.takeRequest();
    assertEquals("2026-09-01", seen.getHeader("shashu-version"));
    assertEquals(null, seen.getHeader("shashu-block-encoding"));
  }

  @Test
  void opted_out_sdk_sends_neither_so_legacy_200_is_reachable() throws Exception {
    enqueue(BLOCKED_200);
    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-shashu-test")
            .baseUrl(server.url("/").toString())
            .contractVersion(null)
            .build()) {
      assertThrows(
          ShashuContentFilterException.class, () -> client.chat().completions().create(req()));
    }
    var seen = server.takeRequest();
    assertEquals(null, seen.getHeader("shashu-version"));
    assertEquals(null, seen.getHeader("shashu-block-encoding"));
  }

  @Test
  void stream_200_content_filter_throws() throws Exception {
    String sse =
        "data: {\"id\":\"chatcmpl-deadbeefcafe4321aabbccdd\",\"object\":\"chat.completion.chunk\",\"created\":1750000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"chatcmpl-deadbeefcafe4321aabbccdd\",\"object\":\"chat.completion.chunk\",\"created\":1750000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"I cannot fulfill this request because it \"},\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"chatcmpl-deadbeefcafe4321aabbccdd\",\"object\":\"chat.completion.chunk\",\"created\":1750000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"violates the organization's security policy.\"},\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"chatcmpl-deadbeefcafe4321aabbccdd\",\"object\":\"chat.completion.chunk\",\"created\":1750000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\n"
            + "data: [DONE]\n\n";
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse));
    try (ShashuClient client = client()) {
      ShashuContentFilterException ex =
          assertThrows(
              ShashuContentFilterException.class,
              () -> {
                try (var stream = client.chat().completions().createStream(req())) {
                  for (var ignored : stream) {
                    /* drain */
                  }
                }
              });
      assertEquals(200, ex.statusCode());
      assertEquals("content_filter", ex.errorType());
    }
  }

  private ShashuClient client() {
    return ShashuClient.builder()
        .apiKey("sk-shashu-test")
        .baseUrl(server.url("/").toString())
        .build();
  }

  private static ChatCompletionRequest req() {
    return ChatCompletionRequest.builder()
        .model("gpt-4o")
        .addMessage(ChatMessage.user("hi"))
        .build();
  }

  private void enqueue(String body) {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body));
  }
}
