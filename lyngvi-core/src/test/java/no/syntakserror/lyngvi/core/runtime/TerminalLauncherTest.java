package no.syntakserror.lyngvi.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalLauncherTest {

    @Test
    void onMacOsWritesAScriptAndOpensItWithTerminal(@TempDir Path tmp) throws Exception {
        Platform platform = new Platform(Platform.Os.MACOS, tmp.resolve("home"), Map.of("PATH", "/usr/bin", "XDG_CACHE_HOME", tmp.resolve("cache").toString()), 501, 20);
        FakeRunner runner = new FakeRunner().ok("open -a Terminal", "");
        TerminalLauncher.Launched l = new TerminalLauncher(platform, runner).open("login-claude-code",
                List.of("podman", "run", "--rm", "-it", "img", "claude", "it's"), tmp, "lyngvi login");
        assertThat(l.script()).isRegularFile();
        assertThat(l.script().getFileName().toString()).isEqualTo("login-claude-code.command");
        String script = Files.readString(l.script());
        assertThat(script).startsWith("#!/bin/sh").contains("export PATH='/usr/bin:/opt/homebrew/bin").contains("cd '" + tmp + "'")
                .contains("'podman' 'run' '--rm' '-it' 'img' 'claude' 'it'\\''s'").contains("exit code");
        assertThat(Files.getPosixFilePermissions(l.script())).hasSize(3);
        assertThat(runner.calls().getLast()).containsExactly("open", "-a", "Terminal", l.script().toString());
    }

    @Test
    void onLinuxUsesATerminalEmulator(@TempDir Path tmp) throws Exception {
        Platform platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_CACHE_HOME", tmp.resolve("cache").toString()), 1000, 1000);
        FakeRunner runner = new FakeRunner().ok("x-terminal-emulator -e", "");
        TerminalLauncher.Launched l = new TerminalLauncher(platform, runner).open("x", List.of("sh", "-c", "true"), tmp, "t");
        assertThat(l.opener()).startsWith("x-terminal-emulator", "-e");
    }
}
