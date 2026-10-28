package no.syntakserror.lyngvi.core.contract;

/** The active profile, as shown by {@code status} and {@code profile show}. */
public record ProfileInfo(String name, String label, String pack) {}
