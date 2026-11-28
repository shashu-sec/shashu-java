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
 * OpenAI payload, unlike {@link RagContext} which rides under {@code shashu_metadata}. Sending them
 * typed lets the gateway's {@code ingress-tool} scanner read each function name and its
 * JSON-encoded arguments structurally instead of relying on best-effort de-flattening of pasted
 * text. All three fields are omitted from the JSON when unset, so plain {@code (role, content)}
 * messages serialise exactly as they always have.
 *
 * <p>The content is either one string or an ordered list of {@link ContentPart}s (text, images,
 * files). Build the second form with {@link #user(ContentPart, ContentPart...)} or {@link
 * #withParts(String, List)}; {@link #content()} is then {@code null} and {@link #contentParts()}
 * holds the parts.
 */
public final class ChatMessage {

  private final String role;

  /** A {@code String}, a read-only {@code List<ContentPart>}, or null; serialised as it is. */
  private final Object content;

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

  /** One OpenAI-shaped chat message with text content. */
  public ChatMessage(
      String role, String content, List<ToolCall> toolCalls, String toolCallId, String name) {
    this((Object) content, role, toolCalls, toolCallId, name);
  }

  // Content comes first so this cannot collide with the public String constructor.
  private ChatMessage(
      Object content, String role, List<ToolCall> toolCalls, String toolCallId, String name) {
    this.role = role;
    this.content = content;
    this.toolCalls =
        toolCalls != null ? Collections.unmodifiableList(new ArrayList<>(toolCalls)) : null;
    this.toolCallId = toolCallId;
    this.name = name;
  }

  @JsonCreator
  static ChatMessage fromWire(
      @JsonProperty("role") String role,
      @JsonProperty("content") Object content,
      @JsonProperty("tool_calls") List<ToolCall> toolCalls,
      @JsonProperty("tool_call_id") String toolCallId,
      @JsonProperty("name") String name) {
    Object value;
    if (content == null || content instanceof String) {
      value = content;
    } else if (content instanceof List<?> list) {
      value = list.stream().map(ContentPart::fromWire).toList();
    } else if (content instanceof Number || content instanceof Boolean) {
      value = content.toString();
    } else {
      throw new IllegalArgumentException("content must be a string or an array of parts");
    }
    return new ChatMessage(value, role, toolCalls, toolCallId, name);
  }

  /**
   * A message whose content is an ordered list of parts. The list is copied and must hold at least
   * one part.
   */
  public static ChatMessage withParts(String role, List<ContentPart> parts) {
    List<ContentPart> copy = List.copyOf(Objects.requireNonNull(parts, "parts"));
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("parts must not be empty");
    }
    return new ChatMessage((Object) copy, role, null, null, null);
  }

  /** A user-role message made of parts, e.g. a question and the document it is about. */
  public static ChatMessage user(ContentPart first, ContentPart... rest) {
    List<ContentPart> parts = new ArrayList<>(1 + rest.length);
    parts.add(first);
    Collections.addAll(parts, rest);
    return withParts("user", parts);
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

  /** The text content; {@code null} when there is none or the message carries parts. */
  public String content() {
    return content instanceof String text ? text : null;
  }

  /** The content parts, read-only; {@code null} unless the content is a list of parts. */
  @SuppressWarnings("unchecked") // Both construction paths store only List<ContentPart>.
  public List<ContentPart> contentParts() {
    return content instanceof List<?> list ? (List<ContentPart>) list : null;
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
