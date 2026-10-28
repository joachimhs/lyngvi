package no.syntakserror.lyngvi.core.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/**
 * Runs an interactive command in a new terminal window (for TUI login flows started from the GUI). The command is
 * written to a small script that carries the runner's environment (augmented PATH, the Podman machine socket);
 * macOS opens it with Terminal, Linux with the first terminal emulator found.
 */
public final class TerminalLauncher {

    /** The script that was written and the command used to open the terminal. */
    public record Launched(Path script, List<String> opener) {}

    private static final List<List<String>> LINUX_TERMINALS = List.of(
            List.of("x-terminal-emulator", "-e"), List.of("gnome-terminal", "--"), List.of("konsole", "-e"), List.of("xterm", "-e"));

    private final Platform platform;
    private final Runner runner;

    public TerminalLauncher(Platform platform, Runner runner) {
        this.platform = platform;
        this.runner = runner;
    }

    public Launched open(String name, List<String> command, Path workingDir, String title) {
        Path script = script(name, command, workingDir, title);
        List<String> opener = switch (platform.os()) {
            case MACOS -> List.of("open", "-a", "Terminal", script.toString());
            case LINUX -> linuxOpener(script);
        };
        runner.run(opener, workingDir, Duration.ofSeconds(30)).requireSuccess("open terminal");
        return new Launched(script, opener);
    }

    private List<String> linuxOpener(Path script) {
        for (List<String> t : LINUX_TERMINALS) {
            if (runner instanceof ProcessRunner pr ? pr.resolveExecutable(t.getFirst()).isPresent() : true) {
                List<String> cmd = new ArrayList<>(t);
                cmd.add(script.toString());
                return cmd;
            }
        }
        throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, "No terminal emulator found (tried x-terminal-emulator, gnome-terminal, konsole, xterm)",
                Map.of("hint", "run the login from a terminal: setup.sh agent login <id>"));
    }

    /** {@code <cache>/terminal/<name>.command}: a sh script that execs the command with the runner's environment. */
    Path script(String name, List<String> command, Path workingDir, String title) {
        StringBuilder sb = new StringBuilder("#!/bin/sh\n# Written by lyngvi; runs an interactive command in this terminal.\n");
        sb.append("printf '\\033]0;%s\\007' ").append(quote(title)).append('\n');
        StringBuilder path = new StringBuilder(platform.env().getOrDefault("PATH", "/usr/bin:/bin"));
        for (Path p : platform.extraPathDirs()) path.append(':').append(p);
        sb.append("export PATH=").append(quote(path.toString())).append('\n');
        if (runner instanceof ProcessRunner pr) {
            pr.machineSocket().ifPresent(sock -> sb.append("export CONTAINER_HOST=").append(quote("unix://" + sock)).append('\n'));
        }
        sb.append("cd ").append(quote(workingDir.toString())).append('\n');
        StringBuilder cmd = new StringBuilder();
        for (String a : command) cmd.append(cmd.isEmpty() ? "" : " ").append(quote(a));
        sb.append(cmd).append('\n');
        sb.append("status=$?\necho\necho \"lyngvi: the command finished with exit code $status; you can close this window.\"\n");
        Path dir = platform.cacheDir().resolve("terminal");
        Path script = dir.resolve(name + ".command");
        try {
            Files.createDirectories(dir);
            Files.writeString(script, sb.toString());
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + script + ": " + e.getMessage(), e);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX file system
        }
        return script;
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
