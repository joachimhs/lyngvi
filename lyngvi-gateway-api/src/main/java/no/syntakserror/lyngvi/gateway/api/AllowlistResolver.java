package no.syntakserror.lyngvi.gateway.api;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How raw allowlist entries become canonical keys.
 *
 * <p>{@link #parse} handles entries that need no lookup (an issue key, a page id). {@link #resolve}
 * may call upstream for the rest (a page title); the runtime caches its result for five minutes.
 */
public interface AllowlistResolver {

    /** The canonical key of a raw entry, or null when it needs {@link #resolve}. */
    String parse(String entry);

    /** Resolves an entry that {@link #parse} could not; default: nothing. */
    default List<String> resolve(String entry, Upstream upstream) {
        return List.of();
    }

    /** Whether a request key is covered by the resolved set; default: exact membership. */
    default boolean matches(String key, Set<String> resolved) {
        return key != null && resolved.contains(key);
    }

    /** Entries are keys as written. */
    static AllowlistResolver exact() {
        return entry -> entry;
    }

    /** Entries are keys, compared case-insensitively. */
    static AllowlistResolver exactIgnoreCase() {
        return new AllowlistResolver() {
            @Override public String parse(String entry) { return entry.toLowerCase(Locale.ROOT); }
            @Override public boolean matches(String key, Set<String> resolved) { return key != null && resolved.contains(key.toLowerCase(Locale.ROOT)); }
        };
    }

    /** Entries are path prefixes; a key matches when it starts with an entry. */
    static AllowlistResolver pathPrefix() {
        return new AllowlistResolver() {
            @Override public String parse(String entry) { return entry; }
            @Override public boolean matches(String key, Set<String> resolved) {
                if (key == null) return false;
                for (String p : resolved) if (key.startsWith(p)) return true;
                return false;
            }
        };
    }

    /** Entries are regular expressions matched against the whole key. */
    static AllowlistResolver regex() {
        return new AllowlistResolver() {
            @Override public String parse(String entry) { return entry; }
            @Override public boolean matches(String key, Set<String> resolved) {
                if (key == null) return false;
                for (String p : resolved) if (key.matches(p)) return true;
                return false;
            }
        };
    }
}
