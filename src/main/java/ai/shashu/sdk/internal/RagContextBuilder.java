package ai.shashu.sdk.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns whatever a retriever handed the customer into {@code rag_context.v1}, and folds the carried
 * declarations into the one block the joiner attaches. Visible across the {@code ai.shashu.sdk}
 * packages but not part of the public API.
 *
 * <p>Sibling of the Python SDK's {@code shashu/rag.py}, the Node SDK's {@code src/rag.ts} and the
 * .NET SDK's {@code Internal/RagContextBuilder.cs}. The reading rules are a DATA table ({@link
 * #FIELD_KEYS}) rather than a chain of branches, so a document type that spells its fields the
 * usual way needs no new code at all &mdash; and {@code RagCoercionTests} holds a case for every
 * row.
 */
public final class RagContextBuilder {

  private RagContextBuilder() {}

  /**
   * {@code rag_context.v1} {@code source.enum}. Restated here rather than read from the schema at
   * load time: the SDK must not need a JSON file on disk to make a call. {@code
   * RagContextSchemaTests} holds this list to the schema's enum, so the two cannot drift apart
   * unnoticed.
   */
  public static final List<String> SOURCES =
      Collections.unmodifiableList(
          Arrays.asList(
              "manual", "langchain", "llamaindex", "tool", "otel", "n8n", "provider", "other"));

  /** {@code docs.items.properties.hash.pattern}. */
  private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{8,64}$");

  /** {@code docs.items.properties.score} bounds. */
  private static final double SCORE_MIN = -1.0d;

  private static final double SCORE_MAX = 1000000.0d;

  /** {@code docs.items.properties.id.maxLength}. */
  private static final int MAX_ID_CHARS = 512;

  /**
   * {@code source_uri} has no maximum in the contract; this one is ours, so a retriever that puts a
   * whole document in a URI field cannot bloat the body.
   */
  private static final int MAX_SOURCE_URI_CHARS = 2048;

  // --- The coercion table -------------------------------------------------
  //
  // Field names come from these key lists, tried in order. A Java retriever
  // hands back records, POJOs, Maps or plain Strings; all four are read
  // through the same table by map key, accessor method or field.

  private static final String[] PREVIEW_KEYS = {
    "preview", "text", "content", "page_content", "pageContent", "body"
  };
  private static final String[] ID_KEYS = {
    "id", "doc_id", "docId", "document_id", "documentId", "node_id", "nodeId", "_id"
  };
  private static final String[] SCORE_KEYS = {"score", "similarity", "relevance"};
  private static final String[] SOURCE_URI_KEYS = {
    "source_uri",
    "sourceUri",
    "url",
    "uri",
    "source",
    "file_path",
    "filePath",
    "file_name",
    "fileName"
  };
  private static final String[] HASH_KEYS = {"hash", "sha256", "digest"};

  /** The five fields, and the names a retriever might spell each with. */
  public static final Map<String, String[]> FIELD_KEYS;

  static {
    Map<String, String[]> keys = new LinkedHashMap<>();
    keys.put("preview", PREVIEW_KEYS);
    keys.put("id", ID_KEYS);
    keys.put("score", SCORE_KEYS);
    keys.put("source_uri", SOURCE_URI_KEYS);
    keys.put("hash", HASH_KEYS);
    FIELD_KEYS = Collections.unmodifiableMap(keys);
  }

  /**
   * Coerce a caller's documents into the wire shape, dropping any the table cannot read text out
   * of.
   *
   * <p>A document with no readable text is dropped rather than sent: {@code preview} has {@code
   * minLength: 1}, and text the SDK cannot read is text the gateway cannot scan.
   */
  static List<RagContextDoc> coerceDocs(Object docs) {
    List<RagContextDoc> out = new ArrayList<>();
    int position = 0;
    for (Object doc : asDocumentList(docs)) {
      position++;
      RagContextDoc normalised = normalise(read(doc), position);
      if (normalised != null) {
        out.add(normalised);
      }
    }
    return out;
  }

