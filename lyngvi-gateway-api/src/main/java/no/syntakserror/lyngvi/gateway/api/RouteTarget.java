package no.syntakserror.lyngvi.gateway.api;

import java.util.Map;

/** Where a pasted browser URL should be routed: a plugin path plus query parameters. */
public record RouteTarget(String path, Map<String, String> query) {}
