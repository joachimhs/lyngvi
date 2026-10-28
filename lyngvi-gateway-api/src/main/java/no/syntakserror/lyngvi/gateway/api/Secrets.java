package no.syntakserror.lyngvi.gateway.api;

/** Masking for human-readable status; never used for request logging (secrets are simply not logged). */
public final class Secrets {

    private Secrets() {}

    /** {@code (not set)}, all asterisks up to 8 characters, else {@code <first 3>…<last 2> (<n> chars)}. */
    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) return "(not set)";
        if (secret.length() <= 8) return "*".repeat(secret.length());
        return secret.substring(0, 3) + "…" + secret.substring(secret.length() - 2) + " (" + secret.length() + " chars)";
    }
}
