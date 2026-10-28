package no.syntakserror.lyngvi.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/** The {@code .env} file: build arguments and local overrides. Existing values are never changed. */
public final class DotEnv {

    static final String MARKER = "# -- added by lyngvi --";

    private DotEnv() {}

    /**
     * Creates {@code env} from {@code example} when missing, otherwise appends the keys
     * of {@code example} that are absent. Returns the keys that were added.
     */
    public static List<String> ensure(Path env, Path example) {
        try {
            if (!Files.exists(env)) {
                Files.createDirectories(env.toAbsolutePath().getParent());
                Files.copy(example, env);
                return new ArrayList<>(read(env).keySet());
            }
            Map<String, String> existing = read(env);
            Map<String, String> wanted = read(example);
            List<String> added = new ArrayList<>();
            StringBuilder append = new StringBuilder();
            for (Map.Entry<String, String> e : wanted.entrySet()) {
                if (!existing.containsKey(e.getKey())) {
                    added.add(e.getKey());
                    append.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                }
            }
            if (!added.isEmpty()) {
                String current = Files.readString(env, StandardCharsets.UTF_8);
                String prefix = current.isEmpty() || current.endsWith("\n") ? current : current + "\n";
                Files.writeString(env, prefix + "\n" + MARKER + "\n" + append, StandardCharsets.UTF_8);
            }
            return added;
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot update " + env + ": " + e.getMessage(), e);
        }
    }

    /**
     * Sets one variable in place, keeping every other line — comments and order included — as it was. A blank value
     * removes the variable. The file holds credentials, so it is created readable by its owner only.
     *
     * @return true when the file changed
     */
    public static boolean set(Path file, String key, String value) {
        String wanted = value == null ? "" : value.strip();
        if (wanted.contains("\n")) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "A value must be a single line", Map.of("key", key));
        }
        List<String> out = new ArrayList<>();
        boolean replaced = false;
        boolean existed = Files.exists(file);
        try {
            for (String raw : existed ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.<String>of()) {
                String line = raw.strip();
                String withoutExport = line.startsWith("export ") ? line.substring("export ".length()).strip() : line;
                int eq = withoutExport.indexOf('=');
                boolean isKey = !line.startsWith("#") && eq > 0 && withoutExport.substring(0, eq).strip().equals(key);
                if (!isKey) {
                    out.add(raw);
                    continue;
                }
                replaced = true;
                if (!wanted.isEmpty()) out.add(key + "=" + wanted);
            }
            if (!replaced && !wanted.isEmpty()) out.add(key + "=" + wanted);
            if (!replaced && wanted.isEmpty()) return false;
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, out.isEmpty() ? "" : String.join("\n", out) + "\n", StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Non-POSIX file system: nothing to tighten.
            }
            return true;
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot update " + file + ": " + e.getMessage(), e);
        }
    }

    /** Parses {@code KEY=value} lines; ignores comments, blanks and an {@code export } prefix. */
    public static Map<String, String> read(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + file + ": " + e.getMessage(), e);
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring("export ".length()).strip();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return out;
    }
}
