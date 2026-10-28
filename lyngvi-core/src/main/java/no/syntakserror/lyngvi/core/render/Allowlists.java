package no.syntakserror.lyngvi.core.render;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import no.syntakserror.lyngvi.core.stack.DomainEntry;

/** Read and edit the user section of {@code proxy/allowed-domains.txt} in place. */
public final class Allowlists {

    private Allowlists() {}

    public static List<DomainEntry> userEntries(Path file) {
        List<DomainEntry> out = new ArrayList<>();
        for (String line : AllowlistRenderer.userSection(file)) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int hash = l.indexOf('#');
            String domain = (hash >= 0 ? l.substring(0, hash) : l).strip();
            String note = hash >= 0 ? l.substring(hash + 1).strip() : "";
            out.add(new DomainEntry(domain.split("\\s+")[0], "user", note));
        }
        return out;
    }

    public static void appendUser(Path file, DomainEntry entry) {
        String content = InPlace.read(file);
        if (!content.endsWith("\n")) content += "\n";
        String line = entry.note().isBlank() ? entry.domain() : entry.domain() + "  # " + entry.note();
        InPlace.write(file, content + line + "\n");
    }

    public static Optional<DomainEntry> removeUser(Path file, String domain) {
        String content = InPlace.read(file);
        int userAt = content.indexOf(AllowlistRenderer.USER);
        if (userAt < 0) return Optional.empty();
        int sectionStart = content.indexOf('\n', userAt) + 1;
        String head = content.substring(0, sectionStart);
        List<String> kept = new ArrayList<>();
        DomainEntry removed = null;
        for (String line : content.substring(sectionStart).split("\n", -1)) {
            String l = line.strip();
            String d = l.isEmpty() || l.startsWith("#") ? null : l.split("[\\s#]+")[0];
            if (removed == null && domain.equals(d)) {
                int hash = l.indexOf('#');
                removed = new DomainEntry(domain, "user", hash >= 0 ? l.substring(hash + 1).strip() : "");
            } else {
                kept.add(line);
            }
        }
        if (removed == null) return Optional.empty();
        while (!kept.isEmpty() && kept.getLast().isBlank()) kept.removeLast();
        InPlace.write(file, head + (kept.isEmpty() ? "" : String.join("\n", kept) + "\n"));
        return Optional.of(removed);
    }
}
