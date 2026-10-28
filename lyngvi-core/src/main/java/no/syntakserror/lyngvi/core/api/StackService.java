package no.syntakserror.lyngvi.core.api;

import java.nio.file.Files;
import java.nio.file.Path;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.stack.BlockedRequest;
import no.syntakserror.lyngvi.core.stack.BlockedRequests;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.stack.Compose;
import no.syntakserror.lyngvi.core.stack.MachineInfo;
import no.syntakserror.lyngvi.core.stack.Podman;
import no.syntakserror.lyngvi.core.stack.SquidLogParser;
import no.syntakserror.lyngvi.core.stack.StackStatus;
import no.syntakserror.lyngvi.core.session.Locks;
import no.syntakserror.lyngvi.core.version.Version;

/** The shared stack (proxy and gateway): up, down, status, build, logs, proxy reload, blocked requests. */
public final class StackService {

    public static final List<String> SERVICES = List.of("proxy", "gateway");

    private final Sandbox sandbox;
    private final Podman podman;
    private final Compose compose;

    StackService(Sandbox sandbox) {
        this.sandbox = sandbox;
        this.podman = new Podman(sandbox.runner(), sandbox.paths().root());
        this.compose = new Compose(sandbox.runner(), sandbox.paths());
    }

    public static String baseImage() {
        return "localhost/lyngvi-base:" + Version.current();
    }

    public static String agentImage(String agentId) {
        return "localhost/lyngvi-" + agentId + ":" + Version.current();
    }

    public void requireRendered() {
        Paths p = sandbox.paths();
        if (!Files.isRegularFile(p.compose()) || !Files.isRegularFile(p.squidConf()) || !Files.isRegularFile(p.allowedDomains())) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "Generated files are missing; run 'setup.sh rebuild-config' first", Map.of("dir", p.root().toString()));
        }
    }

    public StackStatus up() {
        requireRendered();
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            compose.up();
        }
        return status();
    }

    public StackStatus down() {
        requireRendered();
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            compose.down();
        }
        return status();
    }

    public StackStatus status() {
        String project = sandbox.paths().projectName();
        List<Podman.Container> containers = podman.ps(project + "-");
        List<StackStatus.Service> services = new ArrayList<>();
        for (String name : SERVICES) {
            String container = project + "-" + name;
            Podman.Container c = containers.stream().filter(x -> x.name().equals(container)).findFirst().orElse(null);
            if (c == null) {
                if (name.equals("gateway") && !Files.isRegularFile(sandbox.paths().gatewayContainerfile())) continue;
                services.add(new StackStatus.Service(name, container, "", ""));
            } else {
                services.add(new StackStatus.Service(name, container, c.state(), c.health()));
            }
        }
        return new StackStatus(sandbox.config().subnetPrefix().orElse(null), services);
    }

    /** Builds the base image (and the gateway image when it exists). Returns the base image reference. */
    public String build(boolean noCache, Consumer<String> onLine) {
        requireRendered();
        String image = baseImage();
        podman.build(sandbox.paths().containerfile().getFileName(), image, noCache, onLine).requireSuccess("podman build");
        for (AgentDescriptor agent : AgentDescriptor.readAll(sandbox.paths().agents())) {
            if (!agent.hasImage() || !sandbox.profile().agents().enabled().contains(agent.id())) continue;
            Path cf = sandbox.paths().agentContainerfile(agent.id());
            if (!Files.isRegularFile(cf)) continue;
            podman.build(sandbox.paths().root().relativize(cf), agentImage(agent.id()), noCache, onLine).requireSuccess("podman build " + agent.id());
        }
        if (Files.isRegularFile(sandbox.paths().gatewayContainerfile())) {
            compose.build(List.of("gateway"), noCache, onLine);
        }
        return image;
    }

    public boolean baseImageExists() {
        return podman.imageExists(baseImage());
    }

    /** The toolchains the built base image carries (from its label); empty when there is no image for this version. */
    public java.util.Optional<List<String>> imageToolchains() {
        if (!baseImageExists()) return java.util.Optional.empty();
        String label = podman.imageLabel(baseImage(), no.syntakserror.lyngvi.core.render.ContainerfileRenderer.TOOLCHAINS_LABEL).orElse("");
        return java.util.Optional.of(java.util.Arrays.stream(label.split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList());
    }

    public void logs(String service, boolean follow, int tail, Consumer<String> onLine) {
        if (!SERVICES.contains(service)) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Unknown service '" + service + "' (proxy or gateway)", Map.of("service", service));
        }
        podman.logs(sandbox.paths().projectName() + "-" + service, follow, tail, onLine).requireSuccess("podman logs");
    }

    /** Starts following a service log; the handle cancels the stream. For GUIs and other long-lived callers. */
    public Runner.StreamHandle streamLogs(String service, int tail, Consumer<String> onLine) {
        if (!SERVICES.contains(service)) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Unknown service '" + service + "' (proxy or gateway)", Map.of("service", service));
        }
        return podman.streamLogs(sandbox.paths().projectName() + "-" + service, tail, onLine);
    }

    /** Starts following denied requests; the handle cancels the stream. */
    public Runner.StreamHandle followBlocked(int tail, Consumer<SquidLogParser.Denied> onDenied) {
        return podman.streamLogs(sandbox.paths().projectName() + "-proxy", tail, line -> SquidLogParser.parse(line).ifPresent(onDenied));
    }

    /** Hot-reloads Squid's ACLs. Returns false when the proxy is not running (the change applies at next start). */
    public boolean reloadProxy() {
        String container = sandbox.paths().projectName() + "-proxy";
        boolean running = podman.ps(container).stream().anyMatch(c -> c.name().equals(container) && c.state().equals("running"));
        if (!running) return false;
        Runner.Result r = podman.exec(container, List.of("squid", "-k", "reconfigure"));
        if (!r.success()) {
            // Older configs without a pid file: signal the squid process directly (PID 1 is the image's entrypoint shell).
            podman.exec(container, List.of("sh", "-c", "kill -HUP $(pgrep -x squid | sort -n | head -1)")).requireSuccess("squid reload");
        }
        return true;
    }

    /** Denied requests from the proxy log, aggregated per host. */
    public List<BlockedRequest> blocked(int tail) {
        BlockedRequests agg = new BlockedRequests();
        follow(false, tail, agg::add);
        return agg.snapshot();
    }

    /** Streams denied requests; with {@code follow} the call blocks until the log ends or the process is interrupted. */
    public void follow(boolean follow, int tail, Consumer<SquidLogParser.Denied> onDenied) {
        String container = sandbox.paths().projectName() + "-proxy";
        podman.logs(container, follow, tail, line -> SquidLogParser.parse(line).ifPresent(onDenied));
    }

    public MachineInfo machine() {
        return MachineInfo.read(sandbox.platform(), sandbox.runner());
    }
}
