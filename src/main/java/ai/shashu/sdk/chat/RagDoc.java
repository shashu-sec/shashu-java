package ai.shashu.sdk.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One retrieved document in a {@link RagContext}. The gateway scanner needs at minimum a {@link
 * #preview()} (or {@code content} / {@code text} alias the gateway accepts); {@link #id()} and
 * {@link #score()} are recorded for audit + citation but optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RagDoc {

  @JsonProperty("id")
  private final String id;

  @JsonProperty("score")
  private final Double score;

  @JsonProperty("preview")
  private final String preview;

  /** One retrieved document declared on a request. */
  public RagDoc(String id, Double score, String preview) {
    this.id = id;
    this.score = score;
    this.preview = preview;
  }

  /** Convenience factory for the most-common shape. */
  public static RagDoc of(String id, double score, String preview) {
    return new RagDoc(id, score, preview);
  }

  /** Provider-assigned completion or tool-call id. */
  public String id() {
    return id;
  }

  /** Retriever ranking score for this document. */
  public Double score() {
    return score;
  }

  /** Snippet of the retrieved document, capped by the gateway. */
  public String preview() {
    return preview;
  }
}
