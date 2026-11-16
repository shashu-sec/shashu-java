package ai.shashu.sdk.chat;

import ai.shashu.sdk.internal.ShashuApi;

/**
 * Resource namespace for chat. Mirrors the Python SDK's {@code client.chat.completions} path so
 * cross-language migration is mechanical.
 */
public final class Chat {
  private final ChatCompletions completions;

  /** Namespace for chat completions. */
  public Chat(ShashuApi api) {
    this.completions = new ChatCompletions(api);
  }

  /** Chat-completions resource ({@code POST /v1/chat/completions}). */
  public ChatCompletions completions() {
    return completions;
  }
}
