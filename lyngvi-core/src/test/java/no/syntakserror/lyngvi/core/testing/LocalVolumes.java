package no.syntakserror.lyngvi.core.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.ProcessRunner;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.stack.SessionVolume;

/**
 * Test double for the Podman volumes a session's files live in: every volume is a directory under
 * {@code root}, and the helper containers {@code SessionVolume} starts run as plain local commands in
 * it. Only the command shapes that class builds are understood — {@code podman volume …} and
 * {@code podman run --rm …} with {@code -v}, {@code -e}, {@code -w} and {@code --entrypoint} — which is
 * what makes the translation honest: the production code constructs the real command line, and the
 * commands inside it (git above all) run for real.
 *
 * <p>Container paths are mapped back to host paths through the mounts, and so are the environment
 * variables that hold them ({@code WORK}, {@code SRC}, {@code OUT}).
 */
public final class LocalVolumes implements Runner {

    /** Where a session volume is mounted in every container that sees it. */
    private static final String MOUNT = SessionVolume.MOUNT;

    private final Path root;
    private final ProcessRunner real = new ProcessRunner(Platform.current());

    public LocalVolumes(Path root) {
        this.root = root;
    }

    /** The host directory standing in for a volume. */
    public Path dir(String volume) {
        return root.resolve(volume);
    }

    /**
     * True for the commands this double understands: {@code podman volume …}, and the helper containers
     * {@code SessionVolume} starts, recognised by the volume mounted at the mount point. Every other
     * {@code podman run --rm} — a model catalogue, a deep check — belongs to the {@link FakeRunner}.
     */
    public boolean handles(List<String> command) {
        if (command.isEmpty() || !command.getFirst().equals("podman") || command.size() < 2) return false;
        if (command.get(1).equals("volume")) return true;
        if (!command.get(1).equals("run") || !command.contains("--rm")) return false;  // -d is the session container
        return command.stream().anyMatch(a -> a.endsWith(":" + MOUNT + ":rw") && !a.startsWith("/"));
    }

    @Override
    public Result run(List<String> command, Path workingDir, Duration timeout) {
        return run(command, workingDir, timeout, null);
    }

    @Override
    public Result run(List<String> command, Path cwd, Duration timeout, Consumer<String> onLine) {
        return run(command, cwd, timeout, Map.of(), onLine);
    }

    @Override
    public Result run(List<String> command, Path cwd, Duration timeout, Map<String, String> extraEnv, Consumer<String> onLine) {
        try {
            return command.get(1).equals("volume") ? volume(command) : helper(command, timeout, onLine);
        } catch (IOException e) {
            return new Result(command, 1, "", e.toString());
        }
    }

    @Override
    public StreamHandle stream(List<String> command, Path cwd, Map<String, String> extraEnv, String stdin, Consumer<String> onLine) {
        Result r = run(command, cwd, Duration.ofMinutes(1), onLine);
        return new StreamHandle() {
            @Override public void cancel() {}
            @Override public int waitFor() { return r.exitCode(); }
            @Override public boolean isCancelled() { return false; }
        };
    }

    // ---- podman volume ----------------------------------------------------------------------------

    private Result volume(List<String> cmd) throws IOException {
        String sub = cmd.get(2);
        return switch (sub) {
            case "create" -> create(cmd);
            case "exists" -> new Result(cmd, Files.isDirectory(dir(cmd.getLast())) ? 0 : 1, "", "");
            case "rm" -> {
                Path d = dir(cmd.getLast());
                deleteTree(d);
                Files.deleteIfExists(labels(cmd.getLast()));
                yield new Result(cmd, 0, "", "");
            }
            case "ls" -> list(cmd);
            default -> new Result(cmd, 1, "", "unsupported: " + sub);
        };
    }

    private Result create(List<String> cmd) throws IOException {
        String name = cmd.getLast();
        Files.createDirectories(dir(name));
        StringBuilder labels = new StringBuilder();
        for (int i = 3; i < cmd.size() - 1; i++) {
            if (cmd.get(i).equals("--label")) labels.append(cmd.get(++i)).append('\n');
        }
        Files.createDirectories(labels(name).getParent());
        Files.writeString(labels(name), labels.toString());
        return new Result(cmd, 0, "", "");
    }

