package no.syntakserror.lyngvi.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.api.InitOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.render.Renderer;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionContainerTest {

    /** The exact command `Podman.containerMounts` builds, so the double can answer that one and not the others. */
    private static final String MOUNTS_FORMAT =
            "podman inspect --format {{range .Mounts}}{{if .Name}}{{.Name}}{{else}}{{.Source}}{{end}}{{println}}{{end}}";

    private static Platform platform(Path tmp, Platform.Os os) {
        return new Platform(os, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
    }

    private static Sandbox sandbox(Path tmp, Platform.Os os) {
        Platform platform = platform(tmp, os);
        FakeRunner runner = new FakeRunner().ok("podman network ls", "[]").ok("openssl req", "").ok("openssl x509", "");
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(), Optional.empty(), false), platform, runner);
        Sandbox sb = Sandbox.open(tmp.resolve("sb"), platform, runner);
        new Renderer(sb).render();
        try {
            // The scripted openssl writes nothing; the run line mounts the certificate only when the file exists.
            Files.writeString(sb.paths().gatewayCert(), "fake");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return sb;
    }

    private static Conversation conversation(Sandbox sb) {
        return new Conversation(2, "6f1c2a3b-0000-4000-8000-000000000001", "Fix login", 1757000000000L, 1757000000000L, 1757000000000L,
                "claude-code", null, null, List.of("api", "web"), new ResourceLimits("8g", "12g", "4.0", "2048"),
                "lyngvi-sb-6f1c2a3b",
                Map.of("api", new Conversation.RepoBase("a", "main", "a", false, History.SHALLOW, 100),
                       "web", new Conversation.RepoBase("b", "main", "b", false, History.NONE, null)),
                List.of(), List.of());
    }

    @Test
    void runArgumentsMountOnlyThisSessionAndApplyTheHardening(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, Platform.Os.MACOS);
        Conversation c = conversation(sb);
        Files.writeString(sb.paths().gatewayManifest(), """
                {"schemaVersion":2,"ipSuffix":10,"instances":[{"id":"docs","plugin":"http","label":"Docs","hostname":"docs.internal.example",
                 "hostAliases":["docs"],"baseUrlEnv":"D","secret":{"env":"T","scheme":"bearer"},"allowlist":{"file":"docs.txt"},"capabilities":["read"],"config":{}}]}
                """);

        SessionContainer container = new SessionContainer(sb);
        List<String> args = container.runArgs(c);

        String expected = Files.readString(Path.of("src/test/resources/golden/m3/podman-run.txt"))
                .replace("{{SB}}", sb.paths().root().toString())
                .replace("{{VERSION}}", no.syntakserror.lyngvi.core.version.Version.current().toString());
        assertThat(String.join("\n", args)).isEqualTo(expected.strip());
        // /work is the session's volume, and nothing writable under it comes from the host: the two other
        // mounts are read-only, and the attachments are the only host directory the container sees at all.
        assertThat(args.stream().filter(a -> a.contains(":/work")).toList())
                .containsExactly(c.container() + "-work:/work:rw",
                        sb.paths().agentsMd() + ":/work/AGENTS.md:ro,z",
                        sb.paths().sessionAttachments(c.id()) + ":/work/_attachments:ro,z");
        assertThat(args.stream().filter(a -> a.startsWith(sb.paths().root().toString()) && a.endsWith(":rw,z")).toList())
                .allMatch(a -> a.contains("/state/agents/"));
    }

    @Test
    void envFileCarriesProxyGatewayAndMirrorSettings(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, Platform.Os.LINUX);
        Files.writeString(sb.paths().profileJson(), Files.readString(sb.paths().profileJson()).replace("\"npm\": null", "\"npm\": \"https://mirror.example/npm/\""));
        sb.reload();
        Conversation c = conversation(sb);

        String env = new SessionContainer(sb).envFile(c);

        assertThat(env).contains("HTTP_PROXY=http://172.31.240.5:3128", "https_proxy=http://172.31.240.5:3128",
                "NO_PROXY=localhost,127.0.0.1,172.31.240.10,gateway", "GATEWAY_URL=http://172.31.240.10:8080",
                "LYNGVI_SESSION=" + c.id(), "LYNGVI_NPM_MIRROR=https://mirror.example/npm/",
                "JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=172.31.240.5 -Dhttps.proxyPort=3128 -Dhttp.proxyHost=172.31.240.5 -Dhttp.proxyPort=3128 -Dhttp.nonProxyHosts=localhost|127.0.0.1|172.31.240.10|gateway");
        assertThat(env).doesNotContain("LYNGVI_MAVEN_MIRROR");
    }

    @Test
    void linuxAddsTheHostGatewayAlias(@TempDir Path tmp) {
        Sandbox sb = sandbox(tmp, Platform.Os.LINUX);

        List<String> args = new SessionContainer(sb).runArgs(conversation(sb));

        assertThat(args).containsSequence("--add-host", "host.containers.internal:host-gateway");
    }

    @Test
    void usesTheAgentImageWhenTheAgentHasAnImageFragment(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, Platform.Os.LINUX);
        Path agent = Files.createDirectories(sb.paths().agentDir("claude-code"));
        Files.writeString(agent.resolve("agent.json"), "{\"id\":\"claude-code\",\"configDir\":\"/home/agent/.claude\",\"egress\":[]}");
        Files.writeString(agent.resolve("image.Containerfile"), "# agent\n");

        List<String> args = new SessionContainer(sb).runArgs(conversation(sb));

        assertThat(args).contains("localhost/lyngvi-claude-code:" + no.syntakserror.lyngvi.core.version.Version.current());
        assertThat(args).contains(sb.paths().agentState("claude-code") + ":/home/agent/.claude:rw,z");
    }

    @Test
    void recreatesAnExistingContainerWhoseProxySettingsPointAtAnOldSubnet(@TempDir Path tmp) {
        Sandbox rendered = sandbox(tmp, Platform.Os.LINUX);
        Conversation c = conversation(rendered);
        FakeRunner runner = new FakeRunner()
                .ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"exited\",\"Status\":\"Exited (0)\",\"Image\":\"img\"}]")
                .ok("podman volume", "").ok("podman image exists", "")
                .ok("podman inspect", "HTTPS_PROXY=http://172.31.243.5:3128\nGATEWAY_URL=http://172.31.243.10:8080\n")
                .ok("podman rm", "")
                .ok("podman run", "")
                .ok("podman start", "");
        Sandbox sb = Sandbox.open(tmp.resolve("sb"), platform(tmp, Platform.Os.LINUX), runner);

        new SessionContainer(sb).start(c);

        assertThat(runner.calls()).anyMatch(call -> call.size() > 3 && call.get(1).equals("rm") && call.contains(c.container()));
        assertThat(runner.calls()).anyMatch(call -> call.size() > 1 && call.get(1).equals("run"));
        assertThat(runner.calls()).noneMatch(call -> call.size() > 1 && call.get(1).equals("start"));
    }

    /** The profile names the caches; only those whose toolchain this image has are mounted. */
    @Test
    void sharedCachesAreMountedPerModeAndOnlyForToolchainsTheImageHas(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, Platform.Os.LINUX);
        Conversation c = conversation(sb);

        // The default profile shares npm, pip and the two Go caches, and has only the node22 toolchain.
        assertThat(new SessionContainer(sb).runArgs(c)).contains("lyngvi-sb-cache-npm:/home/agent/.npm:rw")
                .doesNotContain("lyngvi-sb-cache-pip:/home/agent/.cache/pip:rw");

        Files.writeString(sb.paths().profileJson(), Files.readString(sb.paths().profileJson())
                .replace("\"toolchains\": [\"node22\"]", "\"toolchains\": [\"node22\", \"maven\"]")
                .replace("[\"npm\", \"pip\", \"go-mod\", \"go-build\"]", "[\"npm\", \"m2\"]"));
        sb.reload();
        assertThat(new SessionContainer(sb).runArgs(c))
                .contains("lyngvi-sb-cache-npm:/home/agent/.npm:rw", "lyngvi-sb-cache-m2:/home/agent/.m2/repository:rw");

        // readonly puts the overlay flag on, so nothing a session downloads outlives its container
        Files.writeString(sb.paths().profileJson(), Files.readString(sb.paths().profileJson()).replace("\"mode\": \"shared\"", "\"mode\": \"readonly\""));
        sb.reload();
        assertThat(new SessionContainer(sb).runArgs(c)).contains("lyngvi-sb-cache-npm:/home/agent/.npm:O");

        Files.writeString(sb.paths().profileJson(), Files.readString(sb.paths().profileJson()).replace("\"mode\": \"readonly\"", "\"mode\": \"off\""));
        sb.reload();
        assertThat(new SessionContainer(sb).runArgs(c)).noneMatch(a -> a.contains("lyngvi-sb-cache-"));
    }

    /** A session may say «not my caches», and that beats the profile. */
    @Test
    void aSessionCanOptOutOfTheSharedCaches(@TempDir Path tmp) {
        Sandbox sb = sandbox(tmp, Platform.Os.LINUX);
        Conversation c = conversation(sb);
        Conversation untrusted = new Conversation(c.schemaVersion(), c.id(), c.title(), c.createdAt(), c.updatedAt(), c.lastActiveAt(),
                c.agent(), c.agentSessionId(), c.model(), c.repos(), c.resources(), c.container(), c.base(), c.deliveries(),
                c.messages(), "off");

        assertThat(new SessionContainer(sb).runArgs(c)).contains("lyngvi-sb-cache-npm:/home/agent/.npm:rw");
        assertThat(new SessionContainer(sb).runArgs(untrusted)).noneMatch(a -> a.contains("lyngvi-sb-cache-"));
        assertThat(new SessionContainer(sb).envFile(untrusted)).contains("LYNGVI_CACHE=off:");
    }

    /** Mounts are frozen at creation, so a container built with other caches is replaced, not restarted. */
    @Test
    void aContainerBuiltWithOtherCachesIsRecreated(@TempDir Path tmp) {
        Sandbox rendered = sandbox(tmp, Platform.Os.LINUX);
        Conversation c = conversation(rendered);
        FakeRunner runner = new FakeRunner()
                .ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"exited\",\"Status\":\"Exited (0)\",\"Image\":\"img\"}]")
                .ok("podman volume", "").on("podman volume exists lyngvi-sb-cache-npm", 1, "", "")
                .ok("podman image exists", "")
                .ok("podman inspect", "HTTPS_PROXY=http://172.31.240.5:3128\nLYNGVI_CACHE=off:\n")
                .ok("podman rm", "").ok("podman run", "").ok("podman start", "");
        Sandbox sb = Sandbox.open(tmp.resolve("sb"), platform(tmp, Platform.Os.LINUX), runner);

        new SessionContainer(sb).start(c);

        assertThat(runner.calls()).anyMatch(call -> call.size() > 1 && call.get(1).equals("run"));
        assertThat(runner.calls()).noneMatch(call -> call.size() > 1 && call.get(1).equals("start"));
        // and the volume it is about to mount was created with our labels rather than by podman on the fly
        assertThat(runner.calls()).anyMatch(call -> call.contains("volume") && call.contains("create")
                && call.contains("lyngvi.cache=npm"));
    }

    /**
     * The upgrade case: a container from before the session volumes binds the working copy from the host.
     * Once that copy has moved into the volume, restarting such a container would show the agent an empty
     * directory, so it is replaced.
     */
    @Test
    void aContainerFromBeforeTheVolumesIsRebuiltRatherThanRestarted(@TempDir Path tmp) {
        Sandbox rendered = sandbox(tmp, Platform.Os.LINUX);
        Conversation c = conversation(rendered);
        FakeRunner runner = new FakeRunner()
                .ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"exited\",\"Status\":\"Exited (0)\",\"Image\":\"img\"}]")
                .ok("podman volume", "").ok("podman image exists", "")
                .ok("podman inspect", "HTTPS_PROXY=http://172.31.240.5:3128\n")
                .ok(MOUNTS_FORMAT, tmp + "/sb/.sessions/" + c.id() + "/work/api\n")
                .ok("podman rm", "").ok("podman run", "").ok("podman start", "");
        Sandbox sb = Sandbox.open(tmp.resolve("sb"), platform(tmp, Platform.Os.LINUX), runner);

        new SessionContainer(sb).start(c);

        assertThat(runner.calls()).anyMatch(call -> call.size() > 3 && call.get(1).equals("rm") && call.contains(c.container()));
        assertThat(runner.calls()).anyMatch(call -> call.size() > 1 && call.get(1).equals("run"));
        assertThat(runner.calls()).noneMatch(call -> call.size() > 1 && call.get(1).equals("start"));
    }

    /** Podman that cannot answer is not evidence of anything: the container is left as it is. */
    @Test
    void aContainerWhoseMountsCannotBeReadIsStartedAsBefore(@TempDir Path tmp) {
        Sandbox rendered = sandbox(tmp, Platform.Os.LINUX);
        Conversation c = conversation(rendered);
        FakeRunner runner = new FakeRunner()
                .ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"exited\",\"Status\":\"Exited (0)\",\"Image\":\"img\"}]")
                .ok("podman volume", "").ok("podman image exists", "")
                .ok("podman inspect", "HTTPS_PROXY=http://172.31.240.5:3128\n")
                .ok(MOUNTS_FORMAT, "")
                .ok("podman rm", "").ok("podman run", "").ok("podman start", "");
        Sandbox sb = Sandbox.open(tmp.resolve("sb"), platform(tmp, Platform.Os.LINUX), runner);

        new SessionContainer(sb).start(c);

        assertThat(runner.calls()).anyMatch(call -> call.size() > 1 && call.get(1).equals("start"));
        assertThat(runner.calls()).noneMatch(call -> call.size() > 1 && call.get(1).equals("run"));
    }

    @Test
    void startsAnExistingContainerWhoseProxySettingsStillMatch(@TempDir Path tmp) {
        Sandbox rendered = sandbox(tmp, Platform.Os.LINUX);
        Conversation c = conversation(rendered);
        FakeRunner runner = new FakeRunner()
                .ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"exited\",\"Status\":\"Exited (0)\",\"Image\":\"img\"}]")
                .ok("podman volume", "").ok("podman image exists", "")
                .ok("podman inspect", "HTTPS_PROXY=http://172.31.240.5:3128\nGATEWAY_URL=http://172.31.240.10:8080\n")
                .ok(MOUNTS_FORMAT, c.container() + "-work\n")
                .ok("podman start", "");
        Sandbox sb = Sandbox.open(tmp.resolve("sb"), platform(tmp, Platform.Os.LINUX), runner);

        new SessionContainer(sb).start(c);

        assertThat(runner.calls()).anyMatch(call -> call.size() > 1 && call.get(1).equals("start"));
        assertThat(runner.calls()).noneMatch(call -> call.size() > 1 && call.get(1).equals("run"));
    }
}
