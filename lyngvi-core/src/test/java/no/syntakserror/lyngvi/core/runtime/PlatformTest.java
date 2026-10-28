package no.syntakserror.lyngvi.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlatformTest {

    @Test
    void detectsTheCurrentOperatingSystem() {
        Platform p = Platform.current();

        assertThat(p.os()).isIn(Platform.Os.MACOS, Platform.Os.LINUX);
        assertThat(p.home()).isDirectory();
        assertThat(p.uid()).isPositive();
        assertThat(p.gid()).isNotNegative();
    }

    @Test
    void stateDirHonoursXdgStateHome() {
        Platform p = new Platform(Platform.Os.LINUX, Path.of("/home/u"), Map.of("XDG_STATE_HOME", "/xdg/state"), 1, 1);

        assertThat(p.stateDir()).isEqualTo(Path.of("/xdg/state/lyngvi"));
    }

    @Test
    void stateDirDefaultsToLocalState() {
        Platform p = new Platform(Platform.Os.MACOS, Path.of("/Users/u"), Map.of(), 1, 1);

        assertThat(p.stateDir()).isEqualTo(Path.of("/Users/u/.local/state/lyngvi"));
    }

    @Test
    void extraPathDirsDependOnTheOs() {
        Platform mac = new Platform(Platform.Os.MACOS, Path.of("/Users/u"), Map.of(), 1, 1);
        Platform linux = new Platform(Platform.Os.LINUX, Path.of("/home/u"), Map.of(), 1, 1);

        assertThat(mac.extraPathDirs()).contains(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"), Path.of("/opt/podman/bin"));
        assertThat(linux.extraPathDirs()).contains(Path.of("/home/u/.local/bin"));
    }

    @Test
    void expandsTildeInPaths() {
        Platform p = new Platform(Platform.Os.LINUX, Path.of("/home/u"), Map.of(), 1, 1);

        assertThat(p.expand("~/.cache/lyngvi/ca")).isEqualTo(Path.of("/home/u/.cache/lyngvi/ca"));
        assertThat(p.expand("/abs")).isEqualTo(Path.of("/abs"));
    }
}
