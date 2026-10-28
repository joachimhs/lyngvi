package no.syntakserror.lyngvi.core.stack;

/** One allowlisted domain and where it comes from: {@code profile}, {@code agent:<id>} or {@code user}. */
public record DomainEntry(String domain, String source, String note) {}
