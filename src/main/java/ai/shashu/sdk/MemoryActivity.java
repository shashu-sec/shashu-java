package ai.shashu.sdk;

import java.util.List;

/**
 * The decision's {@code memory} block, or its header counts when that is all there is. {@code
 * coverage} ({@code gateway} / {@code observed} / {@code hosted} / {@code mixed}, raw) is null off
 * a header.
 */
public record MemoryActivity(
    int writes,
    int writesBlocked,
    int recalls,
    int recallsFlagged,
    String coverage,
    List<MemoryItem> items,
    String carrier) {

  public MemoryActivity {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
