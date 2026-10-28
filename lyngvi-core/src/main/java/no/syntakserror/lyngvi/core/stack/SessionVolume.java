package no.syntakserror.lyngvi.core.stack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.runtime.Runner;

/**
 * The files of one session — the working copies and the agent's output — in a Podman volume (spec §5.1).
 *
 * <p>Nothing the agent can write lives on the host. The volume is the container's {@code /work}, and the
 * host reaches into it only through the short-lived helper containers started here: no network, every
 * capability dropped, the base image, and the same user namespace as the session container so files keep
 * the host user's uid. That is more than tidiness. The working copy's {@code .git} is the agent's to
 * write, so a {@code git} the host ran in it would run the agent's {@code hooks/} and its {@code config}
 * ({@code core.fsmonitor}, {@code core.sshCommand}, aliases) as the human, outside the sandbox. Here such
 * a program runs in a throwaway container with no network instead.
 *
 * <p>Every helper gets {@code WORK=/work} in its environment, and scripts address the volume as
 * {@code "$WORK"} rather than by the literal path. Values that come from outside — paths, commits,
 * messages — are passed as environment variables too, never pasted into the script text.
 */
public final class SessionVolume {

    /** Where the volume is mounted, in every container that sees it. */
    public static final String MOUNT = "/work";
    /** Where a host directory a helper reads is mounted. */
    private static final String SOURCE = "/src";
    /** Where a host directory a helper writes into is mounted. */
    private static final String SINK = "/out";

    public static final Duration SHORT = Duration.ofMinutes(5);
    public static final Duration LONG = Duration.ofMinutes(30);

    /** One entry of {@link #tree}, with its path relative to the listed directory. */
    public record Entry(String path, boolean directory) {}

    private final Runner runner;
    private final Path cwd;
    private final Path temp;
    private final String name;
    private final String sessionId;
    private final String project;
    private final String image;
    private final String userns;

    SessionVolume(Runner runner, Path cwd, Path temp, String container, String sessionId, String project, String image, int uid, int gid) {
        this.runner = runner;
        this.cwd = cwd;
        this.temp = temp;
        this.name = nameFor(container);
        this.sessionId = sessionId;
        this.project = project;
        this.image = image;
        this.userns = "keep-id:uid=" + uid + ",gid=" + gid;
    }

    /** The volume of a session container: the container's name with {@code -work} after it. */
    public static String nameFor(String container) {
        return container + "-work";
    }

    public String name() {
        return name;
    }

    public boolean exists() {
        try {
            return runner.run(List.of("podman", "volume", "exists", name), cwd, SHORT).success();
        } catch (SandboxException e) {
            return false;
        }
    }

    /** Creates the volume unless it is already there, labelled so that orphans can be found again. */
    public void create() {
        if (exists()) return;
        List<String> cmd = new ArrayList<>(List.of("podman", "volume", "create",
                "--label", "lyngvi.project=" + project));
        if (sessionId != null) {
            cmd.addAll(List.of("--label", "lyngvi.session=" + sessionId));
        }
        cmd.add(name);
        runner.run(cmd, cwd, SHORT).requireSuccess("podman volume create");
    }

    public void remove() {
        try {
            runner.run(List.of("podman", "volume", "rm", "-f", name), cwd, SHORT);
        } catch (SandboxException ignored) {
            // podman unavailable: nothing to remove
        }
    }

    /**
     * Runs a shell script in {@code dir} (relative to the volume root; {@code ""} is the root itself).
     * It sees {@code $WORK} and whatever {@code env} adds.
     */
    public Runner.Result sh(String dir, String script, Map<String, String> env, Duration timeout) {
        return shell(List.of(), dir, script, env, timeout);
    }

    /** As {@link #sh}, with a host directory mounted writable as {@code $OUT} for what the script produces. */
    public Runner.Result shToHost(String dir, Path hostDir, String script, Map<String, String> env, Duration timeout) {
        return shToHost(dir, hostDir, script, env, List.of(), timeout);
    }

    /** As {@link #shToHost}, with {@code args} as the script's positional parameters ({@code "$@"}). */
    public Runner.Result shToHost(String dir, Path hostDir, String script, Map<String, String> env, List<String> args, Duration timeout) {
        Map<String, String> all = new LinkedHashMap<>(env);
        all.put("OUT", SINK);
        return shell(List.of("-v", hostDir.toAbsolutePath() + ":" + SINK + ":rw,z"), dir, script, all, args, timeout);
    }

    private Runner.Result shell(List<String> mounts, String dir, String script, Map<String, String> env, Duration timeout) {
        return shell(mounts, dir, script, env, List.of(), timeout);
    }

    private Runner.Result shell(List<String> mounts, String dir, String script, Map<String, String> env, List<String> args, Duration timeout) {
        List<String> cmd = helper(mounts, env, dir);
        cmd.addAll(List.of("--entrypoint", "sh", image, "-c", script));
        if (!args.isEmpty()) {
            // sh -c <script> <$0> <$1…>: a name for $0 first, then the positional parameters. They are
            // argv, not script text, so a file name of the agent's choosing is never read as shell.
            cmd.add("sh");
            cmd.addAll(args);
        }
        return runner.run(cmd, cwd, timeout);
    }

