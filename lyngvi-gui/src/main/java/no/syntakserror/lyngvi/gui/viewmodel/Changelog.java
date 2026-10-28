package no.syntakserror.lyngvi.gui.viewmodel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.syntakserror.lyngvi.core.version.Version;

/** {@code CHANGELOG.md} as versioned entries; the version button shows what is new since the last seen version. */
public record Changelog(List<Entry> entries) {

    public record Entry(String version, String date, String body) {}

    private static final Pattern HEADING = Pattern.compile("^## +(\\S+)(?: +- +(\\S+))?\\s*$");

    public static Changelog parse(String markdown) {
        List<Entry> out = new ArrayList<>();
        String version = null;
        String date = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\n")) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                if (version != null) out.add(new Entry(version, date, body.toString().strip()));
                version = m.group(1);
                date = m.group(2);
                body.setLength(0);
            } else if (version != null) {
                body.append(line).append('\n');
            }
        }
        if (version != null) out.add(new Entry(version, date, body.toString().strip()));
        return new Changelog(List.copyOf(out));
    }

    /** The changelog packaged with the GUI. */
    public static Changelog bundled() {
        try (InputStream in = Changelog.class.getResourceAsStream("/CHANGELOG.md")) {
            return in == null ? new Changelog(List.of()) : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Changelog(List.of());
        }
    }

    /** Entries newer than {@code version} (unparsable versions count as older than everything). */
    public List<Entry> since(String version) {
        Version base;
        try {
            base = Version.parse(version);
        } catch (RuntimeException e) {
            return entries;
        }
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            try {
                if (Version.parse(e.version()).compareTo(base) > 0) out.add(e);
            } catch (RuntimeException ignored) {
                // skip headings that are not versions
            }
        }
        return out;
    }

    public String latestVersion() {
        return entries.isEmpty() ? "" : entries.getFirst().version();
    }
}
