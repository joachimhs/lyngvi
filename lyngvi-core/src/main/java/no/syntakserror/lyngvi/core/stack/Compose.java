package no.syntakserror.lyngvi.core.stack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.runtime.Runner;

/** {@code podman compose} (preferred) or {@code podman-compose}, always with {@code -p <project> -f docker-compose.yml}. */
public final class Compose {

    private static final Duration LONG = Duration.ofHours(1);
    private final Runner runner;
    private final Paths paths;
    private List<String> provider;

    public Compose(Runner runner, Paths paths) {
        this.runner = runner;
        this.paths = paths;
    }

    /** The compose provider command, detected once. */
    public Optional<List<String>> detect() {
        if (provider != null) return Optional.of(provider);
        if (works(List.of("podman", "compose", "version"))) {
            provider = List.of("podman", "compose");
        } else if (works(List.of("podman-compose", "--version"))) {
            provider = List.of("podman-compose");
        }
        return Optional.ofNullable(provider);
    }

    private boolean works(List<String> probe) {
        try {
            return runner.run(probe, paths.root(), Duration.ofSeconds(30)).success();
        } catch (SandboxException e) {
            return false;
        }
    }

    public Runner.Result up() {
        return run(List.of("up", "-d"), null).requireSuccess("compose up");
    }

    /** {@code up -d --force-recreate <service>}: recreate one service from its (re)built image. */
    public Runner.Result upService(String service) {
        return run(List.of("up", "-d", "--force-recreate", "--no-deps", service), null).requireSuccess("compose up " + service);
    }

    public Runner.Result down() {
        return run(List.of("down"), null).requireSuccess("compose down");
    }

    public Runner.Result build(List<String> services, boolean noCache, Consumer<String> onLine) {
        List<String> args = new ArrayList<>(List.of("build"));
        if (noCache) args.add("--no-cache");
        args.addAll(services);
        return run(args, onLine).requireSuccess("compose build");
    }

    private Runner.Result run(List<String> args, Consumer<String> onLine) {
        List<String> base = detect().orElseThrow(() -> new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED,
                "Neither 'podman compose' nor 'podman-compose' is available", Map.of("hint", "install podman-compose")));
        List<String> cmd = new ArrayList<>(base);
        cmd.addAll(List.of("-p", paths.projectName(), "-f", "docker-compose.yml"));
        cmd.addAll(args);
        return runner.run(cmd, paths.root(), LONG, onLine);
    }
}
