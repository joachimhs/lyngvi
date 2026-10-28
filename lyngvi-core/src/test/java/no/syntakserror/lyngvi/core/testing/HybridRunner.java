package no.syntakserror.lyngvi.core.testing;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.ProcessRunner;
import no.syntakserror.lyngvi.core.runtime.Runner;

/**
 * Test double: scripts {@code podman} through a {@link FakeRunner}, runs everything else (git, openssl,
 * adapters) for real.
 *
 * <p>Session volumes are the exception. {@code podman volume …} and the helper containers the core runs
 * in them go to {@link LocalVolumes}, which keeps each volume in a directory and runs the command inside
 * it locally — so a working copy is a real git repository in the tests too, while the command line the
 * core builds is the real one.
 */
public final class HybridRunner implements Runner {

    public final FakeRunner fake = new FakeRunner();
    public final ProcessRunner real = new ProcessRunner(Platform.current());
    public final LocalVolumes volumes;

    public HybridRunner(Path volumeRoot) {
        this.volumes = new LocalVolumes(volumeRoot);
    }

    private Runner pick(List<String> command) {
        if (!command.getFirst().equals("podman")) return real;
        if (volumes.handles(command)) {
            fake.calls().add(List.copyOf(command));
            return volumes;
        }
        return fake;
    }

    @Override
    public Result run(List<String> command, Path cwd, Duration timeout) {
        return pick(command).run(command, cwd, timeout);
    }

    @Override
    public Result run(List<String> command, Path cwd, Duration timeout, Consumer<String> onLine) {
        return pick(command).run(command, cwd, timeout, onLine);
    }

    @Override
    public Result run(List<String> command, Path cwd, Duration timeout, Map<String, String> extraEnv, Consumer<String> onLine) {
        return pick(command).run(command, cwd, timeout, extraEnv, onLine);
    }

    @Override
    public StreamHandle stream(List<String> command, Path cwd, Map<String, String> extraEnv, String stdin, Consumer<String> onLine) {
        return pick(command).stream(command, cwd, extraEnv, stdin, onLine);
    }

    @Override
    public int interactive(List<String> command, Path cwd) {
        fake.calls().add(List.copyOf(command));
        return 0;
    }
}
