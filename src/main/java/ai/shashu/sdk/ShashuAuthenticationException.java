package ai.shashu.sdk;

/**
 * 401 — authentication failure. When the gateway names a cloud-pass refusal, {@link #getCode()} is
 * one of the six {@code pass_*} codes and the message is the gateway's sentence, unchanged.
 */
public class ShashuAuthenticationException extends ShashuException {

  private final String code;

  /** 401 from the gateway, with a {@code pass_*} code when the body carries one. */
  public ShashuAuthenticationException(
      int statusCode, String responseBody, String message, String code) {
    super(statusCode, responseBody, message);
    this.code = code;
  }

  /** Gateway {@code error.code}, or {@code null} when the body carried none. */
  public String getCode() {
    return code;
  }
}
