package ai.shashu.sdk.internal;

/**
 * One document as {@code rag_context.v1} {@code docs[]} spells it. Optional fields are {@code null}
 * when absent, because {@code additionalProperties: false} makes a malformed one reject the whole
 * payload. Visible across the {@code ai.shashu.sdk} packages but not part of the public API.
 */
public final class RagContextDoc {

  private final String id;
  private final String preview;
  private final Double score; // nullable
  private final String sourceUri; // nullable
  private final String hash; // nullable

  RagContextDoc(String id, String preview, Double score, String sourceUri, String hash) {
    this.id = id;
    this.preview = preview;
    this.score = score;
    this.sourceUri = sourceUri;
    this.hash = hash;
  }

  public String id() {
    return id;
  }

  public String preview() {
    return preview;
  }

  public Double score() {
    return score;
  }

  public String sourceUri() {
    return sourceUri;
  }

  public String hash() {
    return hash;
  }
}
