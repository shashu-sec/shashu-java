package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.*;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.chat.FunctionCall;
import ai.shashu.sdk.chat.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * SDK-3 (Java half): typed tool-call fields on {@link ChatMessage}. Serialisation is asserted
 * through the public client + MockWebServer — the real wire path through the SDK's central
 * ObjectMapper — so the tests prove what the gateway's {@code ingress-tool} scanner actually
 * receives. The #1 review gate is back-compat: messages without tool fields must serialise
 * byte-identically to the pre-SDK-3 wire shape.
 */
class ToolCallTest {

  private MockWebServer server;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  private ShashuClient newClient() {
    return ShashuClient.builder()
        .apiKey("sk-trustgate-test")
        .baseUrl(server.url("/").toString())
        .build();
  }

  private void enqueueOk() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"fake-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"));
  }

  /** Serialise a request through the real client and return the raw body. */
  private String send(ChatCompletionRequest request) throws Exception {
    enqueueOk();
    try (ShashuClient client = newClient()) {
      client.chat().completions().create(request);
    }
    RecordedRequest req = server.takeRequest();
    return req.getBody().readUtf8();
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  @Test
  void messages_without_tool_fields_serialise_byte_identical() throws Exception {
    // #1 review gate: factory + 2-arg-constructor messages must not
    // grow tool_calls / tool_call_id / name keys. Golden-string
    // asserted against the raw body, not just a parsed tree.
    String raw =
        send(
            ChatCompletionRequest.builder()
                .model("gpt-4o")
                .addMessage(ChatMessage.user("hi"))
                .addMessage(new ChatMessage("assistant", "ok"))
                .build());

    assertTrue(
        raw.contains("{\"role\":\"user\",\"content\":\"hi\"}"),
        "user message must serialise exactly as before, got: " + raw);
    assertTrue(
        raw.contains("{\"role\":\"assistant\",\"content\":\"ok\"}"),
        "2-arg constructor message must serialise exactly as before, got: " + raw);

    JsonNode messages = mapper.readTree(raw).path("messages");
    assertEquals(List.of("role", "content"), fieldNames(messages.get(0)));
    assertEquals(List.of("role", "content"), fieldNames(messages.get(1)));
  }

  @Test
  void assistant_tool_calls_serialise_in_openai_wire_shape() throws Exception {
    String raw =
        send(
            ChatCompletionRequest.builder()
                .model("gpt-4o")
                .addMessage(ChatMessage.user("weather in Paris?"))
                .addMessage(
                    ChatMessage.assistant(
                        null,
                        List.of(
                            ToolCall.function(
                                "call_abc123", "get_weather", "{\"city\":\"Paris\"}"))))
                .build());

    JsonNode toolCalls = mapper.readTree(raw).path("messages").get(1).path("tool_calls");
    assertEquals(1, toolCalls.size());
    assertEquals("call_abc123", toolCalls.get(0).path("id").asText());
    assertEquals("function", toolCalls.get(0).path("type").asText());
    assertEquals("get_weather", toolCalls.get(0).path("function").path("name").asText());
    assertEquals(
        "{\"city\":\"Paris\"}", toolCalls.get(0).path("function").path("arguments").asText());
  }

  @Test
  void tool_result_message_serialises_role_tool_with_call_id_and_name() throws Exception {
    String raw =
        send(
            ChatCompletionRequest.builder()
                .model("gpt-4o")
                .addMessage(ChatMessage.tool("call_abc123", "{\"temp_c\":18}", "get_weather"))
                .addMessage(ChatMessage.tool("call_def456", "plain result"))
                .build());

    JsonNode messages = mapper.readTree(raw).path("messages");
    JsonNode withName = messages.get(0);
    assertEquals("tool", withName.path("role").asText());
    assertEquals("{\"temp_c\":18}", withName.path("content").asText());
    assertEquals("call_abc123", withName.path("tool_call_id").asText());
    assertEquals("get_weather", withName.path("name").asText());

    // The no-name overload must not emit a name key (NON_NULL include).
    JsonNode withoutName = messages.get(1);
    assertEquals(List.of("role", "content", "tool_call_id"), fieldNames(withoutName));
    assertEquals("call_def456", withoutName.path("tool_call_id").asText());
  }

  @Test
  void arguments_stays_a_json_encoded_string_through_serialisation() throws Exception {
    // The gateway json.loads()es function.arguments — it must arrive
    // as a STRING, never re-serialised into a nested JSON object.
    String raw =
        send(
            ChatCompletionRequest.builder()
                .model("gpt-4o")
                .addMessage(
                    ChatMessage.assistant(
                        "checking",
                        List.of(ToolCall.function("call_1", "lookup", "{\"q\":\"rate\",\"n\":3}"))))
                .build());

    JsonNode arguments =
        mapper
            .readTree(raw)
            .path("messages")
            .get(0)
            .path("tool_calls")
            .get(0)
            .path("function")
            .path("arguments");
    assertTrue(
        arguments.isTextual(), "arguments must be a JSON string, got: " + arguments.getNodeType());
    JsonNode decoded = mapper.readTree(arguments.asText());
    assertEquals("rate", decoded.path("q").asText());
    assertEquals(3, decoded.path("n").asInt());
  }

  @Test
  void gateway_extract_tool_calls_shape_conformance() throws Exception {
    // Walk the serialised tree the way the gateway's _extract_tool_calls
    // does: message-level "tool_calls", each entry function/name +
    // function/arguments.
    String raw =
        send(
            ChatCompletionRequest.builder()
                .model("gpt-4o")
                .addMessage(
                    ChatMessage.assistant(
                        null,
                        List.of(
                            ToolCall.function("call_1", "read_file", "{\"path\":\"/etc/passwd\"}"),
                            ToolCall.function("call_2", "send_email", "{\"to\":\"a@b.co\"}"))))
                .build());

    JsonNode msg = mapper.readTree(raw).path("messages").get(0);
    JsonNode toolCalls = msg.get("tool_calls"); // msg.get("tool_calls") — the gateway read
    assertNotNull(toolCalls);
    assertEquals(2, toolCalls.size());
    assertEquals("read_file", toolCalls.get(0).path("function").path("name").asText());
    assertEquals(
        "{\"path\":\"/etc/passwd\"}", toolCalls.get(0).path("function").path("arguments").asText());
    assertEquals("send_email", toolCalls.get(1).path("function").path("name").asText());
    assertEquals(
        "{\"to\":\"a@b.co\"}", toolCalls.get(1).path("function").path("arguments").asText());
  }

  @Test
  void tool_calls_deserialise_from_assistant_response() throws Exception {
    // Pre-SDK-3 the central mapper silently DROPPED tool_calls on
    // responses (unknown property). Typed callers can now read them.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"fake-2\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,"
                    + "\"tool_calls\":[{\"id\":\"call_xyz\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}]},"
                    + "\"finish_reason\":\"tool_calls\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"));

    try (ShashuClient client = newClient()) {
      ChatMessage msg =
          client
              .chat()
              .completions()
              .create(
                  ChatCompletionRequest.builder()
                      .model("gpt-4o")
                      .addMessage(ChatMessage.user("weather in Paris?"))
                      .build())
              .choices()
              .get(0)
              .message();

      assertEquals("assistant", msg.role());
      assertNull(msg.content());
      assertEquals(1, msg.toolCalls().size());
      assertEquals("call_xyz", msg.toolCalls().get(0).id());
      assertEquals("function", msg.toolCalls().get(0).type());
      assertEquals("get_weather", msg.toolCalls().get(0).function().name());
      assertEquals("{\"city\":\"Paris\"}", msg.toolCalls().get(0).function().arguments());
    }
  }

  @Test
  void equals_hashCode_toString_cover_tool_fields() {
    ChatMessage a = ChatMessage.tool("call_1", "result", "lookup");
    ChatMessage b = ChatMessage.tool("call_1", "result", "lookup");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, ChatMessage.tool("call_2", "result", "lookup"));

    ChatMessage withCalls =
        ChatMessage.assistant(
            "c", List.of(new ToolCall("call_1", "function", new FunctionCall("f", "{}"))));
    assertEquals(
        withCalls, ChatMessage.assistant("c", List.of(ToolCall.function("call_1", "f", "{}"))));
    assertNotEquals(withCalls, ChatMessage.assistant("c"));

    // Plain messages keep the pre-SDK-3 toString; tool fields appear when set.
    assertEquals("ChatMessage{role=user, content=hi}", ChatMessage.user("hi").toString());
    assertTrue(a.toString().contains("toolCallId=call_1"));
    assertTrue(withCalls.toString().contains("ToolCall{id=call_1"));
  }
}