  /**
   * Treat a single document as a one-element list. A bare {@code String} is not a {@code
   * Collection} in Java, so it needs no special case for the per-character hazard the Python and
   * .NET ports have &mdash; but a single record or Map does, and arrays are a shape Java retrievers
   * genuinely return.
   */
  private static List<Object> asDocumentList(Object docs) {
    if (docs == null) {
      return Collections.emptyList();
    }
    if (docs instanceof Collection<?> collection) {
      return new ArrayList<>(collection);
    }
    if (docs instanceof Object[] array) {
      return Arrays.asList(array);
    }
    return Collections.singletonList(docs);
  }

  /** Read the five fields out of one document, by whatever it is. */
  private static Map<String, Object> read(Object doc) {
    Map<String, Object> found = new HashMap<>();
    if (doc == null) {
      return found;
    }
    if (doc instanceof CharSequence text) {
      found.put("preview", text.toString());
      return found;
    }

    Function<String, Object> lookup = lookupFor(doc);
    for (Map.Entry<String, String[]> entry : FIELD_KEYS.entrySet()) {
      for (String key : entry.getValue()) {
        Object value = lookup.apply(key);
        if (value != null) {
          found.put(entry.getKey(), value);
          break;
        }
      }
    }

    // Gaps filled from a `metadata` bag, the way LangChain4j and the
    // LlamaIndex ports spell provenance. Never the text: a retriever that
    // put the body in metadata would double it into the payload.
    Object metadata = lookup.apply("metadata");
    if (metadata != null && !(metadata instanceof CharSequence)) {
      Function<String, Object> meta = lookupFor(metadata);
      for (Map.Entry<String, String[]> entry : FIELD_KEYS.entrySet()) {
        if ("preview".equals(entry.getKey()) || found.containsKey(entry.getKey())) {
          continue;
        }
        for (String key : entry.getValue()) {
          Object value = meta.apply(key);
          if (value != null) {
            found.put(entry.getKey(), value);
            break;
          }
        }
      }
    }

    return found;
  }

  /**
   * A key reader for one object: map lookup where it is a Map, otherwise a public no-argument
   * accessor ({@code preview()}, {@code getPreview()}, {@code isPreview()}) or a public field,
   * matched case-insensitively.
   */
  private static Function<String, Object> lookupFor(Object doc) {
    if (doc instanceof Map<?, ?> map) {
      return key -> {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (entry.getKey() instanceof CharSequence k && k.toString().equalsIgnoreCase(key)) {
            return entry.getValue();
          }
        }
        return null;
      };
    }

