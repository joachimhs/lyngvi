package no.syntakserror.lyngvi.gateway.api;

import java.util.List;

/** {@code count} is the number of entries in the file, {@code resolved} the keys they currently resolve to. */
public record AllowlistInfo(boolean enforced, String label, String file, int count, List<String> entries, List<String> resolved) {}
