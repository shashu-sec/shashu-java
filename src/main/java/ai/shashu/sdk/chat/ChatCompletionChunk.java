package ai.shashu.sdk.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;

/**
 * Single delta chunk in a streaming chat-completions response. Same shape OpenAI emits over
 * Server-Sent Events when {@code stream=true}. Concatenate {@code
 * choices().get(0).delta().content()} across all chunks to reconstruct the full assistant message.
 */
public final class ChatCompletionChunk {

  private final String id;
  private final String object;
  private final Long created;
  private final String model;
  private final List<Choice> choices;

  @JsonCreator
  /** One SSE chunk from a streaming chat completion. */
  public ChatCompletionChunk(
      @JsonProperty("id") String id,
      @JsonProperty("object") String object,
      @JsonProperty("created") Long created,
      @JsonProperty("model") String model,
      @JsonProperty("choices") List<Choice> choices) {
    this.id = id;
    this.object = object;
    this.created = created;
    this.model = model;
    this.choices =
        choices != null ? Collections.unmodifiableList(choices) : Collections.emptyList();
  }

  /** Provider-assigned completion or tool-call id. */
  public String id() {
    return id;
  }

  /** OpenAI object type ({@code chat.completion} or {@code chat.completion.chunk}). */
  public String object() {
    return object;
  }

  /**
   * Unix timestamp. {@code null} when the gateway's allowed path emits {@code "created": null} —
   * the same live frame that a primitive {@code long} silently coerced to {@code 0}.
   */
  public Long created() {
    return created;
  }

  /** Model id on the request or the routed model on the response. */
  public String model() {
    return model;
  }

  /** Completion choices returned by the provider. */
  public List<Choice> choices() {
    return choices;
  }

  /** Single choice's incremental delta within a streaming response. */
  public static final class Choice {
    private final int index;
    private final Delta delta;
    private final String finishReason;

    @JsonCreator
    /** One choice in a completion or stream chunk. */
    public Choice(
        @JsonProperty("index") int index,
        @JsonProperty("delta") Delta delta,
        @JsonProperty("finish_reason") String finishReason) {
      this.index = index;
      this.delta = delta;
      this.finishReason = finishReason;
    }

    /** Zero-based position of this choice. */
    public int index() {
      return index;
    }

    /** Incremental tokens on a stream chunk. */
    public Delta delta() {
      return delta;
    }

    /** Why this choice stopped ({@code stop}, {@code length}, {@code tool_calls}, …). */
    public String finishReason() {
      return finishReason;
    }
  }

  /**
   * Incremental delta. Fields are only present in chunks that include them — {@code role} typically
   * appears only on the first chunk.
   */
  public static final class Delta {
    private final String role;
    private final String content;

    @JsonCreator
    /** Incremental role/content on a stream chunk. */
    public Delta(@JsonProperty("role") String role, @JsonProperty("content") String content) {
      this.role = role;
      this.content = content;
    }

    /** Message role ({@code system}, {@code user}, {@code assistant}, or {@code tool}). */
    public String role() {
      return role;
    }

    /** Message or delta text. */
    public String content() {
      return content;
    }
  }
}
