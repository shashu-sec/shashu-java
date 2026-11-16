package ai.shashu.sdk.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Single message in a chat conversation. {@code role} is one of {@code system} / {@code user} /
 * {@code assistant} / {@code tool}.
 *
 * <p>The tool-call fields ({@link #toolCalls()}, {@link #toolCallId()}, {@link #name()}) are
 * optional and serialise at the <b>message</b> level — they are part of the provider-forwarded
 * OpenAI payload, unlike {@link RagContext} which rides under {@code trustgate_metadata}. Sending
 * them typed lets the gateway's {@code ingress-tool} scanner read each function name and its
 * JSON-encoded arguments structurally instead of relying on best-effort de-flattening of pasted
 * text. All three fields are omitted from the JSON when unset, so plain {@code (role, content)}
 * messages serialise exactly as they always have.
 */
public final class ChatMessage {

  private final String role;
  private final String content;

  /** Assistant-issued tool calls (OpenAI {@code tool_calls} array). */
  @JsonProperty("tool_calls")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final List<ToolCall> toolCalls;

  /** On a {@code role:"tool"} result — id of the tool call being answered. */
  @JsonProperty("tool_call_id")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final String toolCallId;

  /** Optional name of the executed tool on a {@code role:"tool"} result. */
  @JsonProperty("name")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final String name;

  /** Plain {@code (role, content)} message — wire shape unchanged. */
  public ChatMessage(String role, String content) {
    this(role, content, null, null, null);
  }

  @JsonCreator
  /** One OpenAI-shaped chat message. */
  public ChatMessage(
      @JsonProperty("role") String role,
      @JsonProperty("content") String content,
      @JsonProperty("tool_calls") List<ToolCall> toolCalls,
      @JsonProperty("tool_call_id") String toolCallId,
      @JsonProperty("name") String name) {
    this.role = role;
    this.content = content;
    this.toolCalls =
        toolCalls != null ? Collections.unmodifiableList(new ArrayList<>(toolCalls)) : null;
    this.toolCallId = toolCallId;
    this.name = name;
  }

  /** A system-role message. */
  public static ChatMessage system(String content) {
    return new ChatMessage("system", content);
  }

  /** A user-role message. */
  public static ChatMessage user(String content) {
    return new ChatMessage("user", content);
  }

  /** An assistant-role message. */
  public static ChatMessage assistant(String content) {
    return new ChatMessage("assistant", content);
  }

  /** Assistant message issuing tool calls ({@code content} may be null). */
  public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
    return new ChatMessage("assistant", content, toolCalls, null, null);
  }

  /** Tool-result message answering the tool call with id {@code toolCallId}. */
  public static ChatMessage tool(String toolCallId, String content) {
    return new ChatMessage("tool", content, null, toolCallId, null);
  }

  /** Tool-result message with the executed tool's {@code name} attached. */
  public static ChatMessage tool(String toolCallId, String content, String name) {
    return new ChatMessage("tool", content, null, toolCallId, name);
  }

  /** Message role ({@code system}, {@code user}, {@code assistant}, or {@code tool}). */
  public String role() {
    return role;
  }

  /** Message or delta text. */
  public String content() {
    return content;
  }

  /** Tool calls on an assistant message, if any. */
  public List<ToolCall> toolCalls() {
    return toolCalls;
  }

  /** Id when this message is a tool result. */
  public String toolCallId() {
    return toolCallId;
  }

  /** Tool or function name. */
  public String name() {
    return name;
  }

  @Override
  /** Value equality for this DTO. */
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ChatMessage that)) return false;
    return Objects.equals(role, that.role)
        && Objects.equals(content, that.content)
        && Objects.equals(toolCalls, that.toolCalls)
        && Objects.equals(toolCallId, that.toolCallId)
        && Objects.equals(name, that.name);
  }

  @Override
  /** Hash of the same fields {@link #equals} compares. */
  public int hashCode() {
    return Objects.hash(role, content, toolCalls, toolCallId, name);
  }

  @Override
  /** Debug form of this DTO; not a wire encoding. */
  public String toString() {
    StringBuilder sb =
        new StringBuilder("ChatMessage{role=").append(role).append(", content=").append(content);
    if (toolCalls != null) sb.append(", toolCalls=").append(toolCalls);
    if (toolCallId != null) sb.append(", toolCallId=").append(toolCallId);
    if (name != null) sb.append(", name=").append(name);
    return sb.append("}").toString();
  }
}
