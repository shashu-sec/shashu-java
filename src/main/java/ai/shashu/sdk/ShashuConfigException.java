package ai.shashu.sdk;

/**
 * Thrown when no gateway address resolves.
 *
 * <p>Shashu is self-hosted. The operator's gateway is on their own network under their own name, so
 * <b>there is no default address and the SDK will not guess one</b>: an SDK that fell back to a
 * vendor hostname would send a misconfigured process's prompts to a host nobody in that
 * organisation chose.
 *
 * <p>A subclass of {@link ShashuException} so a caller that already catches the parent for generic
 * handling keeps catching this, while a caller that wants to distinguish "misconfigured" from "the
 * gateway said no" can catch this instead.
 *
 * <p>The message text is the frozen template in {@code contracts/sdk-surface.v1.md}; the peer type
 * is Python's and Node's {@code ShashuConfigError} and .NET's {@code ShashuConfigException}.
 * Changing its wording is a v2 change.
 */
public class ShashuConfigException extends ShashuException {

  /** Raised when the client is misconfigured (both credentials, or neither). */
  public ShashuConfigException(String message) {
    super(message);
  }
}
