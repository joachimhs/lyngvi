package no.syntakserror.lyngvi.core.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One registered repository: {@code REPO=<path>[|<name>[|history=<mode>]]} in {@code sandbox.config}.
 *
 * @param path    absolute path on the host
 * @param name    mount name inside {@code /work}; defaults to the path's last segment
 * @param history per-repository override of the profile's {@code copy.history}
 */
public record RepoEntry(Path path, String name, Optional<History> history) {

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

    public RepoEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(history, "history");
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Repository name '" + name + "' must match " + NAME.pattern());
        }
        if (ReservedNames.isReserved(name)) {
            throw new IllegalArgumentException("Repository name '" + name + "' is reserved");
        }
    }

    /** Parses the {@code sandbox.config} form. An empty name field means "use the basename". */
    public static RepoEntry parse(String text) {
        String[] parts = text.strip().split("\\|", -1);
        if (parts.length > 3 || parts[0].isBlank()) {
            throw new IllegalArgumentException("Invalid REPO entry: '" + text + "'");
        }
        Path path = Path.of(parts[0].strip());
        String name = parts.length > 1 && !parts[1].isBlank() ? parts[1].strip() : basename(path);
        Optional<History> history = Optional.empty();
        if (parts.length == 3) {
            String opt = parts[2].strip();
            if (!opt.startsWith("history=")) {
                throw new IllegalArgumentException("Unknown REPO option '" + opt + "' (expected history=none|shallow|full)");
            }
            history = Optional.of(History.parse(opt.substring("history=".length())));
        }
        return new RepoEntry(path, name, history);
    }

    /** The {@code sandbox.config} form; omits defaulted fields. */
    public String format() {
        boolean customName = !name.equals(basename(path));
        StringBuilder sb = new StringBuilder(path.toString());
        if (customName || history.isPresent()) {
            sb.append('|').append(customName ? name : "");
        }
        history.ifPresent(h -> sb.append("|history=").append(h.json()));
        return sb.toString();
    }

    private static String basename(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }
}
