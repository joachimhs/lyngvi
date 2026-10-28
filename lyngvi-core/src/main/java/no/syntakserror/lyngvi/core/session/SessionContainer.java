package no.syntakserror.lyngvi.core.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.api.StackService;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.config.Profile;
import no.syntakserror.lyngvi.core.gateway.ManifestIo;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.stack.CacheVolumes;
import no.syntakserror.lyngvi.core.stack.Podman;
import no.syntakserror.lyngvi.core.stack.SessionVolume;
import no.syntakserror.lyngvi.core.version.Version;

/** One container per session (spec §5.3): builds the {@code podman run} line and drives the container. */
public final class SessionContainer {

    private final Sandbox sandbox;
    private final Podman podman;

    public SessionContainer(Sandbox sandbox) {
        this.sandbox = sandbox;
        this.podman = new Podman(sandbox.runner(), sandbox.paths().root());
    }

    public static String containerName(Paths paths, Conversation c) {
        return "lyngvi-" + paths.projectName() + "-" + c.shortId();
    }

    private String prefix() {
        return sandbox.config().subnetPrefix().orElseThrow(() -> new SandboxException(ErrorCode.VALIDATION_FAILED,
                "No subnet allocated; run 'setup.sh rebuild-config' first"));
    }

    private String gatewayIp(Manifest manifest) {
        return prefix() + "." + manifest.ipSuffix();
    }

    private String proxyUrl() {
        return "http://" + prefix() + ".5:3128";
    }

    /**
     * True when the container was created against a different subnet than the sandbox uses now.
     * Podman freezes the proxy and gateway addresses into the container at creation, so a plain
     * restart would keep pointing at a subnet that no longer exists.
     */
    private boolean addressedToAnOldSubnet(Conversation c) {
        String proxy = podman.containerEnv(c.container()).get("HTTPS_PROXY");
        return proxy != null && !proxy.equals(proxyUrl());
    }

    /**
     * True when the container was created with other shared caches than the session asks for now. Mounts
     * are frozen at creation, so a changed setting can only take effect in a new container — the same
     * reason {@link #addressedToAnOldSubnet} exists. The environment carries the rendered mode so the
     * comparison needs nothing but the container itself.
     */
    private boolean builtWithOtherCaches(Conversation c) {
        String was = podman.containerEnv(c.container()).get("LYNGVI_CACHE");
        return was != null && !was.equals(cacheStamp(c));
    }

    /**
     * True when the container predates the session volumes and still binds {@code .sessions/<id>/work}
     * from the host. Restarting it after the working copy has been moved into the volume would give the
     * agent an empty directory — podman recreates the missing bind source — while its work sat in a volume
     * the container does not mount. So it is rebuilt instead. An empty answer means we could not ask, and
     * then nothing is assumed.
     */
    private boolean builtBeforeTheVolume(Conversation c) {
        List<String> mounts = podman.containerMounts(c.container());
        return !mounts.isEmpty() && !mounts.contains(SessionVolume.nameFor(c.container()));
    }

    /** The profile as this session sees it: its own cache mode wins over the sandbox's. */
    private Profile cacheProfile(Conversation c) {
        Profile profile = sandbox.profile();
        if (c.cacheMode() == null || c.cacheMode().isBlank() || c.cacheMode().equals(profile.cache().mode())) {
            return profile;
        }
        return new Profile(profile.schemaVersion(), profile.name(), profile.label(), profile.language(), profile.agents(),
                profile.toolchains(), profile.egress(), profile.localModels(), profile.mirrors(), profile.ca(),
                profile.gateway(), profile.git(), profile.resources(), profile.resourcePresets(), profile.copy(),
                profile.session(), new Profile.Cache(c.cacheMode(), profile.cache().paths()));
    }

