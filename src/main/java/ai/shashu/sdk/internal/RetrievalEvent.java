package ai.shashu.sdk.internal;

import java.util.Collections;
import java.util.List;

/**
 * One retrieval declaration, already coerced to the wire shape. Visible across the {@code
 * ai.shashu.sdk} packages but not part of the public API.
 */
public final class RetrievalEvent {

  private final String corpus;
  private final String query; // nullable
  private final String source;
  private final List<RagContextDoc> docs;

  RetrievalEvent(String corpus, String query, String source, List<RagContextDoc> docs) {
    this.corpus = corpus;
    this.query = query;
    this.source = source;
    this.docs = Collections.unmodifiableList(docs);
  }

  public String corpus() {
    return corpus;
  }

  public String query() {
    return query;
  }

  public String source() {
    return source;
  }

  public List<RagContextDoc> docs() {
    return docs;
  }

  /** A copy carrying only the tail {@code keep} documents. */
  RetrievalEvent withDocs(List<RagContextDoc> replacement) {
    return new RetrievalEvent(corpus, query, source, replacement);
  }
}
