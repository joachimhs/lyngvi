package no.syntakserror.lyngvi.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** How much git history a working copy receives (spec §3.1, §5.2). */
public enum History {
    /** No history: a fresh repository with a single baseline commit. */
    NONE,
    /** A sanitised clone limited to {@code depth} commits. */
    SHALLOW,
    /** A sanitised clone with the full history of the current branch. */
    FULL;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses {@code none|shallow|full}, case-insensitively. */
    @JsonCreator
    public static History parse(String text) {
        for (History h : values()) {
            if (h.json().equalsIgnoreCase(text.strip())) {
                return h;
            }
        }
        throw new IllegalArgumentException("Unknown history mode '" + text + "' (expected none, shallow or full)");
    }
}