    /** The shared cache volumes this session mounts, created and labelled before the container needs them. */
    private void ensureCacheVolumes(Conversation c) {
        CacheVolumes.names(cacheProfile(c), sandbox.paths().projectName())
                .forEach((volume, id) -> sandbox.sessionVolumes().ensureCache(volume, id));
    }

    /** What the session's caches amount to, as one value a container can be compared against. */
    private String cacheStamp(Conversation c) {
        Profile profile = cacheProfile(c);
        return profile.cache().mode() + ":" + String.join(",", CacheVolumes.names(profile, sandbox.paths().projectName()).values());
    }

    public String image(Conversation c) {
        String version = Version.current().toString();
        if (Files.isRegularFile(sandbox.paths().agentDir(c.agent()).resolve("image.Containerfile"))) {
            return "localhost/lyngvi-" + c.agent() + ":" + version;
        }
        return StackService.baseImage();
    }

    /** Everything after {@code podman}: the full {@code run} line, deterministic for golden tests. */
    public List<String> runArgs(Conversation c) {
        Paths p = sandbox.paths();
        Manifest manifest = ManifestIo.read(p.gatewayManifest());
        String gw = gatewayIp(manifest);
        List<String> a = new ArrayList<>(List.of("podman", "run", "-d",
                "--name", c.container(),
                "--label", "lyngvi.session=" + c.id(),
                "--label", "lyngvi.project=" + p.projectName(),
                "--hostname", "lyngvi",
                "--network", p.projectName() + "_internal"));
        for (String host : manifest.hostnames()) {
            a.addAll(List.of("--add-host", host + ":" + gw));
        }
        a.addAll(List.of("--add-host", "gateway:" + gw));
        if (sandbox.platform().os() == Platform.Os.LINUX) {
            a.addAll(List.of("--add-host", "host.containers.internal:host-gateway"));
        }
        a.addAll(List.of(
                "--userns", "keep-id:uid=" + sandbox.config().uid() + ",gid=" + sandbox.config().gid(),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--security-opt", "label=disable",
                "--memory", c.resources().mem(),
                "--memory-swap", c.resources().memswap(),
                "--cpus", c.resources().cpus(),
                "--pids-limit", c.resources().pids(),
                "--env-file", p.sessionEnvFile(c.id()).toString(),
                // The whole of /work is the session's volume: the working copies and the output the agent
                // writes stay in it and never touch the host filesystem (spec §5.1).
                "-v", SessionVolume.nameFor(c.container()) + ":" + SessionVolume.MOUNT + ":rw",
                "-v", p.agentsMd() + ":/work/AGENTS.md:ro,z",
                "-v", p.sessionAttachments(c.id()) + ":/work/_attachments:ro,z"));
        a.addAll(CacheVolumes.mounts(cacheProfile(c), p.projectName()));
        if (Files.isRegularFile(p.gatewayCert())) {
            a.addAll(List.of("-v", p.gatewayCert() + ":/etc/lyngvi/gateway.crt:ro,z"));
        }
        Optional<AgentDescriptor> agent = AgentDescriptor.find(p.agents(), c.agent());
        if (agent.isPresent() && agent.get().configDir() != null) {
            a.addAll(List.of("-v", p.agentState(c.agent()) + ":" + agent.get().configDir() + ":rw,z"));
        }
        // Credentials the human stored as environment variables (e.g. a long-lived token) for this agent only.
        Path agentEnv = p.agentState(c.agent()).resolve("env");
        if (Files.isRegularFile(agentEnv)) {
            a.addAll(List.of("--env-file", agentEnv.toString()));
        }
        a.addAll(List.of("-w", "/work", image(c), "sleep", "infinity"));
        return a;
    }

