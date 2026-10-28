package no.syntakserror.lyngvi.core.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/**
 * {@link Runner} backed by {@link ProcessBuilder}.
 *
 * <p>Extends {@code PATH} with the platform's extra directories and resolves
 * the executable itself, because a GUI started from Finder inherits a PATH
 * without Homebrew or Podman.
 */
public final class ProcessRunner implements Runner {

    private final Platform platform;
    private final List<Path> searchPath;
    private volatile Optional<Path> machineSocket;

    public ProcessRunner(Platform platform) {
        this.platform = platform;
        Set<Path> dirs = new LinkedHashSet<>();
        String path = platform.env().getOrDefault("PATH", "");
        for (String p : path.split(java.io.File.pathSeparator)) {
            if (!p.isBlank()) dirs.add(Path.of(p));
        }
        dirs.addAll(platform.extraPathDirs());
        this.searchPath = List.copyOf(dirs);
    }

    /**
     * On macOS the podman CLI reaches its machine over SSH by default, which fails when {@code ~/.ssh/known_hosts}
     * is malformed. The machine also exposes an API socket; when one is available and the user has not set
     * {@code CONTAINER_HOST}/{@code CONTAINER_CONNECTION}, podman commands get {@code CONTAINER_HOST=unix://<socket>}.
     * Resolved once per runner; empty when there is no running machine.
     */
    public Optional<Path> machineSocket() {
        Optional<Path> cached = machineSocket;
        if (cached != null) return cached;
        Optional<Path> found = Optional.empty();
        if (platform.os() == Platform.Os.MACOS && !platform.env().containsKey("CONTAINER_HOST") && !platform.env().containsKey("CONTAINER_CONNECTION")) {
            try {
                machineSocket = Optional.empty(); // guard against recursion while probing
                Result r = run(List.of("podman", "machine", "inspect", "--format", "{{.State}} {{.ConnectionInfo.PodmanSocket.Path}}"), null, Duration.ofSeconds(20));
                String[] parts = r.stdout().strip().split("\\s+", 2);
                if (r.success() && parts.length == 2 && parts[0].equalsIgnoreCase("running") && Files.exists(Path.of(parts[1]))) {
                    found = Optional.of(Path.of(parts[1]));
                }
            } catch (RuntimeException e) {
                found = Optional.empty();
            }
        }
        machineSocket = found;
        return found;
    }

    private Map<String, String> podmanEnv(List<String> command) {
        if (command.isEmpty()) return Map.of();
        String exe = Path.of(command.getFirst()).getFileName().toString();
        if (!exe.equals("podman") && !exe.equals("podman-compose")) return Map.of();
        return machineSocket().map(s -> Map.of("CONTAINER_HOST", "unix://" + s)).orElse(Map.of());
    }

    /** The PATH value child processes receive. */
    public String augmentedPath() {
        StringBuilder sb = new StringBuilder();
        for (Path p : searchPath) {
            if (!sb.isEmpty()) sb.append(java.io.File.pathSeparatorChar);
            sb.append(p);
        }
        return sb.toString();
    }

