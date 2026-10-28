package no.syntakserror.lyngvi.core.config;

import java.util.Set;

/** Names that cannot be used for repositories because they are taken inside {@code /work}. */
public final class ReservedNames {

    private static final Set<String> RESERVED = Set.of("output", "_attachments", "AGENTS.md", "CLAUDE.md");

    private ReservedNames() {}

    public static boolean isReserved(String name) {
        return RESERVED.contains(name);
    }

    public static Set<String> all() {
        return RESERVED;
    }
}
