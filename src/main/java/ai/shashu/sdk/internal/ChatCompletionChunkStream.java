package ai.shashu.sdk.internal;

import ai.shashu.sdk.ShashuException;
import ai.shashu.sdk.chat.ChatCompletionChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Iterable, closeable stream of {@link ChatCompletionChunk} parsed from a Server-Sent Events
 * response. Backed by an OkHttp {@link ResponseBody}'s {@link BufferedSource} — line-by-line read,
 * SSE framing parsed lazily.
 *
 * <p>Always close via try-with-resources so the underlying connection returns to OkHttp's pool.
 * Closing mid-iteration aborts cleanly.
 */
public final class ChatCompletionChunkStream
    implements Iterable<ChatCompletionChunk>, AutoCloseable {

  private final Response response;
  private final ObjectMapper mapper;
  private boolean iteratorObtained = false;

  /**
   * @param response Live OkHttp response (status 2xx, content-type SSE). Ownership transfers to
   *     this stream — closing the stream closes the response body.
   * @param mapper Jackson mapper, configured with the same naming strategy as the rest of the SDK.
   */
  public ChatCompletionChunkStream(Response response, ObjectMapper mapper) {
    this.response = response;
    this.mapper = mapper;
  }

  @Override
  public Iterator<ChatCompletionChunk> iterator() {
    if (iteratorObtained) {
      // SSE streams are single-pass; calling iterator() twice would
      // re-consume the same body and likely deadlock.
      throw new IllegalStateException("Iterator already obtained; SSE streams are single-pass.");
    }
    iteratorObtained = true;

    ResponseBody body = response.body();
    if (body == null) {
      return java.util.Collections.<ChatCompletionChunk>emptyList().iterator();
    }
    return new SseIterator(body.source(), mapper);
  }

  @Override
  public void close() {
    response.close();
  }

  /**
   * Pull-based SSE parser. Read lines until blank-line frame boundary, then deserialise the
   * accumulated {@code data:} payload (skipping {@code [DONE]} sentinel).
   */
  private static final class SseIterator implements Iterator<ChatCompletionChunk> {
    private final BufferedSource source;
    private final ObjectMapper mapper;
    private ChatCompletionChunk pending;
    private boolean ended;

    SseIterator(BufferedSource source, ObjectMapper mapper) {
      this.source = source;
      this.mapper = mapper;
    }

    @Override
    public boolean hasNext() {
      if (ended) return false;
      if (pending != null) return true;
      try {
        pending = readNext();
      } catch (IOException e) {
        throw new ShashuException("SSE stream read failed", e);
      }
      if (pending == null) {
        ended = true;
        return false;
      }
      return true;
    }

    @Override
    public ChatCompletionChunk next() {
      if (!hasNext()) throw new NoSuchElementException();
      ChatCompletionChunk out = pending;
      pending = null;
      return out;
    }

    private ChatCompletionChunk readNext() throws IOException {
      StringBuilder dataBuffer = new StringBuilder();
      while (true) {
        String line = source.readUtf8Line(); // blocks; null on EOF
        if (line == null) {
          // EOF without a trailing blank line — flush whatever
          // remains. Most well-behaved emitters terminate with
          // \n\n so this branch is rarely hit in practice.
          if (dataBuffer.length() > 0) {
            return parsePayload(dataBuffer.toString());
          }
          return null;
        }
        if (line.isEmpty()) {
          // Blank line = end of frame.
          if (dataBuffer.length() == 0) continue;
          ChatCompletionChunk parsed = parsePayload(dataBuffer.toString());
          if (parsed != null) return parsed;
          // [DONE] sentinel — terminate stream.
          return null;
        }
        if (line.startsWith("data:")) {
          // Strip the field/value separator. SSE spec allows an
          // OPTIONAL space after the colon; handle both forms.
          String value =
              (line.length() > 5 && line.charAt(5) == ' ') ? line.substring(6) : line.substring(5);
          if (dataBuffer.length() > 0) dataBuffer.append('\n');
          dataBuffer.append(value);
        }
        // event:/id:/retry:/comment(:) lines are ignored.
      }
    }

    private ChatCompletionChunk parsePayload(String payload) {
      if ("[DONE]".equals(payload)) return null;
      try {
        ChatCompletionChunk parsed = mapper.readValue(payload, ChatCompletionChunk.class);
        ShashuApi.throwIfStreamBlocked(parsed);
        return parsed;
      } catch (ai.shashu.sdk.ShashuException e) {
        throw e;
      } catch (IOException e) {
        throw new ShashuException("Failed to parse SSE chunk: " + e.getMessage(), e);
      }
    }
  }
}
