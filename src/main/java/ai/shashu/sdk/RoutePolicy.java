package ai.shashu.sdk;

/**
 * Why a route was refused for the data in the request — for example "route {@code general-chat} is
 * not approved for {@code phi}". Every field is passed through raw.
 */
public record RoutePolicy(
    String route, String category, String reason, String requestedRoute, String degradedRoute) {}