    /** Finds an executable on the augmented PATH; an absolute or relative path with a separator is returned as-is if executable. */
    public Optional<Path> resolveExecutable(String command) {
        Path direct = Path.of(command);
        if (command.contains("/")) {
            return Files.isExecutable(direct) ? Optional.of(direct) : Optional.empty();
        }
        for (Path dir : searchPath) {
            Path candidate = dir.resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    @Override
    public Result run(List<String> command, Path workingDir, Duration timeout) {
        return run(command, workingDir, timeout, null);
    }

    @Override
    public Result run(List<String> command, Path workingDir, Duration timeout, Consumer<String> onLine) {
        return run(command, workingDir, timeout, Map.of(), onLine);
    }

    @Override
    public StreamHandle stream(List<String> command, Path workingDir, Map<String, String> extraEnv, String stdin, Consumer<String> onLine) {
        Process process = start(command, workingDir, extraEnv, stdin != null);
        if (stdin != null) {
            Thread.ofVirtual().start(() -> {
                try (var out = process.getOutputStream()) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the child may exit before reading everything
                }
            });
        }
        StringBuilder err = new StringBuilder();
        Thread errPump = pump(process, false, err, null);
        Thread outPump = pump(process, true, new StringBuilder(), onLine);
        return new StreamHandle() {
            private volatile boolean cancelled;

            @Override
            public void cancel() {
                cancelled = true;
                process.destroy();
                try {
                    if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public int waitFor() {
                try {
                    int exit = process.waitFor();
                    outPump.join();
                    errPump.join();
                    return exit;
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new SandboxException(ErrorCode.INTERRUPTED, "Interrupted while waiting for " + command.getFirst(), e);
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }

            @Override
            public String stderr() {
                return err.toString();
            }
        };
    }

    private Process start(List<String> command, Path workingDir, Map<String, String> extraEnv) {
        return start(command, workingDir, extraEnv, false);
    }

    private Process start(List<String> command, Path workingDir, Map<String, String> extraEnv, boolean pipeStdin) {
        if (command.isEmpty()) {
            throw new SandboxException(ErrorCode.INTERNAL, "Empty command");
        }
        Path exe = resolveExecutable(command.getFirst()).orElseThrow(() -> new SandboxException(
                ErrorCode.EXTERNAL_COMMAND_FAILED, "Command not found: " + command.getFirst(),
                Map.of("command", command.getFirst())));
        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, exe.toString());
        ProcessBuilder pb = new ProcessBuilder(resolved);
        pb.directory(workingDir == null ? null : workingDir.toFile());
        pb.environment().clear();
        pb.environment().putAll(platform.env());
        pb.environment().putAll(podmanEnv(command));
        pb.environment().putAll(extraEnv);
        pb.environment().put("PATH", augmentedPath());
        if (!pipeStdin) {
            pb.redirectInput(ProcessBuilder.Redirect.from(devNull()));
        }
        try {
            return pb.start();
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED,
                    "Cannot start " + command.getFirst() + ": " + e.getMessage(), Map.of("command", String.join(" ", command)), e);
        }
    }

    @Override
    public Result run(List<String> command, Path workingDir, Duration timeout, Map<String, String> extraEnv, Consumer<String> onLine) {
        if (command.isEmpty()) {
            throw new SandboxException(ErrorCode.INTERNAL, "Empty command");
        }
        Process process = start(command, workingDir, extraEnv);
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread errPump = pump(process, false, err, null);
        Thread outPump = pump(process, true, out, onLine);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED,
                        command.getFirst() + " timed out after " + timeout.toSeconds() + "s",
                        Map.of("command", String.join(" ", command), "timeoutSeconds", timeout.toSeconds()));
            }
            outPump.join();
            errPump.join();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SandboxException(ErrorCode.INTERRUPTED, "Interrupted while running " + command.getFirst(), e);
        }
        return new Result(List.copyOf(command), process.exitValue(), out.toString(), err.toString());
    }

    @Override
    public int interactive(List<String> command, Path workingDir) {
        Path exe = resolveExecutable(command.getFirst()).orElseThrow(() -> new SandboxException(
                ErrorCode.EXTERNAL_COMMAND_FAILED, "Command not found: " + command.getFirst(), Map.of("command", command.getFirst())));
        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, exe.toString());
        ProcessBuilder pb = new ProcessBuilder(resolved).inheritIO();
        pb.directory(workingDir == null ? null : workingDir.toFile());
        pb.environment().clear();
        pb.environment().putAll(platform.env());
        pb.environment().putAll(podmanEnv(command));
        pb.environment().put("PATH", augmentedPath());
        try {
            return pb.start().waitFor();
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, "Cannot start " + command.getFirst() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException(ErrorCode.INTERRUPTED, "Interrupted while running " + command.getFirst(), e);
        }
    }

    private static Thread pump(Process process, boolean stdout, StringBuilder into, Consumer<String> onLine) {
        Thread t = Thread.ofVirtual().unstarted(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    stdout ? process.getInputStream() : process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (into) {
                        into.append(line).append('\n');
                    }
                    if (onLine != null) onLine.accept(line);
                }
            } catch (IOException ignored) {
                // stream closed by process exit or destroy
            }
        });
        t.start();
        return t;
    }

    private static java.io.File devNull() {
        return new java.io.File(platformDevNull());
    }

    private static String platformDevNull() {
        return "/dev/null";
    }

    Platform platform() {
        return platform;
    }
}
