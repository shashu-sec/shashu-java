package ai.shashu.sdk;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatCompletionResponse;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.chat.ContentPart;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Multi-part messages. The {@code *.python-node.json} goldens are the exact request bodies the
 * Python (shashu-ai 1.2.0) and Node (@shashu-ai/sdk 1.2.0) SDKs sent for the same input; the two
 * were byte-identical.
 */
class ContentPartsTest {

  private static final String TEXT = "Résumé — a+b <x> & 'q' \"d\" / \\ 😀\n\tend";
  private static final byte[] PDF = latin1("%PDF-1.4\nûï¾ÿ?>~ golden");
  private static final byte[] PNG = latin1("\u0089PNG\r\n\u001a\n\u0000ûÿþ");

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
  void text_and_file_match_python_and_node_bytes() throws Exception {
    server.enqueue(ok());
    send(textAndFile());
    assertEquals(golden("text_file"), sent());
  }

  @Test
  void image_data_uri_matches_python_and_node_bytes() throws Exception {
    server.enqueue(ok());
    send(
        request(
            ChatMessage.user(ContentPart.text("look"), ContentPart.imageData(PNG, "image/png"))));
    assertEquals(golden("text_image_data"), sent());
  }

  @Test
  void image_https_url_matches_python_and_node_bytes() throws Exception {
    server.enqueue(ok());
    send(
        ChatCompletionRequest.builder()
            .model("gpt-4o-mini")
            .addMessage(ChatMessage.system("sys"))
            .addMessage(
                ChatMessage.withParts(
                    "user",
                    List.of(
                        ContentPart.text("look"),
                        ContentPart.imageUrl("https://example.com/a+b.png?x=1&y=%20"))))
            .build());
    assertEquals(golden("text_image_url"), sent());
  }

  @Test
  void busy_retry_resends_identical_parts_with_the_declaration_joined() throws Exception {
    server.enqueue(busy());
    server.enqueue(ok());
    Retrieval.declareRetrieval(List.of("g6 declared document"), "g6_corpus", "g6 query");
    send(textAndFile());
    assertEquals(2, server.getRequestCount());
    String first = sent();
    assertEquals(first, sent());
    assertTrue(first.contains("g6_corpus"));
    assertTrue(first.contains("g6 declared document"));
    assertTrue(first.contains("data:application/pdf;base64," + b64(PDF)));
  }

