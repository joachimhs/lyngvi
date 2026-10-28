package no.syntakserror.lyngvi.core.ask;

/** Whether the agent has credentials, and of what kind. */
public record AuthStatus(boolean loggedIn, String mode, String account, String expiresAt) {}
