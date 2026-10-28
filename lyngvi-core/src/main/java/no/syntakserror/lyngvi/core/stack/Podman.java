package no.syntakserror.lyngvi.core.stack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.runtime.Runner;

/** The {@code podman} commands the core needs, through {@link Runner} so tests can script them. */
public final class Podman {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration SHORT = Duration.ofSeconds(30);
    private static final Duration LONG = Duration.ofHours(2);

    /** A container as {@code podman ps} reports it. */
    public record Container(String name, String state, String status, String image) {
        /** {@code healthy}, {@code unhealthy}, {@code starting} or empty, parsed from the status text. */
        public String health() {
            int open = status.lastIndexOf('(');
            int close = status.lastIndexOf(')');
            if (open >= 0 && close > open) {
                String h = status.substring(open + 1, close).toLowerCase();
                if (h.equals("healthy") || h.equals("unhealthy") || h.equals("starting")) return h;
            }
            return "";
        }
    }

    private final Runner runner;
    private final Path cwd;

    public Podman(Runner runner, Path cwd) {
        this.runner = runner;
        this.cwd = cwd;
    }

    public List<Container> ps(String namePrefix) {
        Runner.Result r = runner.run(List.of("podman", "ps", "-a", "--format", "json", "--filter", "name=^" + namePrefix), cwd, SHORT);
        List<Container> out = new ArrayList<>();
        if (!r.success() || r.stdout().isBlank()) return out;
        try {
            for (JsonNode c : JSON.readTree(r.stdout())) {
                String name = c.path("Names").isArray() && !c.path("Names").isEmpty() ? c.path("Names").get(0).asText() : c.path("Names").asText("");
                out.add(new Container(name, c.path("State").asText(""), c.path("Status").asText(""), c.path("Image").asText("")));
            }
        } catch (IOException ignored) {
            // unparseable: report nothing
        }
        return out;
    }

    /** A label of a local image; empty when the image or the label is absent. */
    public java.util.Optional<String> imageLabel(String ref, String label) {
        try {
            Runner.Result r = runner.run(List.of("podman", "image", "inspect", "--format", "{{index .Config.Labels \"" + label + "\"}}", ref), cwd, SHORT);
            if (!r.success()) return java.util.Optional.empty();
            String v = r.stdout().strip();
            return v.isEmpty() || v.equals("<no value>") ? java.util.Optional.empty() : java.util.Optional.of(v);
        } catch (SandboxException e) {
            return java.util.Optional.empty();
        }
    }

    public boolean imageExists(String ref) {
        try {
            return runner.run(List.of("podman", "image", "exists", ref), cwd, SHORT).success();
        } catch (SandboxException e) {
            return false;
        }
    }

    public Runner.Result exec(String container, List<String> command) {
        List<String> cmd = new ArrayList<>(List.of("podman", "exec", container));
        cmd.addAll(command);
        return runner.run(cmd, cwd, SHORT);
    }

    public Runner.Result logs(String container, boolean follow, int tail, Consumer<String> onLine) {
        List<String> cmd = new ArrayList<>(List.of("podman", "logs"));
        if (tail >= 0) cmd.addAll(List.of("--tail", String.valueOf(tail)));
        if (follow) cmd.add("-f");
        cmd.add(container);
        return runner.run(cmd, cwd, follow ? LONG : SHORT, onLine);
    }

    /** {@code podman logs -f --tail n}: streams until cancelled. */
    public Runner.StreamHandle streamLogs(String container, int tail, Consumer<String> onLine) {
        List<String> cmd = new ArrayList<>(List.of("podman", "logs"));
        if (tail >= 0) cmd.addAll(List.of("--tail", String.valueOf(tail)));
        cmd.add("-f");
        cmd.add(container);
        return runner.stream(cmd, cwd, java.util.Map.of(), onLine);
    }

    /** {@code podman run ...}; {@code args} are everything after {@code podman}. */
    public Runner.Result run(List<String> args) {
        return runner.run(args, cwd, Duration.ofMinutes(5)).requireSuccess("podman run");
    }

    public Runner.Result restart(String container) {
        return runner.run(List.of("podman", "restart", container), cwd, Duration.ofMinutes(2));
    }

    public Runner.Result start(String container) {
        return runner.run(List.of("podman", "start", container), cwd, Duration.ofMinutes(2)).requireSuccess("podman start");
    }

    public Runner.Result stop(String container) {
        return runner.run(List.of("podman", "stop", container), cwd, Duration.ofMinutes(2)).requireSuccess("podman stop");
    }

    public void removeForce(String container) {
        try {
            runner.run(List.of("podman", "rm", "-f", container), cwd, Duration.ofMinutes(2));
        } catch (SandboxException ignored) {
            // podman unavailable: nothing to remove
        }
    }

    /**
     * The environment baked into a container when it was created. Podman freezes {@code --env-file}
     * contents at creation, so this is what the container really runs with, not what the file says now.
     * Empty when the container is absent or Podman is unavailable.
     */
    public Map<String, String> containerEnv(String container) {
        Runner.Result r;
        try {
            r = runner.run(List.of("podman", "inspect", "--format", "{{range .Config.Env}}{{println .}}{{end}}", container), cwd, SHORT);
        } catch (SandboxException e) {
            return Map.of();
        }
        if (!r.success()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : r.stdout().split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return out;
    }

    /**
     * What a container has mounted: the volume name where there is one, the host path otherwise. Empty
     * when the container is absent or Podman cannot be asked — so a caller must read «empty» as «do not
     * know», never as «nothing mounted».
     */
    public List<String> containerMounts(String container) {
        Runner.Result r;
        try {
            r = runner.run(List.of("podman", "inspect", "--format",
                    "{{range .Mounts}}{{if .Name}}{{.Name}}{{else}}{{.Source}}{{end}}{{println}}{{end}}", container), cwd, SHORT);
        } catch (SandboxException e) {
            return List.of();
        }
        if (!r.success()) return List.of();
        return r.stdout().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** The container with exactly this name, if any. */
    public java.util.Optional<Container> find(String container) {
        return ps(container).stream().filter(c -> c.name().equals(container)).findFirst();
    }

    public int execInteractive(String container, String workdir, List<String> command) {
        List<String> cmd = new ArrayList<>(List.of("podman", "exec", "-it", "-w", workdir, container));
        cmd.addAll(command);
        return runner.interactive(cmd, cwd);
    }

    public Runner.Result build(Path containerfile, String tag, boolean noCache, Consumer<String> onLine) {
        List<String> cmd = new ArrayList<>(List.of("podman", "build"));
        if (noCache) cmd.add("--no-cache");
        cmd.addAll(List.of("-t", tag, "-f", containerfile.toString(), "."));
        return runner.run(cmd, cwd, LONG, onLine);
    }
}
