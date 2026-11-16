package ai.shashu.sdk;

import java.util.List;

/**
 * The decision's {@code attachments} block, or its header counts when that is all there is. {@code
 * items} is empty when read off the header, which never carries per-file detail; {@code carrier} is
 * {@code "body"} or {@code "header"}.
 */
public record Attachments(
    int total,
    int scanned,
    int scannedPartial,
    int notInspected,
    int blocked,
    List<Attachment> items,
    String carrier) {

  public Attachments {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
