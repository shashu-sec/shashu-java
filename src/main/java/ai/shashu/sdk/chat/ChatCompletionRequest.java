package ai.shashu.sdk.chat;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat-completions request. Only the fields Shashu inspects/forwards today are
 * typed; pass anything else via {@link Builder#extra(String, Object)} (provider-specific knobs like
 * Anthropic's {@code top_k}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ChatCompletionRequest {

  @JsonProperty("model")
  private final String model;

  @JsonProperty("messages")
  private final List<ChatMessage> messages;

  @JsonProperty("temperature")
  private final Double temperature;

  @JsonProperty("top_p")
  private final Double topP;

  @JsonProperty("max_tokens")
  private final Integer maxTokens;

  @JsonProperty("stream")
  private final Boolean stream;

  @JsonProperty("stop")
  private final List<String> stop;

  /**
   * Stored separately so the streaming / future re-builder paths can carry the typed RagContext
   * through. Serialised under {@code trustgate_metadata.rag_context} via the merged-payload getter.
   */
  @com.fasterxml.jackson.annotation.JsonIgnore private final RagContext ragContext;

  /** Free-form control-plane metadata; same lane as ragContext. */
  @com.fasterxml.jackson.annotation.JsonIgnore
  private final Map<String, Object> trustGateMetadataExtras;

  private final Map<String, Object> extra;

  private ChatCompletionRequest(Builder b) {
    this.model = b.model;
    this.messages = Collections.unmodifiableList(new ArrayList<>(b.messages));
    this.temperature = b.temperature;
    this.topP = b.topP;
    this.maxTokens = b.maxTokens;
    this.stream = b.stream;
    this.stop = b.stop != null ? Collections.unmodifiableList(new ArrayList<>(b.stop)) : null;
    this.extra = b.extra != null ? Collections.unmodifiableMap(new HashMap<>(b.extra)) : null;
    this.ragContext = b.ragContext;
    this.trustGateMetadataExtras =
        b.trustGateMetadata != null
            ? Collections.unmodifiableMap(new HashMap<>(b.trustGateMetadata))
            : null;
  }

  /** Model id on the request or the routed model on the response. */
  public String model() {
    return model;
  }

  /** Conversation turns in this payload. */
  public List<ChatMessage> messages() {
    return messages;
  }

  /** Sampling temperature forwarded to the provider. */
  public Double temperature() {
    return temperature;
  }

  /** Nucleus-sampling {@code top_p} forwarded to the provider. */
  public Double topP() {
    return topP;
  }

  /** Maximum completion tokens forwarded to the provider. */
  public Integer maxTokens() {
    return maxTokens;
  }

  /** Whether the caller asked for SSE; {@link ChatCompletions#create} rejects true. */
  public Boolean stream() {
    return stream;
  }

  /** Stop sequences forwarded to the provider. */
  public List<String> stop() {
    return stop;
  }

  /** Retrieved documents declared on this request for the gateway to scan. */
  public RagContext ragContext() {
    return ragContext;
  }

  /** Extra keys merged into {@code x-trustgate-metadata}. */
  public Map<String, Object> trustGateMetadataExtras() {
    return trustGateMetadataExtras;
  }

  /**
   * Computed-at-serialisation merged {@code trustgate_metadata} blob. Combines {@link
   * #trustGateMetadataExtras()} with {@link #ragContext()} so the gateway's ingress-rag scanner has
   * structured retrieval data to work with. Returns null when both are absent so the field is
   * omitted from the JSON body (NON_NULL include).
   */
  @JsonProperty("trustgate_metadata")
  public Map<String, Object> trustGateMetadataPayload() {
    if (ragContext == null
        && (trustGateMetadataExtras == null || trustGateMetadataExtras.isEmpty())) {
      return null;
    }
    Map<String, Object> merged =
        trustGateMetadataExtras != null ? new HashMap<>(trustGateMetadataExtras) : new HashMap<>();
    if (ragContext != null) {
      merged.putIfAbsent("rag_context", ragContext);
    }
    return merged;
  }

  /**
   * Provider-specific extension fields, serialised at the top level of the JSON body. Use for
   * fields not yet promoted to typed setters.
   */
  @JsonAnyGetter
  public Map<String, Object> extra() {
    return extra;
  }

  /** Start a fluent builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for this type. */
  public static final class Builder {
    private String model;
    private final List<ChatMessage> messages = new ArrayList<>();
    private Double temperature;
    private Double topP;
    private Integer maxTokens;
    private Boolean stream;
    private List<String> stop;
    private Map<String, Object> extra;
    private RagContext ragContext;
    private Map<String, Object> trustGateMetadata;

    /** Model id on the request or the routed model on the response. */
    public Builder model(String model) {
      this.model = model;
      return this;
    }

    /** Append one conversation turn. */
    public Builder addMessage(ChatMessage m) {
      this.messages.add(m);
      return this;
    }

    /** Conversation turns in this payload. */
    public Builder messages(List<ChatMessage> messages) {
      this.messages.clear();
      this.messages.addAll(messages);
      return this;
    }

    /** Sampling temperature forwarded to the provider. */
    public Builder temperature(Double temperature) {
      this.temperature = temperature;
      return this;
    }

    /** Nucleus-sampling {@code top_p} forwarded to the provider. */
    public Builder topP(Double topP) {
      this.topP = topP;
      return this;
    }

    /** Maximum completion tokens forwarded to the provider. */
    public Builder maxTokens(Integer maxTokens) {
      this.maxTokens = maxTokens;
      return this;
    }

    /** Whether the caller asked for SSE; {@link ChatCompletions#create} rejects true. */
    public Builder stream(Boolean stream) {
      this.stream = stream;
      return this;
    }

    /** Stop sequences forwarded to the provider. */
    public Builder stop(List<String> stop) {
      this.stop = stop;
      return this;
    }

    /** Passthrough JSON field merged into the request body. */
    public Builder extra(String key, Object value) {
      if (this.extra == null) this.extra = new HashMap<>();
      this.extra.put(key, value);
      return this;
    }

    /**
     * Attach structured RAG context so the gateway routes retrieved documents through the dedicated
     * ingress-rag Shashu scanner. See {@link RagContext}.
     */
    public Builder ragContext(RagContext ragContext) {
      this.ragContext = ragContext;
      return this;
    }

    /**
     * Free-form metadata sent under {@code trustgate_metadata} in the request body. Use for
     * control-plane fields the gateway adds after this SDK release without us having to ship typed
     * setters. Composable with {@link #ragContext}.
     */
    public Builder trustGateMetadata(String key, Object value) {
      if (this.trustGateMetadata == null) this.trustGateMetadata = new HashMap<>();
      this.trustGateMetadata.put(key, value);
      return this;
    }

    /** Construct the immutable value from this builder. */
    public ChatCompletionRequest build() {
      if (model == null || model.isBlank()) {
        throw new IllegalStateException("model is required");
      }
      if (messages.isEmpty()) {
        throw new IllegalStateException("at least one message is required");
      }
      return new ChatCompletionRequest(this);
    }
  }
}
