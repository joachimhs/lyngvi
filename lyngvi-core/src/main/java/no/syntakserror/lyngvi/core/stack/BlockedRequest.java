package no.syntakserror.lyngvi.core.stack;

/** A host the proxy denied, aggregated over the log (spec §4.3 {@code domain blocked}). */
public record BlockedRequest(String host, int count, long firstSeen, long lastSeen, int status, String method, String url) {}
