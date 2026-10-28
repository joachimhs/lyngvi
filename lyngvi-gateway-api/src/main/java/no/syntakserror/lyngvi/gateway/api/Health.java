package no.syntakserror.lyngvi.gateway.api;

/** Result of an upstream probe. */
public record Health(boolean ok, int status, String reason, String user) {}
