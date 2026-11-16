package ai.shashu.sdk.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured retrieval context for the gateway's {@code ingress-rag} Shashu scanner. All fields are
 * optional; the gateway falls back gracefully if any are missing.
 *
 * <p>Set {@link ChatCompletionRequest.Builder#ragContext(RagContext)} to attach. The SDK serialises
 * this under {@code trustgate_metadata.rag_context}; the gateway strips that key before forwarding
 * to the LLM provider, so the upstream model never sees the control-plane metadata.
 *
 * <p>Without RAG context, the gateway scans concatenated retrieved text on the generic {@code
 * ingress-text} surface — substantially less effective at catching indirect prompt injection inside
 * retrieved documents (a major attack vector for RAG apps).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RagContext {

  @JsonProperty("corpus")
  private final String corpus;

  @JsonProperty("query")
  private final String query;

  @JsonProperty("docs")
  private final List<RagDoc> docs;

  private RagContext(Builder b) {
    this.corpus = b.corpus;
    this.query = b.query;
    this.docs = b.docs != null ? Collections.unmodifiableList(new ArrayList<>(b.docs)) : null;
  }

  /** Named retrieval corpus these documents came from. */
  public String corpus() {
    return corpus;
  }

  /** Query that produced these retrieved documents. */
  public String query() {
    return query;
  }

  /** Retrieved documents declared for scanning. */
  public List<RagDoc> docs() {
    return docs;
  }

  /** Start a fluent builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for this type. */
  public static final class Builder {
    private String corpus;
    private String query;
    private List<RagDoc> docs;

    /** Named retrieval corpus these documents came from. */
    public Builder corpus(String corpus) {
      this.corpus = corpus;
      return this;
    }

    /** Query that produced these retrieved documents. */
    public Builder query(String query) {
      this.query = query;
      return this;
    }

    /** Retrieved documents declared for scanning. */
    public Builder docs(List<RagDoc> docs) {
      this.docs = docs;
      return this;
    }

    /** Append one retrieved document. */
    public Builder addDoc(RagDoc doc) {
      if (this.docs == null) this.docs = new ArrayList<>();
      this.docs.add(doc);
      return this;
    }

    /** Construct the immutable value from this builder. */
    public RagContext build() {
      return new RagContext(this);
    }
  }
}
