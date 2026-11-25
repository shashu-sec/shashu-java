package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.*;

import ai.shashu.sdk.chat.ChatCompletionRequest;
import ai.shashu.sdk.chat.ChatMessage;
import ai.shashu.sdk.context.WorkflowContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WorkflowContext}.
 *
 * <p><strong>Note on env-var coverage:</strong> Java's {@code System.getenv()} is read-only at
 * runtime by design — there's no supported way to mutate process env from inside a test without a
 * 3rd-party dependency (e.g. system-stubs). To avoid adding that dep, we test:
 *
 * <ol>
 *   <li>The local_script default path (no env-mocking required since most CI runners have
 *       N8N/GITHUB/GITLAB set; we skip that assertion when so).
 *   <li>Source-tool blob shape (no env dependency).
 *   <li>Caller-supplied workflowStep override (no env dependency).
 *   <li>Wire-level: headers actually land on outbound requests through the SDK, against a
 *       MockWebServer.
 * </ol>
 *
 * <p>The .NET test suite covers the n8n / github_actions / gitlab_ci detection branches against the
 * same algorithm; the Python SDK is the authoritative reference for env-detection behaviour.
 */
class WorkflowContextTest {

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

  @Test
  void source_is_local_script_when_no_managed_env_is_set() {
    // Skip when running inside CI that sets these env vars; we can't
    // unset them in-process without a 3rd-party lib.
    if (System.getenv("N8N_EXECUTION_ID") != null
        || "true".equals(System.getenv("GITHUB_ACTIONS"))
        || "true".equals(System.getenv("GITLAB_CI"))) {
      return;
    }
    assertEquals("local_script", WorkflowContext.detectSource());
  }

  @Test
  void caller_supplied_workflow_step_wins_when_provided() {
    Map<String, String> headers = WorkflowContext.build("Caller_Override", null);
    assertEquals("Caller_Override", headers.get("x-shashu-workflow-step"));
  }

  @Test
  void source_tool_blob_is_valid_json_with_sdk_identification() throws Exception {
    Map<String, String> headers = WorkflowContext.build();
    String blob = headers.get("x-shashu-source-tool");
    assertNotNull(blob, "source-tool header must always be emitted");
    JsonNode root = mapper.readTree(blob);
    assertEquals("trustgate-java", root.path("sdk").asText());
    assertEquals("java", root.path("language").asText());
    assertFalse(root.path("timestamp_utc").asText().isEmpty(), "timestamp_utc must be present");
  }

  @Test
  void source_tool_overrides_merge_into_blob() throws Exception {
    Map<String, String> headers =
        WorkflowContext.build(null, Map.of("step_name", "extract", "request_id", "req-42"));
    JsonNode root = mapper.readTree(headers.get("x-shashu-source-tool"));
    assertEquals("extract", root.path("step_name").asText());
    assertEquals("req-42", root.path("request_id").asText());
    // Default fields preserved:
    assertEquals("trustgate-java", root.path("sdk").asText());
  }

  @Test
  void runtime_metadata_headers_always_emitted() {
    Map<String, String> headers = WorkflowContext.build();
    assertNotNull(headers.get("x-trustlog-os"));
    assertNotNull(headers.get("x-trustlog-arch"));
    String runtime = headers.get("x-trustlog-runtime-version");
    assertNotNull(runtime);
    assertTrue(
        runtime.startsWith("java-"), "runtime-version should be prefixed with java-: " + runtime);
  }

  @Test
  void headers_applied_to_every_outbound_request_through_the_sdk() throws Exception {
    // End-to-end via MockWebServer.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"));

    try (ShashuClient client =
        ShashuClient.builder()
            .apiKey("sk-trustgate-test")
            .baseUrl(server.url("/").toString())
            .build()) {
      client
          .chat()
          .completions()
          .create(
              ChatCompletionRequest.builder()
                  .model("gpt-4o")
                  .addMessage(ChatMessage.user("hi"))
                  .build());
    }

    RecordedRequest req = server.takeRequest();
    // Mandatory headers (always emitted regardless of env).
    assertNotNull(req.getHeader("x-shashu-source"));
    assertNotNull(req.getHeader("x-shashu-trace-id"));
    assertNotNull(req.getHeader("x-shashu-source-tool"));
    assertNotNull(req.getHeader("x-trustlog-os"));
    assertNotNull(req.getHeader("x-trustlog-arch"));
    assertTrue(req.getHeader("x-trustlog-runtime-version").startsWith("java-"));

    // Source-tool is JSON identifying the calling SDK.
    JsonNode sourceTool = mapper.readTree(req.getHeader("x-shashu-source-tool"));
    assertEquals("trustgate-java", sourceTool.path("sdk").asText());
  }
}