    /** Only the filter and format {@code SessionVolumes.list} uses: a project label, name and session id. */
    private Result list(List<String> cmd) throws IOException {
        String filter = "";
        for (int i = 3; i < cmd.size(); i++) {
            if (cmd.get(i).equals("--filter") && i + 1 < cmd.size()) filter = cmd.get(i + 1).replaceFirst("^label=", "");
        }
        final String want = filter;
        StringBuilder out = new StringBuilder();
        if (Files.isDirectory(root)) {
            try (var names = Files.list(root)) {
                for (Path p : names.sorted().toList()) {
                    String name = p.getFileName().toString();
                    if (!Files.isDirectory(p)) continue;
                    Map<String, String> labels = labelsOf(name);
                    if (!want.isBlank() && !labels.entrySet().stream().anyMatch(e -> (e.getKey() + "=" + e.getValue()).equals(want))) continue;
                    out.append(name).append('\t').append(labels.getOrDefault("lyngvi.session", "")).append('\n');
                }
            }
        }
        return new Result(cmd, 0, out.toString(), "");
    }

    private Path labels(String volume) {
        return root.resolve(".labels").resolve(volume);
    }

    private Map<String, String> labelsOf(String volume) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(labels(volume))) return out;
        for (String line : Files.readString(labels(volume)).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return out;
    }

    // ---- podman run --rm --------------------------------------------------------------------------

    private Result helper(List<String> cmd, Duration timeout, Consumer<String> onLine) throws IOException {
        Map<String, Path> mounts = new LinkedHashMap<>();
        Map<String, String> env = new LinkedHashMap<>();
        String workdir = null;
        String entrypoint = null;
        List<String> argv = new ArrayList<>();
        for (int i = 2; i < cmd.size(); i++) {
            String a = cmd.get(i);
            switch (a) {
                case "--rm" -> { }
                case "-v" -> mount(mounts, cmd.get(++i));
                case "-e" -> {
                    String kv = cmd.get(++i);
                    int eq = kv.indexOf('=');
                    env.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
                case "-w" -> workdir = cmd.get(++i);
                case "--entrypoint" -> entrypoint = cmd.get(++i);
                case "--network", "--userns", "--cap-drop", "--security-opt" -> i++;
                default -> {
                    // the image, then the command's arguments
                    argv.addAll(cmd.subList(i + 1, cmd.size()));
                    i = cmd.size();
                }
            }
        }
        if (entrypoint == null) return new Result(cmd, 1, "", "no --entrypoint in " + cmd);
        List<String> local = new ArrayList<>(List.of(entrypoint));
        local.addAll(argv);
        Map<String, String> localEnv = new LinkedHashMap<>();
        env.forEach((k, v) -> localEnv.put(k, mounts.containsKey(v) ? mounts.get(v).toString() : v));
        Path cwd = translate(mounts, workdir);
        Files.createDirectories(cwd);
        Result r = real.run(local, cwd, timeout, localEnv, onLine);
        return new Result(cmd, r.exitCode(), r.stdout(), r.stderr());
    }

    private void mount(Map<String, Path> mounts, String spec) throws IOException {
        String[] parts = spec.split(":");
        Path source = parts[0].startsWith("/") ? Path.of(parts[0]) : dir(parts[0]);
        if (!parts[0].startsWith("/")) Files.createDirectories(source);
        mounts.put(parts[1], source);
    }

    /** A container path to the host path it stands for, through the longest matching mount. */
    private Path translate(Map<String, Path> mounts, String path) {
        if (path == null) return root;
        String best = null;
        for (String dest : mounts.keySet()) {
            if ((path.equals(dest) || path.startsWith(dest + "/")) && (best == null || dest.length() > best.length())) best = dest;
        }
        if (best == null) return root;
        String rest = path.substring(best.length());
        return rest.isBlank() ? mounts.get(best) : mounts.get(best).resolve(rest.substring(1));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
