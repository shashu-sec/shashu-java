package ai.shashu.sdk.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * One assistant-issued tool call — OpenAI wire shape {@code {id, type, function: {name,
 * arguments}}}, serialised inside a message's {@code tool_calls} array (see {@link
 * ChatMessage#toolCalls()}). This is the structure the gateway's {@code ingress-tool} scanner reads
 * function names and arguments from.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ToolCall {

  @JsonProperty("id")
  private final String id;

  /** {@code "function"} — the only tool-call type in the wire format today. */
  @JsonProperty("type")
  private final String type;

  @JsonProperty("function")
  private final FunctionCall function;

  @JsonCreator
  /** One tool call on an assistant message. */
  public ToolCall(
      @JsonProperty("id") String id,
      @JsonProperty("type") String type,
      @JsonProperty("function") FunctionCall function) {
    this.id = id;
    this.type = type;
    this.function = function;
  }

  /**
   * Convenience factory for a {@code type:"function"} call.
   *
   * @param argumentsJson JSON-encoded argument string (see {@link FunctionCall#arguments()})
   */
  public static ToolCall function(String id, String functionName, String argumentsJson) {
    return new ToolCall(id, "function", new FunctionCall(functionName, argumentsJson));
  }

  /** Provider-assigned completion or tool-call id. */
  public String id() {
    return id;
  }

  /** Tool-call type, usually {@code function}. */
  public String type() {
    return type;
  }

  /** Function name and JSON arguments on a tool call. */
  public FunctionCall function() {
    return function;
  }

  @Override
  /** Value equality for this DTO. */
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ToolCall that)) return false;
    return Objects.equals(id, that.id)
        && Objects.equals(type, that.type)
        && Objects.equals(function, that.function);
  }

  @Override
  /** Hash of the same fields {@link #equals} compares. */
  public int hashCode() {
    return Objects.hash(id, type, function);
  }

  @Override
  /** Debug form of this DTO; not a wire encoding. */
  public String toString() {
    return "ToolCall{id=" + id + ", type=" + type + ", function=" + function + "}";
  }
}
