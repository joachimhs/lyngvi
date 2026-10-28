package no.syntakserror.lyngvi.core.render;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;

/**
 * {@code proxy/allowed-domains.txt} in three sections: profile, agents, user.
 * The user section is preserved verbatim across renders (spec §4.5).
 */
final class AllowlistRenderer {

    static final String HEADER = "# Lyngvi egress allowlist. Rendered by 'setup.sh rebuild-config'.\n"
            + "# One domain per line; a leading dot matches the domain and all subdomains.\n";
    static final String PROFILE = "# profile";
    static final String AGENTS = "# agents";
    static final String USER = "# user (kept across rebuilds; edit with 'setup.sh domain add|remove')";

    private AllowlistRenderer() {}

    static String render(RenderInputs in) {
        StringBuilder sb = new StringBuilder(HEADER);
        sb.append(PROFILE).append('\n');
        for (String d : in.profile().egress().allow()) {
            sb.append(d).append('\n');
        }
        sb.append(AGENTS).append('\n');
        for (AgentDescriptor a : in.agents()) {
            for (String host : a.egress()) {
                sb.append(host).append("  # ").append(a.id()).append('\n');
            }
        }
        sb.append(USER).append('\n');
        for (String line : userSection(in.paths().allowedDomains())) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Lines after the user header in an existing file; empty when the file or header is absent. */
    static List<String> userSection(Path file) {
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        boolean inUser = false;
        for (String line : InPlace.read(file).split("\n", -1)) {
            if (inUser) {
                out.add(line);
            } else if (line.startsWith("# user")) {
                inUser = true;
            }
        }
        while (!out.isEmpty() && out.getLast().isBlank()) out.removeLast();
        return out;
    }

    /** Entries in the user section that are domains (not comments or blanks). */
    static List<String> userDomains(Path file) {
        List<String> out = new ArrayList<>();
        for (String line : userSection(file)) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            out.add(l.split("\\s+")[0]);
        }
        return out;
    }
}
