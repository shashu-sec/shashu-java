package ai.shashu.sdk.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * An in-repo mock gateway: records every request verbatim and returns a canned completion. <b>No
 * socket is ever opened</b> &mdash; it is the last OkHttp interceptor in the chain and answers
 * instead of calling {@code chain.proceed}, so the harness measures the exact bytes the SDK was
 * about to put on the wire without any network in the picture.
 *
 * <p>Requests are captured as fully-materialised bytes, so a test can assert on the exact wire body
 * &mdash; including asserting that two runs are byte-identical, which is how the zero-cost claim is
 * proved rather than described.
 *
 * <p><b>Every request is stamped.</b> A real gateway records whether a request declared its
 * retrieval; this one does the same, into {@link RecordedRequest#declaration()}. That matters more
 * here than in any other SDK: in Java a lost declaration produces a request that looks exactly like
 * one from an app that never declared anything, so the harness asserts on a positive {@code
 * not_declared} row rather than on an absence.
 *
 * <p>Thread-safe: the concurrency case drives eight tasks through one instance at once.
 */
public final class MockGateway implements Interceptor {

  /**
   * The gateway address every test uses. RFC 6761 reserves {@code .invalid} and guarantees it never
   * resolves, so a test that reached the network by mistake fails on DNS rather than quietly
   * succeeding against something real.
   */
  public static final String BASE_URL = "https://gateway.test.invalid/";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ConcurrentLinkedQueue<RecordedRequest> requests = new ConcurrentLinkedQueue<>();

  /** Every request that reached the gateway, in arrival order. */
  public List<RecordedRequest> requests() {
    return Collections.unmodifiableList(new ArrayList<>(requests));
  }

  /** The single request, asserting there was exactly one. */
  public RecordedRequest single() {
    List<RecordedRequest> all = requests();
    if (all.size() != 1) {
      throw new IllegalStateException("expected exactly 1 request, recorded " + all.size());
    }
    return all.get(0);
  }

  @Override
  public Response intercept(Chain chain) throws IOException {
    Request request = chain.request();

    byte[] body = new byte[0];
    RequestBody sent = request.body();
    if (sent != null) {
      Buffer buffer = new Buffer();
      sent.writeTo(buffer);
      body = buffer.readByteArray();
    }
    requests.add(
        new RecordedRequest(
            request.url().toString(),
            body,
            request.header("Content-Length"),
            sent != null && sent.contentType() != null ? sent.contentType().toString() : null));

    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(ResponseBody.create(CANNED_COMPLETION, MediaType.get("application/json")))
        .build();
  }

  private static final String CANNED_COMPLETION =
      """
        {
          "id": "chatcmpl-mock",
          "object": "chat.completion",
          "created": 0,
          "model": "gpt-4o-mini",
          "choices": [
            {
              "index": 0,
              "message": { "role": "assistant", "content": "ok" },
              "finish_reason": "stop"
            }
          ],
          "usage": { "prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2 }
        }
        """;

  /** One request as the gateway saw it. */
  public static final class RecordedRequest {

    private final String url;
    private final byte[] body;
    private final String contentLengthHeader;
    private final String contentType;

    RecordedRequest(String url, byte[] body, String contentLengthHeader, String contentType) {
      this.url = url;
      this.body = body;
      this.contentLengthHeader = contentLengthHeader;
      this.contentType = contentType;
    }

    public String url() {
      return url;
    }

    public byte[] body() {
      return body.clone();
    }

    public String contentLengthHeader() {
      return contentLengthHeader;
    }

    public String contentType() {
      return contentType;
    }

    public String bodyText() {
      return new String(body, StandardCharsets.UTF_8);
    }

    /** The body parsed as a JSON object, or null if it was not one. */
    public JsonNode json() {
      if (body.length == 0) {
        return null;
      }
      try {
        JsonNode parsed = MAPPER.readTree(body);
        return parsed != null && parsed.isObject() ? parsed : null;
      } catch (IOException e) {
        return null;
      }
    }

    /** Whether the request carried a {@code trustgate_metadata} key at all. */
    public boolean hasShashuMetadata() {
      JsonNode json = json();
      return json != null && json.hasNonNull("trustgate_metadata");
    }

    /** The {@code trustgate_metadata.rag_context} block, or null if the request carried none. */
    public JsonNode ragContext() {
      JsonNode json = json();
      if (json == null) {
        return null;
      }
      JsonNode metadata = json.get("trustgate_metadata");
      return metadata != null && metadata.isObject() ? metadata.get("rag_context") : null;
    }

    /** The {@code trustgate_metadata.memory_context} block, or null. */
    public JsonNode memoryContext() {
      JsonNode json = json();
      if (json == null) {
        return null;
      }
      JsonNode metadata = json.get("trustgate_metadata");
      return metadata != null && metadata.isObject() ? metadata.get("memory_context") : null;
    }

    /**
     * What the gateway records this request's retrieval posture as: {@code "declared"} or {@code
     * "not_declared"}. The stamp exists so a test can assert the undeclared case as a value rather
     * than as the absence of one.
     */
    public String declaration() {
      JsonNode context = ragContext();
      return context != null && context.isObject() ? "declared" : "not_declared";
    }
  }
}