    /** The {@code --env-file} content: proxy, gateway, session id, mirrors. */
    public String envFile(Conversation c) {
        Manifest manifest = ManifestIo.read(sandbox.paths().gatewayManifest());
        String proxy = proxyUrl();
        String gw = gatewayIp(manifest);
        List<String> noProxy = new ArrayList<>(List.of("localhost", "127.0.0.1", gw, "gateway"));
        noProxy.addAll(manifest.hostnames());
        StringBuilder env = new StringBuilder();
        env.append("# Rendered by lyngvi for session ").append(c.id()).append("; do not edit.\n");
        for (String k : List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")) {
            env.append(k).append('=').append(proxy).append('\n');
        }
        env.append("NO_PROXY=").append(String.join(",", noProxy)).append('\n');
        env.append("no_proxy=").append(String.join(",", noProxy)).append('\n');
        env.append("JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=").append(prefix()).append(".5 -Dhttps.proxyPort=3128 -Dhttp.proxyHost=")
           .append(prefix()).append(".5 -Dhttp.proxyPort=3128 -Dhttp.nonProxyHosts=").append(String.join("|", noProxy))
           .append(" -Dcom.sun.jndi.dns.timeout=1000 -Dcom.sun.jndi.dns.connect.timeout=1000 -Dsun.net.inetaddr.ttl=10")
           .append(" -Dnetworkaddress.cache.ttl=10 -Dnetworkaddress.cache.negative.ttl=1\n");
        env.append("GATEWAY_URL=http://").append(gw).append(":8080\n");
        for (Instance i : manifest.instances()) {
            env.append(i.urlEnvName()).append("=http://").append(gw).append(":8080/").append(i.id()).append('\n');
        }
        env.append("LYNGVI_SESSION=").append(c.id()).append('\n');
        // Not for the agent, but for us: it is how a running container says which caches it was built with.
        env.append("LYNGVI_CACHE=").append(cacheStamp(c)).append('\n');
        AgentDescriptor.find(sandbox.paths().agents(), c.agent()).ifPresent(d -> d.env().forEach((k, v) -> env.append(k).append('=').append(v).append('\n')));
        Profile.Mirrors m = sandbox.profile().mirrors();
        if (m.npm() != null) env.append("LYNGVI_NPM_MIRROR=").append(m.npm()).append('\n');
        if (m.maven() != null) env.append("LYNGVI_MAVEN_MIRROR=").append(m.maven()).append('\n');
        if (m.pypi() != null) env.append("LYNGVI_PYPI_MIRROR=").append(m.pypi()).append('\n');
        env.append("LANG=C.UTF-8\nTERM=xterm-256color\n");
        return env.toString();
    }

    public Optional<Podman.Container> state(Conversation c) {
        return podman.find(c.container());
    }

    /** Creates or starts the container; a running one is left alone. Returns the new state. */
    public String start(Conversation c) {
        Optional<Podman.Container> existing = state(c);
        if (existing.isPresent() && existing.get().state().equals("running")) {
            return "running";
        }
        Path session = sandbox.paths().sessionMeta(c.id());
        sandbox.sessionService().volume(c);  // created, and filled from a pre-volume working copy, when needed
        try {
            Files.createDirectories(sandbox.paths().sessionAttachments(c.id()));
            Files.createDirectories(sandbox.paths().agentState(c.agent()));
            Files.writeString(sandbox.paths().sessionEnvFile(c.id()), envFile(c));
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot prepare session directory " + session + ": " + e.getMessage(), e);
        }
        if (existing.isPresent() && !addressedToAnOldSubnet(c) && !builtWithOtherCaches(c) && !builtBeforeTheVolume(c)) {
            podman.start(c.container());
        } else {
            if (existing.isPresent()) podman.removeForce(c.container());
            ensureCacheVolumes(c);
            podman.run(runArgs(c));
        }
        return "running";
    }

    public void stop(Conversation c) {
        Optional<Podman.Container> existing = state(c);
        if (existing.isPresent() && existing.get().state().equals("running")) {
            podman.stop(c.container());
        }
    }

    public void remove(Conversation c) {
        podman.removeForce(c.container());
    }

    public int shell(Conversation c) {
        return podman.execInteractive(c.container(), "/work", List.of("bash"));
    }
}