  @Test
  void pass_expired_refresh_keeps_the_same_parts() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"error\":{\"message\":\"This pass has expired. Fetch a new one.\","
                    + "\"type\":\"invalid_request_error\",\"code\":\"pass_expired\"}}"));
    server.enqueue(ok());
    String first = pass(300);
    String second = pass(600);
    AtomicInteger calls = new AtomicInteger();
    try (ShashuClient client =
        ShashuClient.builder()
            .baseUrl(server.url("/").toString())
            .tokenProvider(() -> calls.getAndIncrement() == 0 ? first : second)
            .maxRetries(0)
            .build()) {
      client.chat().completions().create(textAndFile());
    }
    assertEquals(2, server.getRequestCount());
    RecordedRequest a = server.takeRequest();
    RecordedRequest b = server.takeRequest();
    assertNotEquals(a.getHeader("Authorization"), b.getHeader("Authorization"));
    assertEquals(golden("text_file"), a.getBody().readUtf8());
    assertEquals(golden("text_file"), b.getBody().readUtf8());
  }

  @Test
  void a_reply_with_array_content_is_read_as_parts() throws Exception {
    server.enqueue(
        reply(
            "[{\"type\":\"text\",\"text\":\"hello\"},{\"type\":\"file\",\"file\":{\"file_id\":\"f\"}}]"));
    ChatMessage message = send(textAndFile()).choices().get(0).message();
    assertNull(message.content());
    assertEquals(2, message.contentParts().size());
    assertEquals("hello", message.contentParts().get(0).text());
    assertEquals("file", message.contentParts().get(1).type());
    assertNull(message.contentParts().get(1).text());
  }

  @Test
  void a_text_reply_is_read_exactly_as_before() throws Exception {
    server.enqueue(ok());
    ChatMessage message = send(textAndFile()).choices().get(0).message();
    assertEquals("ok", message.content());
    assertNull(message.contentParts());
  }

  @Test
  void accessors_equality_and_to_string() {
    ChatMessage message =
        ChatMessage.user(ContentPart.text("t"), ContentPart.file(PDF, "a.pdf", "application/pdf"));
    assertNull(message.content());
    assertEquals(2, message.contentParts().size());
    assertEquals(
        message,
        ChatMessage.user(ContentPart.text("t"), ContentPart.file(PDF, "a.pdf", "application/pdf")));
    assertEquals(
        message.hashCode(),
        ChatMessage.user(ContentPart.text("t"), ContentPart.file(PDF, "a.pdf", "application/pdf"))
            .hashCode());
    assertNotEquals(message, ChatMessage.user("t"));
    // A debug string must never carry the attached bytes.
    assertFalse(message.toString().contains(b64(PDF)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> message.contentParts().add(ContentPart.text("x")));
  }

  @Test
  void the_part_list_is_copied() {
    List<ContentPart> parts = new ArrayList<>(List.of(ContentPart.text("a")));
    ChatMessage message = ChatMessage.withParts("user", parts);
    parts.add(ContentPart.text("b"));
    assertEquals(1, message.contentParts().size());
  }

  @Test
  void control_characters_and_lone_surrogates_are_escaped_like_json_stringify() throws Exception {
    server.enqueue(ok());
    send(request(ChatMessage.user(ContentPart.text("\u0001\u001f\b\f\r\ud800x\u2028"))));
    assertTrue(sent().contains("\"text\":\"\\u0001\\u001f\\b\\f\\r\\ud800x\u2028\""));
  }

  @Test
  void factories_refuse_missing_arguments() {
    assertThrows(NullPointerException.class, () -> ContentPart.text(null));
    assertThrows(NullPointerException.class, () -> ContentPart.imageUrl(null));
    assertThrows(NullPointerException.class, () -> ContentPart.imageData(null, "image/png"));
    assertThrows(IllegalArgumentException.class, () -> ContentPart.imageData(PNG, " "));
    assertThrows(
        IllegalArgumentException.class, () -> ContentPart.file(PDF, "", "application/pdf"));
    assertThrows(IllegalArgumentException.class, () -> ContentPart.file(PDF, "a.pdf", ""));
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.withParts("user", List.of()));
    assertThrows(
        NullPointerException.class,
        () -> ChatMessage.withParts("user", Arrays.asList((ContentPart) null)));
  }

  private static ChatCompletionRequest textAndFile() {
    return request(
        ChatMessage.user(
            ContentPart.text(TEXT), ContentPart.file(PDF, "q3 réport+.pdf", "application/pdf")));
  }

  private static ChatCompletionRequest request(ChatMessage message) {
    return ChatCompletionRequest.builder().model("gpt-4o-mini").addMessage(message).build();
  }

  private ChatCompletionResponse send(ChatCompletionRequest request) {
    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("shashu_sk_golden")
            .baseUrl(server.url("/").toString())
            .build()) {
      return client.chat().completions().create(request);
    }
  }

  private String sent() throws InterruptedException {
    return server.takeRequest().getBody().readUtf8();
  }

  private String golden(String name) throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/golden/" + name + ".python-node.json")) {
      return new String(in.readAllBytes(), UTF_8);
    }
  }

  private static MockResponse ok() {
    return reply("\"ok\"");
  }

  private static MockResponse reply(String contentJson) {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                + contentJson
                + "},\"finish_reason\":\"stop\"}]}");
  }

  private static MockResponse busy() {
    return new MockResponse()
        .setResponseCode(503)
        .setHeader("Retry-After", "0")
        .setHeader("x-should-retry", "true")
        .setBody("{\"error\":{\"message\":\"busy\",\"code\":\"gateway_busy\"}}");
  }

  private static String pass(long lifetimeSeconds) {
    long now = System.currentTimeMillis() / 1000L;
    Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
    return url.encodeToString("{\"alg\":\"none\"}".getBytes(UTF_8))
        + "."
        + url.encodeToString(
            ("{\"iat\":" + now + ",\"exp\":" + (now + lifetimeSeconds) + "}").getBytes(UTF_8))
        + ".sig";
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static byte[] latin1(String s) {
    return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  }
}
