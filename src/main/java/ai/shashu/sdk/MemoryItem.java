package ai.shashu.sdk;

/**
 * One memory write or recall. Every field is passed through raw; {@code kind} is {@code write} or
 * {@code recall} today and a new kind is still just a string.
 */
public record MemoryItem(
    String kind,
    String state,
    String reason,
    String tool,
    String store,
    String scope,
    String source,
    String enforcement,
    String memoryRef,
    String writeRef,
    Boolean seenBefore) {}
