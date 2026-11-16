package ai.shashu.sdk.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * The {@code function} payload inside a {@link ToolCall} — OpenAI wire shape {@code {name,
 * arguments}}.
 *
 * <p>{@code arguments} is a <b>JSON-encoded string</b>, not a JSON object — exactly what providers
 * emit and what the gateway's {@code ingress-tool} scanner parses. Callers holding a structured
 * argument map must serialise it to a JSON string first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class FunctionCall {

  @JsonProperty("name")
  private final String name;

  @JsonProperty("arguments")
  private final String arguments;

  @JsonCreator
  /** Function name and JSON-string arguments on a tool call. */
  public FunctionCall(
      @JsonProperty("name") String name, @JsonProperty("arguments") String arguments) {
    this.name = name;
    this.arguments = arguments;
  }

  /** Tool or function name. */
  public String name() {
    return name;
  }

  /** JSON-encoded argument string, e.g. {@code "{\"city\":\"Paris\"}"}. */
  public String arguments() {
    return arguments;
  }

  @Override
  /** Value equality for this DTO. */
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof FunctionCall that)) return false;
    return Objects.equals(name, that.name) && Objects.equals(arguments, that.arguments);
  }

  @Override
  /** Hash of the same fields {@link #equals} compares. */
  public int hashCode() {
    return Objects.hash(name, arguments);
  }

  @Override
  /** Debug form of this DTO; not a wire encoding. */
  public String toString() {
    return "FunctionCall{name=" + name + ", arguments=" + arguments + "}";
  }
}
