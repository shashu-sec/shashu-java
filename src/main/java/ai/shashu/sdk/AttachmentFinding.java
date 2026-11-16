package ai.shashu.sdk;

/**
 * One finding inside an attached file. {@code detector} and {@code category} are open sets; {@code
 * code} is passed through raw — never branch on it.
 */
public record AttachmentFinding(
    String detector, String category, String code, String severity, String locator) {}
