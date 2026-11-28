package ai.shashu.sdk.chat;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One part of a multi-part {@link ChatMessage}: text, an image, or a file, in the OpenAI chat
 * format the gateway inspects and forwards. The Python and Node SDKs send the same shapes.
 *
 * <p>Instances are immutable. {@link #toString()} names the part type only, so attached bytes never
 * reach a log through it.
 */
@JsonSerialize(using = ContentPart.Serializer.class)
public final class ContentPart {

  private final Map<String, Object> fields;

  private ContentPart(Map<String, Object> fields) {
    this.fields = fields;
  }

  /** A text part: {@code {"type":"text","text":…}}. */
  public static ContentPart text(String text) {
    Objects.requireNonNull(text, "text");
    return of("text", "text", text);
  }

  /**
   * An image part referring to a URL: {@code {"type":"image_url","image_url":{"url":…}}}. The URL
   * is sent as given. Whether the gateway fetches or inspects a remote URL is decided by the
   * agent's file policy.
   */
  public static ContentPart imageUrl(String url) {
    Objects.requireNonNull(url, "url");
    return of("image_url", "image_url", nested("url", url));
  }

  /** An image part carrying the bytes as a base64 {@code data:} URI; the SDK does the encoding. */
  public static ContentPart imageData(byte[] data, String mimeType) {
    return imageUrl(dataUri(data, mimeType));
  }

  /**
   * A file part: {@code {"type":"file","file":{"filename":…,"file_data":"data:…;base64,…"}}}; the
   * SDK does the base64 encoding. Size and type limits are the gateway's.
   */
  public static ContentPart file(byte[] data, String filename, String mimeType) {
    requireText(filename, "filename");
    Map<String, Object> file = nested("filename", filename);
    file.put("file_data", dataUri(data, mimeType));
    return of("file", "file", file);
  }

  /** A part read from a response; unknown types and fields are kept as sent. */
  static ContentPart fromWire(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("a content part must be a JSON object");
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
    return new ContentPart(copy);
  }

  /** The part type, e.g. {@code text}, {@code image_url} or {@code file}. */
  public String type() {
    return fields.get("type") instanceof String type ? type : null;
  }

  /** The text of a {@code text} part; {@code null} for any other type. */
  public String text() {
    return "text".equals(type()) && fields.get("text") instanceof String text ? text : null;
  }

  /** The part as its wire fields, read-only. */
  public Map<String, Object> asMap() {
    return Collections.unmodifiableMap(fields);
  }

  @Override
  /** Parts are equal when their wire fields are. */
  public boolean equals(Object o) {
    return this == o || (o instanceof ContentPart that && fields.equals(that.fields));
  }

  @Override
  /** Hash of the wire fields. */
  public int hashCode() {
    return fields.hashCode();
  }

  @Override
  /** The part type only; never the content. */
  public String toString() {
    return "ContentPart{type=" + type() + "}";
  }

  private static ContentPart of(String type, String key, Object value) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("type", type);
    fields.put(key, value);
    return new ContentPart(fields);
  }

  private static Map<String, Object> nested(String key, Object value) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(key, value);
    return map;
  }

  private static String dataUri(byte[] data, String mimeType) {
    Objects.requireNonNull(data, "data");
    requireText(mimeType, "mimeType");
    return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(data);
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  /**
   * Writes a part with the escaping the Python and Node SDKs use. Jackson's default escapes
   * characters outside the Basic Multilingual Plane as surrogate pairs; they do not.
   */
  static final class Serializer extends StdSerializer<ContentPart> {
    private static final long serialVersionUID = 1L;

    Serializer() {
      super(ContentPart.class);
    }

    @Override
    public void serialize(ContentPart part, JsonGenerator generator, SerializerProvider provider)
        throws IOException {
      StringBuilder out = new StringBuilder();
      write(out, part.fields);
      generator.writeRawValue(new SerializedString(out.toString()));
    }

    private static void write(StringBuilder out, Object value) {
      if (value instanceof String text) {
        quote(out, text);
      } else if (value instanceof Map<?, ?> map) {
        out.append('{');
        String separator = "";
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          out.append(separator);
          quote(out, String.valueOf(entry.getKey()));
          out.append(':');
          write(out, entry.getValue());
          separator = ",";
        }
        out.append('}');
      } else if (value instanceof List<?> list) {
        out.append('[');
        List<?> items = new ArrayList<>(list);
        for (int i = 0; i < items.size(); i++) {
          if (i > 0) out.append(',');
          write(out, items.get(i));
        }
        out.append(']');
      } else {
        // Number, Boolean or null, as parsed from a response.
        out.append(value);
      }
    }

    private static void quote(StringBuilder out, String text) {
      out.ensureCapacity(out.length() + text.length() + 2);
      out.append('"');
      int length = text.length();
      for (int i = 0; i < length; i++) {
        char c = text.charAt(i);
        switch (c) {
          case '"' -> out.append("\\\"");
          case '\\' -> out.append("\\\\");
          case '\b' -> out.append("\\b");
          case '\f' -> out.append("\\f");
          case '\n' -> out.append("\\n");
          case '\r' -> out.append("\\r");
          case '\t' -> out.append("\\t");
          default -> {
            if (Character.isHighSurrogate(c)
                && i + 1 < length
                && Character.isLowSurrogate(text.charAt(i + 1))) {
              out.append(c).append(text.charAt(++i));
            } else if (c < 0x20 || Character.isSurrogate(c)) {
              out.append(String.format("\\u%04x", (int) c));
            } else {
              out.append(c);
            }
          }
        }
      }
      out.append('"');
    }
  }
}
