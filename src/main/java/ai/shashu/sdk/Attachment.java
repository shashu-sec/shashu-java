package ai.shashu.sdk;

import java.util.List;

/**
 * One attached file, as the gateway reported it. Read-only.
 *
 * <p>{@code reason} is passed through raw: a reason this SDK has never heard of is still just a
 * string here. {@code rawState} is the verbatim wire value of {@code state}, known or not.
 */
public record Attachment(
    AttachmentState state,
    String rawState,
    String reason,
    String filename,
    String declaredType,
    String sniffedType,
    String sha256,
    Long bytes,
    String source,
    Integer index,
    String path,
    List<AttachmentFinding> findings) {

  public Attachment {
    state = state == null ? AttachmentState.UNKNOWN : state;
    findings = findings == null ? List.of() : List.copyOf(findings);
  }
}