    Class<?> type = doc.getClass();
    return key -> {
      Method accessor = accessorFor(type, key);
      if (accessor != null) {
        return safeGet(() -> accessor.invoke(doc));
      }
      Field field = fieldFor(type, key);
      return field != null ? safeGet(() -> field.get(doc)) : null;
    };
  }

  private static Method accessorFor(Class<?> type, String key) {
    String getter = "get" + capitalise(key);
    String is = "is" + capitalise(key);
    for (Method method : type.getMethods()) {
      if (method.getParameterCount() != 0
          || method.getReturnType() == void.class
          || Modifier.isStatic(method.getModifiers())) {
        continue;
      }
      String name = method.getName();
      if (name.equalsIgnoreCase(key)
          || name.equalsIgnoreCase(getter)
          || name.equalsIgnoreCase(is)) {
        return method;
      }
    }
    return null;
  }

  private static Field fieldFor(Class<?> type, String key) {
    for (Field field : type.getFields()) {
      if (!Modifier.isStatic(field.getModifiers()) && field.getName().equalsIgnoreCase(key)) {
        return field;
      }
    }
    return null;
  }

  private static String capitalise(String key) {
    return key.isEmpty() ? key : Character.toUpperCase(key.charAt(0)) + key.substring(1);
  }

  /** Something that reads one member and may fail doing it. */
  private interface Getter {
    Object get() throws ReflectiveOperationException;
  }

  /**
   * A customer's accessor is customer code and may throw. A retrieval declaration must never be the
   * thing that breaks their request, so a throwing member reads as absent.
   */
  private static Object safeGet(Getter getter) {
    try {
      return getter.get();
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  private static RagContextDoc normalise(Map<String, Object> raw, int position) {
    String preview = asText(raw.get("preview"), RetrievalBus.MAX_PREVIEW_CHARS);
    if (preview == null) {
      return null;
    }
    return new RagContextDoc(
        asId(raw.get("id"), position),
        preview,
        asScore(raw.get("score")),
        asText(raw.get("source_uri"), MAX_SOURCE_URI_CHARS),
        asHash(raw.get("hash")));
  }

  private static String asText(Object value, int limit) {
    if (!(value instanceof CharSequence sequence)) {
      return null;
    }
    String text = sequence.toString();
    if (text.length() > limit) {
      text = text.substring(0, limit);
    }
    return text.isEmpty() ? null : text;
  }

  /** A stable id, else a positional one. Never derived from document text. */
  private static String asId(Object value, int position) {
    if (value instanceof CharSequence sequence) {
      String cleaned = sequence.toString().trim();
      if (cleaned.length() > MAX_ID_CHARS) {
        cleaned = cleaned.substring(0, MAX_ID_CHARS);
      }
      if (!cleaned.isEmpty()) {
        return cleaned;
      }
    }
    if (value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte) {
      return value.toString();
    }
    if (value instanceof UUID) {
      return value.toString();
    }
    return "doc-" + position;
  }

  private static Double asScore(Object value) {
    double score;
    if (value instanceof Boolean) {
      return null;
    } else if (value instanceof Number number) {
      score = number.doubleValue();
    } else if (value instanceof CharSequence sequence) {
      try {
        score = Double.parseDouble(sequence.toString().trim());
      } catch (NumberFormatException e) {
        return null;
      }
    } else {
      return null;
    }

    // JSON has no NaN or Infinity literal, so an unrepresentable score is
    // dropped rather than sent - and dropping the score keeps the
    // document, which still has text worth scanning.
    if (Double.isNaN(score) || Double.isInfinite(score)) {
      return null;
    }
    return (score < SCORE_MIN || score > SCORE_MAX) ? null : score;
  }

  private static String asHash(Object value) {
    return (value instanceof CharSequence sequence) && HEX.matcher(sequence.toString()).matches()
        ? sequence.toString()
        : null;
  }

  // ---------------------------------------------------------------- fold

  /**
   * Fold carried declarations into one {@code rag_context.v1} block, or {@code null} when nothing
   * survived.
   *
   * <p>The contract carries a single {@code corpus}, {@code query} and {@code source} for the whole
   * declaration, so multiple events have to be reconciled:
   *
   * <ul>
   *   <li>{@code corpus} &mdash; the one they share, or the distinct ids joined with {@code +}. A
   *       hybrid retrieval across two indexes is a real shape, and naming only one of them would
   *       misattribute the other's documents.
   *   <li>{@code source} &mdash; the one they share, else {@code other}, the enum value that exists
   *       for exactly this.
   *   <li>{@code query} &mdash; the most recent non-empty one: the query nearest the call.
   * </ul>
   *
   * <p>Documents are de-duplicated by {@code (id, preview)}, first occurrence kept &mdash; the
   * pair, not the id alone, because a positional id ({@code doc-1}) is assigned per event, so two
   * unrelated retrievals both produce a {@code doc-1} and matching on the id would silently discard
   * the second one's first document.
   *
   * <p>Returns {@code null} when nothing survived, so the joiner never writes an empty {@code docs}
   * array ({@code minItems: 1}).
   */
  static Map<String, Object> build(List<RetrievalEvent> events) {
    if (events.isEmpty()) {
      return null;
    }

    Set<List<String>> seen = new HashSet<>();
    List<RagContextDoc> docs = new ArrayList<>();
    for (RetrievalEvent event : events) {
      for (RagContextDoc doc : event.docs()) {
        // The PAIR, not a concatenation: any separator a preview
        // could itself contain would let two different documents
        // collide into one key.
        if (seen.add(List.of(doc.id(), doc.preview()))) {
          docs.add(doc);
        }
      }
    }
    if (docs.isEmpty()) {
      return null;
    }
    if (docs.size() > RetrievalBus.MAX_DOCS) {
      docs = new ArrayList<>(docs.subList(docs.size() - RetrievalBus.MAX_DOCS, docs.size()));
    }

    List<String> corpora = new ArrayList<>();
    Set<String> sources = new LinkedHashSet<>();
    String query = null;
    for (RetrievalEvent event : events) {
      if (!corpora.contains(event.corpus())) {
        corpora.add(event.corpus());
      }
      sources.add(event.source());
      if (event.query() != null && !event.query().isEmpty()) {
        query = event.query();
      }
    }

    String corpus = String.join("+", corpora);
    if (corpus.length() > RetrievalBus.MAX_CORPUS_CHARS) {
      corpus = corpus.substring(0, RetrievalBus.MAX_CORPUS_CHARS);
      while (corpus.endsWith("+")) {
        corpus = corpus.substring(0, corpus.length() - 1);
      }
    }

    Map<String, Object> context = new LinkedHashMap<>();
    context.put("v", 1);
    context.put("corpus", corpus);
    if (query != null) {
      context.put("query", query);
    }
    context.put("source", sources.size() == 1 ? sources.iterator().next() : "other");

    List<Map<String, Object>> wire = new ArrayList<>(docs.size());
    for (RagContextDoc doc : docs) {
      wire.add(toWire(doc));
    }
    context.put("docs", wire);
    return context;
  }

  /**
   * One document as the JSON object the gateway reads. Optional keys are omitted rather than sent
   * null: the schema is {@code additionalProperties: false} with typed optionals, and a null would
   * fail validation on a field the caller simply did not have.
   */
  private static Map<String, Object> toWire(RagContextDoc doc) {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("id", doc.id());
    if (doc.score() != null) {
      wire.put("score", doc.score());
    }
    wire.put("preview", doc.preview());
    if (doc.sourceUri() != null) {
      wire.put("source_uri", doc.sourceUri());
    }
    if (doc.hash() != null) {
      wire.put("hash", doc.hash());
    }
    return wire;
  }

  // ------------------------------------------------------- validated input

  /**
   * Validate and normalise a corpus. A blank one is a call-site mistake, and throwing here is the
   * only place it can be seen &mdash; the alternative is a payload the gateway rejects at the far
   * end of a customer's stack.
   */
  static String requireCorpus(String corpus) {
    if (corpus == null || corpus.isBlank()) {
      throw new IllegalArgumentException(
          "declareRetrieval needs a non-empty corpus: it is what the gateway"
              + " records the documents as having come from, and rag_context.v1"
              + " requires it.");
    }
    String trimmed = corpus.trim();
    return trimmed.length() > RetrievalBus.MAX_CORPUS_CHARS
        ? trimmed.substring(0, RetrievalBus.MAX_CORPUS_CHARS)
        : trimmed;
  }

  /** Validate a {@code rag_context.v1} {@code source} enum value. */
  static String requireSource(String source) {
    if (source == null || !SOURCES.contains(source)) {
      throw new IllegalArgumentException(
          "declareRetrieval source='"
              + source
              + "' is not a rag_context.v1"
              + " value. Use one of: "
              + String.join(", ", SOURCES)
              + ". A new"
              + " integration adds its value to the contract first.");
    }
    return source;
  }

  static String truncateQuery(String query) {
    if (query == null || query.isEmpty()) {
      return null;
    }
    return query.length() > RetrievalBus.MAX_QUERY_CHARS
        ? query.substring(0, RetrievalBus.MAX_QUERY_CHARS)
        : query;
  }

  /**
   * Build a validated event. {@code Retrieval} is the only door a customer reaches this through.
   */
  public static RetrievalEvent newEvent(Object docs, String corpus, String query, String source) {
    return new RetrievalEvent(
        requireCorpus(corpus), truncateQuery(query), requireSource(source), coerceDocs(docs));
  }
}
