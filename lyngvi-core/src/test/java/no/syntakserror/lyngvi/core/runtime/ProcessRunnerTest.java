package no.syntakserror.lyngvi.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner(Platform.current());

    @Test
    void capturesExitCodeStdoutAndStderr(@TempDir Path dir) {
        Runner.Result r = runner.run(List.of("sh", "-c", "echo out; echo err >&2; exit 3"), dir, Duration.ofSeconds(5));

        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.stdout()).isEqualTo("out\n");
        assertThat(r.stderr()).isEqualTo("err\n");
        assertThat(r.success()).isFalse();
    }

    @Test
    void runsInTheGivenWorkingDirectory(@TempDir Path dir) throws Exception {
        Runner.Result r = runner.run(List.of("pwd"), dir, Duration.ofSeconds(5));

        assertThat(Path.of(r.stdout().strip()).toRealPath()).isEqualTo(dir.toRealPath());
    }

    @Test
    void streamsLinesAsTheyArrive(@TempDir Path dir) {
        List<String> lines = new ArrayList<>();

        Runner.Result r = runner.run(List.of("sh", "-c", "echo a; echo b"), dir, Duration.ofSeconds(5), lines::add);

        assertThat(r.exitCode()).isZero();
        assertThat(lines).containsExactly("a", "b");
    }

    @Test
    void timesOutAndKillsTheProcess(@TempDir Path dir) {
        assertThatThrownBy(() -> runner.run(List.of("sh", "-c", "sleep 30"), dir, Duration.ofMillis(300)))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.EXTERNAL_COMMAND_FAILED))
                .hasMessageContaining("timed out");
    }

    @Test
    void missingExecutableIsAnExternalCommandFailure(@TempDir Path dir) {
        assertThatThrownBy(() -> runner.run(List.of("definitely-not-a-command-xyz"), dir, Duration.ofSeconds(5)))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.EXTERNAL_COMMAND_FAILED));
    }

    @Test
    void resolvesExecutablesOnTheAugmentedPath() {
        assertThat(runner.resolveExecutable("sh")).isPresent();
        assertThat(runner.resolveExecutable("definitely-not-a-command-xyz")).isEmpty();
    }

    @Test
    void requireSuccessThrowsWithStderrInData(@TempDir Path dir) {
        Runner.Result r = runner.run(List.of("sh", "-c", "echo boom >&2; exit 2"), dir, Duration.ofSeconds(5));

        assertThatThrownBy(() -> r.requireSuccess("git something"))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> {
                    SandboxException se = (SandboxException) e;
                    assertThat(se.code()).isEqualTo(ErrorCode.EXTERNAL_COMMAND_FAILED);
                    assertThat(se.data()).containsEntry("stderr", "boom\n").containsEntry("exitCode", 2);
                });
    }

    /** A fake podman on PATH: "machine inspect" reports a running machine, "env" prints what it received. */
    private static Platform fakePodman(Path dir, String state, boolean withHost) throws Exception {
        Path bin = java.nio.file.Files.createDirectories(dir.resolve("bin"));
        Path sock = dir.resolve("api.sock");
        java.nio.file.Files.writeString(sock, "");
        java.nio.file.Files.writeString(bin.resolve("podman"), "#!/bin/sh\n"
                + "if [ \"$1\" = machine ]; then echo \"" + state + " " + sock + "\"; exit 0; fi\n"
                + "echo \"CONTAINER_HOST=${CONTAINER_HOST:-unset}\"\n");
        bin.resolve("podman").toFile().setExecutable(true);
        java.util.Map<String, String> env = new java.util.HashMap<>(java.util.Map.of("PATH", bin.toString()));
        if (withHost) env.put("CONTAINER_HOST", "ssh://elsewhere");
        return new Platform(Platform.Os.MACOS, dir, env, 501, 20);
    }

    @Test
    void onMacOsPodmanCommandsUseTheRunningMachinesApiSocket(@TempDir Path dir) throws Exception {
        ProcessRunner r = new ProcessRunner(fakePodman(dir, "running", false));
        Runner.Result out = r.run(List.of("podman", "info"), dir, Duration.ofSeconds(5));
        assertThat(out.stdout().strip()).isEqualTo("CONTAINER_HOST=unix://" + dir.resolve("api.sock"));
        // resolved once, not per command
        assertThat(r.run(List.of("podman", "ps"), dir, Duration.ofSeconds(5)).stdout()).contains("unix://");
        assertThat(r.machineSocket()).contains(dir.resolve("api.sock"));
        // non-podman commands are untouched
        assertThat(r.run(List.of("sh", "-c", "echo ${CONTAINER_HOST:-unset}"), dir, Duration.ofSeconds(5)).stdout().strip()).isEqualTo("unset");
    }

    @Test
    void anExplicitContainerHostOrAStoppedMachineIsLeftAlone(@TempDir Path dir) throws Exception {
        ProcessRunner explicit = new ProcessRunner(fakePodman(dir.resolve("a"), "running", true));
        assertThat(explicit.run(List.of("podman", "info"), dir, Duration.ofSeconds(5)).stdout().strip()).isEqualTo("CONTAINER_HOST=ssh://elsewhere");
        ProcessRunner stopped = new ProcessRunner(fakePodman(dir.resolve("b"), "stopped", false));
        assertThat(stopped.run(List.of("podman", "info"), dir, Duration.ofSeconds(5)).stdout().strip()).isEqualTo("CONTAINER_HOST=unset");
        assertThat(stopped.machineSocket()).isEmpty();
    }
}
