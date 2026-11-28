package ai.shashu.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;

/**
 * Joins the carried retrieval declaration onto the outgoing request. Visible across the {@code
 * ai.shashu.sdk} packages but not part of the public API.
 *
 * <p>Five properties it has to hold, in this order:
 *
 * <ol>
 *   <li><b>Zero cost when nothing was retrieved.</b> The first thing it does is {@link
 *       RetrievalBus#anythingPending()} &mdash; a ThreadLocal read and two emptiness checks. With
 *       nothing declared anywhere it returns <b>the same {@link Request} instance</b> before
 *       touching the request's headers or body at all, so the bytes on the wire are the bytes the
 *       unpatched interceptor produced &mdash; asserted byte-for-byte in the harness.
 *   <li><b>Explicit always wins.</b> A body already carrying {@code shashu_metadata.rag_context} is
 *       left exactly as the caller built it. The ambient declaration is consumed and discarded, not
 *       deferred: a declaration that lost this call must not turn up on the next one.
 *   <li><b>Snapshot and clear.</b> The bus is emptied the moment it is written to a request.
 *       Retrieval declared for request N never reaches request N+1.
 *   <li><b>Never corrupt a body it does not understand.</b> A non-JSON body, a non-object JSON body
 *       or a duplex/one-shot body is passed through untouched and does NOT consume the bus &mdash;
 *       the next real model call still carries the declaration, and a file upload does not eat it.
 *       A JSON body is joined whatever its size, as in the Python and Node SDKs: a chat request
 *       carrying a large file is still the call the declaration was made for.
 *   <li><b>A declaration that could not be attached is audible.</b> Java is the one language where
 *       the hand-off loses the declaration outright, so when this joiner attaches nothing while a
 *       declaration is sitting unconsumed on another thread, that is reported to the customer's own
 *       logs rather than left to look like a request nobody declared anything for.
 * </ol>
 *
 * <p>Sibling of the Python SDK's {@code shashu/_joiner.py}, the Node SDK's {@code src/_joiner.ts}
 * and the .NET SDK's {@code Internal/RetrievalJoiner.cs}.
 */
public final class RetrievalJoiner {

  private RetrievalJoiner() {}

  private static final Logger LOG = System.getLogger(RetrievalJoiner.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Return the request to send: either {@code request} itself, or a copy carrying {@code
   * shashu_metadata.rag_context}.
   *
   * <p>Never throws. A retrieval declaration must not be the thing that breaks a customer's
   * request.
   */
  public static Request join(Request request) {
    // The zero-cost guard, before anything is touched.
    if (!RetrievalBus.anythingPending()) {
      return request;
    }

    // Checked second, so the kill switch costs nothing on the common
    // path. A bus populated before the switch flipped is dropped, not
    // sent, and not warned about: the operator turned the SDK off.
    if (ShashuConfig.isDisabled()) {
      RetrievalBus.clearForRequest();
      return request;
    }

    if (!RetrievalBus.hasEventsToAttach()) {
      // Something was declared, but not anywhere this request can see.
      // This is the Java hand-off loss, and this is where it is made
      // audible. The body is not touched.
      RetrievalBus.reportLostDeclarations();
      return request;
    }

    try {
      return joinCore(request);
    } catch (IOException | RuntimeException e) {
      // The declaration is lost, the request goes out as the customer
      // built it. Nothing about the document text is logged.
      LOG.log(
          Level.WARNING,
          "[shashu.retrieval] declaration not attached: " + e.getClass().getSimpleName());
      return request;
    }
  }

  private static Request joinCore(Request request) throws IOException {
    RequestBody body = request.body();
    if (body == null || body.isDuplex() || body.isOneShot()) {
      return request;
    }
    if (!looksLikeJson(body.contentType())) {
      return request;
    }
    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    if (buffer.size() == 0L) {
      return request;
    }
    byte[] raw = buffer.readByteArray();

    JsonNode parsed;
    try {
      parsed = MAPPER.readTree(raw);
    } catch (IOException e) {
      return request; // not JSON after all - pass through, bus untouched
    }
    if (parsed == null || !parsed.isObject()) {
      return request;
    }
    ObjectNode root = (ObjectNode) parsed;

    JsonNode existing = root.get("shashu_metadata");
    boolean explicitRag =
        existing != null && existing.isObject() && existing.hasNonNull("rag_context");
    boolean explicitMem =
        existing != null && existing.isObject() && existing.hasNonNull("memory_context");
    if (explicitRag) {
      RetrievalBus.clearRetrievalForRequest();
    }
    if (explicitMem) {
      RetrievalBus.clearMemoryForRequest();
    }

    List<RetrievalEvent> events =
        explicitRag ? java.util.Collections.emptyList() : RetrievalBus.takeForRequest();
    List<MemoryEvent> memoryEvents =
        explicitMem ? java.util.Collections.emptyList() : RetrievalBus.takeMemoryForRequest();
    Map<String, Object> context = RagContextBuilder.build(events);
    Map<String, Object> memoryContext = MemoryContextBuilder.build(memoryEvents);
    if (context == null && memoryContext == null) {
      return request;
    }

    ObjectNode metadata =
        (existing != null && existing.isObject())
            ? (ObjectNode) existing
            : root.putObject("shashu_metadata");
    if (context != null) {
      metadata.set("rag_context", MAPPER.valueToTree(context));
    }
    if (memoryContext != null) {
      metadata.set("memory_context", MAPPER.valueToTree(memoryContext));
    }

    byte[] encoded = MAPPER.writeValueAsBytes(root);
    Request.Builder rebuilt =
        request
            .newBuilder()
            .method(request.method(), RequestBody.create(encoded, body.contentType()));

    // OkHttp derives Content-Length from the body, so the replacement is
    // already correct - but a caller that set the header by hand would
    // otherwise ship the OLD length and truncate the payload at the
    // gateway.
    if (request.header("Content-Length") != null) {
      rebuilt.header("Content-Length", Integer.toString(encoded.length));
    }
    return rebuilt.build();
  }

  /**
   * Whether the body is worth parsing. A missing content type is treated as JSON because vendor
   * SDKs do send one, and the parse below is the real gate either way.
   */
  private static boolean looksLikeJson(MediaType contentType) {
    if (contentType == null) {
      return true;
    }
    String subtype = contentType.subtype();
    return "json".equalsIgnoreCase(subtype) || subtype.toLowerCase().endsWith("+json");
  }
}