    /** Copies the <em>contents</em> of a host directory into {@code destRel}, which is created when missing. */
    public void copyIn(Path hostDir, String destRel) {
        shell(List.of("-v", hostDir.toAbsolutePath() + ":" + SOURCE + ":ro,z"), "",
                "set -e; mkdir -p \"$WORK/$REL\"; cp -a \"$SRC/.\" \"$WORK/$REL/\"",
                Map.of("REL", destRel, "SRC", SOURCE), LONG)
                .requireSuccess("copy into " + name);
    }

    /**
     * Copies a directory out of the volume into {@code hostDir}, which is created first so the helper gets
     * that directory and nothing above it. Symlinks are dropped on the way: what leaves the sandbox is
     * files, never a link into the host's filesystem.
     */
    public void copyOutDir(String rel, Path hostDir) {
        try {
            Files.createDirectories(hostDir);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot create " + hostDir + ": " + e.getMessage(), e);
        }
        Runner.Result r = shToHost("", hostDir,
                "if [ ! -d \"$WORK/$REL\" ]; then exit 3; fi\n"
                        + "set -e; cp -a \"$WORK/$REL/.\" \"$OUT/\"; find \"$OUT\" -type l -exec rm -f {} +",
                Map.of("REL", rel), LONG);
        if (r.exitCode() == 3) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "No directory at " + rel + " in " + name,
                    Map.of("path", rel, "volume", name));
        }
        r.requireSuccess("copy out of " + name);
    }

    /**
     * What goes into a {@link #zipTo}: the directories to take, relative to the volume root; the path
     * segments to leave out wherever they occur (wildcards allowed); and whole paths to leave out
     * (wildcards allowed, {@code *} spanning {@code /}), for the ones a segment name cannot tell apart
     * from an innocent directory of the same name.
     */
    public record Contents(List<String> roots, List<String> excludeNames, List<String> excludePaths) {}

    /**
     * Builds a zip of {@code contents} as {@code hostDir/name}, and returns the helper's result so the
     * caller can read {@code zip}'s own exit code.
     *
     * <p>The archive is made <em>inside</em> a helper container by {@code zip} from the base image, so the
     * volume's files never pass through the host on their way into it: what leaves is the finished archive.
     * What goes into it is listed by {@code find}, which does not follow symbolic links, so the rule
     * {@link #copyOutDir} follows holds here too — files leave the sandbox, links never do. Letting
     * {@code zip -r} walk the tree itself would break it twice over: it stores what a link points at, and
     * a link to its own directory ({@code ln -s . x}) makes it copy the whole subtree once per level until
     * the path gets too long. {@code note} goes in as {@code noteName}, so the archive says what it is.
     *
     * <p>The directories and patterns are the script's positional parameters, never part of its text: a
     * file name the agent chose is argv to {@code find}, not shell.
     */
    public Runner.Result zipTo(Path hostDir, String name, Contents contents, String noteName, String note) {
        try {
            Files.createDirectories(hostDir);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot create " + hostDir + ": " + e.getMessage(), e);
        }
        List<String> args = new ArrayList<>(contents.roots());
        List<String> prune = new ArrayList<>();
        for (String path : contents.excludePaths()) {
            if (!prune.isEmpty()) prune.add("-o");
            prune.addAll(List.of("-path", path));
        }
        for (String segment : contents.excludeNames()) {
            if (!prune.isEmpty()) prune.add("-o");
            prune.addAll(List.of("-name", segment));
        }
        if (!prune.isEmpty()) {
            args.add("(");
            args.addAll(prune);
            args.addAll(List.of(")", "-prune", "-o"));
        }
        args.addAll(List.of("(", "-type", "f", "-o", "-type", "d", ")", "-print"));
        return shToHost("", hostDir, """
                mkdir -p /tmp/lyngvi-export
                printf '%s' "$NOTE" > "/tmp/lyngvi-export/$NOTE_NAME"
                (cd /tmp/lyngvi-export && zip -q -X "$OUT/$NAME" "$NOTE_NAME")
                find "$@" | zip -q -X -@ "$OUT/$NAME"
                """, Map.of("NAME", name, "NOTE_NAME", noteName, "NOTE", note), args, LONG);
    }

    /**
     * A fresh scratch directory a helper container can mount. It is inside the sandbox, not in the
     * system temp area, because that is what Podman can reach on every platform (see {@code Paths.temp}).
     * The caller deletes it with {@link #deleteTemp}.
     */
    public Path tempDir(String prefix) {
        try {
            Files.createDirectories(temp);
            return Files.createTempDirectory(temp, prefix);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot create a scratch directory under " + temp + ": " + e.getMessage(), e);
        }
    }

    public void mkdirs(String rel) {
        sh("", "mkdir -p \"$WORK/$REL\"", Map.of("REL", rel), SHORT).requireSuccess("mkdir in " + name);
    }

    /**
     * The tree under {@code rel}: directories and regular files, with {@code .git} and symlinks left out —
     * nothing that points elsewhere is followed — and at most {@code maxEntries} of them.
     */
    public List<Entry> tree(String rel, int maxDepth, int maxEntries) {
        // Two plain passes rather than one with -printf: find's formatting is a GNU extension, and the
        // same script has to run on whatever the host's shell tools are in the tests.
        Runner.Result r = sh("", """
                cd "$WORK/$REL" 2>/dev/null || exit 0
                find . -maxdepth "$DEPTH" -name .git -prune -o -type d -print 2>/dev/null | sed 's|^|d |' | head -n "$MAX"
                find . -maxdepth "$DEPTH" -name .git -prune -o -type f -print 2>/dev/null | sed 's|^|f |' | head -n "$MAX"
                exit 0
                """, Map.of("REL", rel, "DEPTH", String.valueOf(maxDepth), "MAX", String.valueOf(maxEntries)), SHORT);
        if (!r.success()) return List.of();
        List<Entry> out = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            if (line.length() < 3 || line.charAt(1) != ' ') continue;
            boolean dir = line.charAt(0) == 'd';
            String path = line.substring(2);
            if (path.startsWith("./")) path = path.substring(2);
            if (path.isBlank() || path.equals(".")) continue;
            out.add(new Entry(path, dir));
        }
        out.sort(Comparator.comparing(Entry::path));
        return out;
    }

    /**
     * The bytes of one file. A file larger than {@code maxBytes} is refused before it is copied, with
     * {@code FILE_TOO_LARGE} in the exception's data, and so are symlinks and anything not a file.
     */
    public byte[] read(String rel, long maxBytes) {
        Path dir = null;
        try {
            dir = tempDir("read-");
            Runner.Result r = shToHost("", dir, """
                    if [ -L "$WORK/$REL" ] || [ ! -f "$WORK/$REL" ]; then exit 3; fi
                    if [ "$(wc -c < "$WORK/$REL" | tr -d ' ')" -gt "$MAX" ]; then exit 5; fi
                    exec cat "$WORK/$REL" > "$OUT/content"
                    """, Map.of("REL", rel, "MAX", String.valueOf(maxBytes)), LONG);
            if (r.exitCode() == 5) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, rel + " is larger than " + maxBytes + " bytes",
                        Map.of("path", rel, "limit", maxBytes, "code", "FILE_TOO_LARGE"));
            }
            if (r.exitCode() == 3) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, "No readable file at " + rel + " in " + name,
                        Map.of("path", rel, "volume", name, "code", "NOT_A_FILE"));
            }
            r.requireSuccess("read " + rel + " from " + name);
            return Files.readAllBytes(dir.resolve("content"));
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + rel + " from " + name + ": " + e.getMessage(), e);
        } finally {
            deleteTemp(dir);
        }
    }

    /** Writes one file, replacing what is there. The directory above it must exist. */
    public void write(String rel, byte[] content) {
        Path dir = null;
        try {
            dir = tempDir("write-");
            Files.write(dir.resolve("content"), content);
            Runner.Result r = shell(List.of("-v", dir.toAbsolutePath() + ":" + SOURCE + ":ro,z"), "",
                    "if [ -L \"$WORK/$REL\" ]; then exit 3; fi\nexec cat \"$SRC/content\" > \"$WORK/$REL\"",
                    Map.of("REL", rel, "SRC", SOURCE), SHORT);
            if (r.exitCode() == 3) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, rel + " is a symbolic link and is not written",
                        Map.of("path", rel, "volume", name));
            }
            r.requireSuccess("write " + rel + " in " + name);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + rel + " in " + name + ": " + e.getMessage(), e);
        } finally {
            deleteTemp(dir);
        }
    }

    /** Everything up to the image: the hardening, the volume, the extra mounts, the environment, the workdir. */
    private List<String> helper(List<String> mounts, Map<String, String> env, String dir) {
        List<String> a = new ArrayList<>(List.of("podman", "run", "--rm",
                "--network", "none",
                "--userns", userns,
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--security-opt", "label=disable",
                "-v", name + ":" + MOUNT + ":rw"));
        a.addAll(mounts);
        a.addAll(List.of("-e", "WORK=" + MOUNT));
        for (Map.Entry<String, String> e : new TreeMap<>(env).entrySet()) {
            a.addAll(List.of("-e", e.getKey() + "=" + e.getValue()));
        }
        a.addAll(List.of("-w", dir == null || dir.isBlank() ? MOUNT : MOUNT + "/" + dir));
        return a;
    }

    private static void delete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temporary file
        }
    }

    /**
     * Removes a temporary directory the host built for a volume — a staging area for a working copy, or
     * somewhere a helper wrote its result. Best effort: what is left behind is a temporary file.
     */
    public static void deleteTemp(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(SessionVolume::delete);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
