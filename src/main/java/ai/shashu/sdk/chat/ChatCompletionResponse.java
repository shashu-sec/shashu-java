package ai.shashu.sdk.chat;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat-completions response. The Shashu-specific security verdict / audit
 * metadata is attached as {@code x-trustgate-*} response headers, not in the JSON body — same as
 * the .NET / Python / Node SDKs. A top-level {@code trustgate} envelope, when present, is retained
 * in {@link #extraFields()} so {@code Shashu.decisionOf} can read the body carrier.
 */
public final class ChatCompletionResponse {

  private final String id;
  private final String object;
  private final Long created;
  private final String model;
  private final List<Choice> choices;
  private final Usage usage;
  private final Map<String, JsonNode> extraFields = new LinkedHashMap<>();
  @JsonIgnore private Map<String, String> trustGateHeaders = Map.of();

  @JsonCreator
  /** Parsed non-streaming chat completion body. */
  public ChatCompletionResponse(
      @JsonProperty("id") String id,
      @JsonProperty("object") String object,
      @JsonProperty("created") Long created,
      @JsonProperty("model") String model,
      @JsonProperty("choices") List<Choice> choices,
      @JsonProperty("usage") Usage usage) {
    this.id = id;
    this.object = object;
    this.created = created;
    this.model = model;
    this.choices =
        choices != null ? Collections.unmodifiableList(choices) : Collections.emptyList();
    this.usage = usage;
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
   * the same live body that a primitive {@code long} silently coerced to {@code 0}.
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

  /** Token-usage breakdown when the provider sent one. */
  public Usage usage() {
    return usage;
  }

  /**
   * Unknown JSON members, including the top-level {@code trustgate} decision envelope. Without this
   * slot the envelope is dropped on parse and {@code Shashu.decisionOf} cannot read the body
   * carrier.
   */
  public Map<String, JsonNode> extraFields() {
    return Collections.unmodifiableMap(extraFields);
  }

  @JsonAnySetter
  void putExtra(String name, JsonNode value) {
    extraFields.put(name, value);
  }

  /**
   * {@code x-trustgate-*} (and sibling) response headers captured from the HTTP response. Empty
   * when the object was not built by this SDK.
   */
  public Map<String, String> trustGateHeaders() {
    return trustGateHeaders;
  }

  /** Copy SHASHU response headers onto this completion for inspection. */
  public void attachShashuHeaders(Map<String, String> headers) {
    this.trustGateHeaders = (headers == null || headers.isEmpty()) ? Map.of() : Map.copyOf(headers);
  }

  /** Single choice (completion). */
  public static final class Choice {
    private final int index;
    private final ChatMessage message;
    private final String finishReason;

    @JsonCreator
    /** One choice in a completion or stream chunk. */
    public Choice(
        @JsonProperty("index") int index,
        @JsonProperty("message") ChatMessage message,
        @JsonProperty("finish_reason") String finishReason) {
      this.index = index;
      this.message = message;
      this.finishReason = finishReason;
    }

    /** Zero-based position of this choice. */
    public int index() {
      return index;
    }

    /** Assistant message for this choice. */
    public ChatMessage message() {
      return message;
    }

    /** Why this choice stopped ({@code stop}, {@code length}, {@code tool_calls}, …). */
    public String finishReason() {
      return finishReason;
    }
  }

  /** Token-usage breakdown. */
  public static final class Usage {
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;

    @JsonCreator
    /** Token counts the provider reported. */
    public Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens) {
      this.promptTokens = promptTokens;
      this.completionTokens = completionTokens;
      this.totalTokens = totalTokens;
    }

    /** Tokens in the prompt, as reported by the provider. */
    public int promptTokens() {
      return promptTokens;
    }

    /** Tokens in the completion, as reported by the provider. */
    public int completionTokens() {
      return completionTokens;
    }

    /** Prompt plus completion tokens, as reported by the provider. */
    public int totalTokens() {
      return totalTokens;
    }
  }
}
