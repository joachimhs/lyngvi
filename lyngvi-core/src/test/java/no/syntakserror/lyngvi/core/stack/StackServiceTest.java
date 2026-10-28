package no.syntakserror.lyngvi.core.stack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.InitOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.DomainService;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.render.Renderer;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StackServiceTest {

    static final String PS_JSON = """
            [{"Names":["sb-proxy"],"State":"running","Status":"Up 2 minutes (healthy)","Image":"docker.io/ubuntu/squid:latest"}]
            """;

    @TempDir Path tmp;
    FakeRunner runner;
    Sandbox sandbox;

    static Platform platform(Path tmp) {
        return new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
    }

    @BeforeEach
    void setUp() {
        runner = new FakeRunner()
                .ok("podman network ls", "[]")
                .ok("openssl req", "").ok("openssl x509", "")
                .ok("podman compose version", "podman-compose 1.2.0\n")
                .ok("podman compose", "")
                .ok("podman ps", PS_JSON)
                .ok("podman exec", "")
                .ok("podman build", "STEP 1/5: FROM ...\nSuccessfully tagged localhost/lyngvi-base\n")
                .ok("podman logs", "1757000000.000 1 172.31.240.20 TCP_DENIED/403 1 CONNECT blocked.example:443 - HIER_NONE/- -\n")
                .ok("podman image exists", "");
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(), Optional.empty(), false), platform(tmp), runner);
        sandbox = Sandbox.open(tmp.resolve("sb"), platform(tmp), runner);
    }

    private List<List<String>> callsStartingWith(String... words) {
        List<List<String>> out = new ArrayList<>();
        for (List<String> c : runner.calls()) {
            if (c.size() >= words.length && c.subList(0, words.length).equals(List.of(words))) out.add(c);
        }
        return out;
    }

    @Test
    void upRequiresRenderedFiles() {
        assertThatThrownBy(() -> sandbox.stackService().up())
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("rebuild-config");
    }

    @Test
    void upAndDownUseComposeWithProjectAndFile() {
        new Renderer(sandbox).render();

        sandbox.stackService().up();
        sandbox.stackService().down();

        assertThat(callsStartingWith("podman", "compose")).contains(
                List.of("podman", "compose", "-p", "sb", "-f", "docker-compose.yml", "up", "-d"),
                List.of("podman", "compose", "-p", "sb", "-f", "docker-compose.yml", "down"));
    }

    @Test
    void fallsBackToPodmanComposeBinary() {
        runner.on("podman compose version", 125, "", "unrecognized command").ok("podman-compose --version", "1.0.6\n").ok("podman-compose", "");
        new Renderer(sandbox).render();

        sandbox.stackService().up();

        assertThat(callsStartingWith("podman-compose")).contains(List.of("podman-compose", "-p", "sb", "-f", "docker-compose.yml", "up", "-d"));
    }

    @Test
    void statusListsProjectContainers() {
        new Renderer(sandbox).render();

        StackStatus status = sandbox.stackService().status();

        assertThat(status.prefix()).isEqualTo("172.31.240");
        assertThat(status.services()).hasSize(2);
        assertThat(status.services().getFirst().name()).isEqualTo("proxy");
        assertThat(status.services().getLast().name()).isEqualTo("gateway");
        assertThat(status.services().getLast().state()).isEmpty();
        assertThat(status.services().getFirst().container()).isEqualTo("sb-proxy");
        assertThat(status.services().getFirst().state()).isEqualTo("running");
        assertThat(status.services().getFirst().health()).isEqualTo("healthy");
        assertThat(callsStartingWith("podman", "ps").getFirst()).contains("--format", "json", "--filter", "name=^sb-");
    }

    @Test
    void buildBuildsTheBaseImageWithTheVersionTag() {
        new Renderer(sandbox).render();
        List<String> lines = new ArrayList<>();

        String image = sandbox.stackService().build(false, lines::add);

        assertThat(image).startsWith("localhost/lyngvi-base:2.0.0");
        List<String> cmd = callsStartingWith("podman", "build").getFirst();
        assertThat(cmd).contains("-t", image, "-f", "Containerfile", ".");
        assertThat(cmd).doesNotContain("--no-cache");
        assertThat(lines).contains("STEP 1/5: FROM ...");

        sandbox.stackService().build(true, l -> {});
        // The base image plus one image per enabled agent with an image fragment (claude-code, opencode), twice.
        assertThat(callsStartingWith("podman", "build")).hasSize(6);
        assertThat(callsStartingWith("podman", "build").get(3)).contains("--no-cache");
    }

    @Test
    void reloadProxyReconfiguresSquidInTheRunningContainer() {
        new Renderer(sandbox).render();

        boolean reloaded = sandbox.stackService().reloadProxy();

        assertThat(reloaded).isTrue();
        assertThat(callsStartingWith("podman", "exec")).contains(List.of("podman", "exec", "sb-proxy", "squid", "-k", "reconfigure"));
    }

    @Test
    void reloadProxyIsFalseWhenTheProxyIsNotRunning() {
        runner.ok("podman ps", "[]");
        new Renderer(sandbox).render();

        assertThat(sandbox.stackService().reloadProxy()).isFalse();
        assertThat(callsStartingWith("podman", "exec")).isEmpty();
    }

    @Test
    void blockedRequestsComeFromTheProxyLog() {
        new Renderer(sandbox).render();

        List<BlockedRequest> blocked = sandbox.stackService().blocked(500);

        assertThat(blocked).hasSize(1);
        assertThat(blocked.getFirst().host()).isEqualTo("blocked.example");
        assertThat(callsStartingWith("podman", "logs").getFirst()).contains("--tail", "500", "sb-proxy");
    }

    @Test
    void domainAddWritesTheUserSectionAndReloads() throws Exception {
        new Renderer(sandbox).render();
        DomainService domains = sandbox.domainService();

        DomainEntry added = domains.add("example.org", Optional.of("docs"));
        domains.add(".example.net", Optional.empty());

        assertThat(added.domain()).isEqualTo("example.org");
        assertThat(added.source()).isEqualTo("user");
        assertThat(Files.readString(sandbox.paths().allowedDomains())).endsWith(
                "# user (kept across rebuilds; edit with 'setup.sh domain add|remove')\nexample.org  # docs\n.example.net\n");
        assertThat(domains.list()).extracting("domain").startsWith("registry.npmjs.org").endsWith("example.org", ".example.net");
        assertThat(domains.list()).extracting("source").contains("profile", "agent:claude-code", "agent:opencode", "user");
        assertThat(domains.list().getFirst().source()).isEqualTo("profile");
        assertThat(callsStartingWith("podman", "exec")).isNotEmpty();

        domains.remove("example.org");
        assertThat(domains.list()).extracting("domain").doesNotContain("example.org").endsWith(".example.net");
    }

    @Test
    void domainAddRejectsInvalidNamesDuplicatesAndProfileEntries() {
        new Renderer(sandbox).render();
        DomainService domains = sandbox.domainService();

        assertThatThrownBy(() -> domains.add("not a domain", Optional.empty())).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> domains.add("registry.npmjs.org", Optional.empty())).hasMessageContaining("profile");
        domains.add("example.org", Optional.empty());
        assertThatThrownBy(() -> domains.add("example.org", Optional.empty())).hasMessageContaining("already");
        assertThatThrownBy(() -> domains.remove("registry.npmjs.org")).hasMessageContaining("profile");
        assertThatThrownBy(() -> domains.remove("nope.example")).hasMessageContaining("not in the user section");
    }

    @Test
    void doctorAddsPodmanChecksAfterRendering() throws Exception {
        runner.ok("git --version", "git version 2.50.1\n").ok("openssl version", "OpenSSL 3\n").ok("podman --version", "podman version 5.3.1\n");
        new Renderer(sandbox).render();

        var report = sandbox.sandboxService().doctor();
        var ids = report.checks().stream().map(c -> c.id()).toList();

        assertThat(ids).contains("generated", "subnet", "compose-mounts", "cert", "image");
        assertThat(report.fail()).isZero();

        Files.writeString(sandbox.paths().compose(), Files.readString(sandbox.paths().compose()) + "      - /Users/x/repo:/work/repo:rw\n");
        var tampered = sandbox.sandboxService().doctor();
        assertThat(tampered.checks().stream().filter(c -> c.id().equals("compose-mounts")).findFirst().orElseThrow().level().name()).isEqualTo("FAIL");
    }

    @Test
    void doctorFailsWhenAnotherNetworkUsesOurPrefix() {
        runner.ok("git --version", "git version 2.50.1\n").ok("openssl version", "OpenSSL 3\n").ok("podman --version", "podman version 5.3.1\n");
        new Renderer(sandbox).render();
        runner.ok("podman network ls", "[{\"name\":\"other_internal\",\"subnets\":[{\"subnet\":\"172.31.240.0/24\"}]}]");

        var report = sandbox.sandboxService().doctor();

        assertThat(report.checks().stream().filter(c -> c.id().equals("subnet")).findFirst().orElseThrow().level().name()).isEqualTo("FAIL");
    }

    @Test
    void doctorWarnsWhenGeneratedFilesAreOlderThanTheirSources() throws Exception {
        runner.ok("git --version", "git version 2.50.1\n").ok("openssl version", "OpenSSL 3\n").ok("podman --version", "podman version 5.3.1\n");
        new Renderer(sandbox).render();
        Files.setLastModifiedTime(sandbox.paths().profileJson(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        var report = sandbox.sandboxService().doctor();

        assertThat(report.checks().stream().filter(c -> c.id().equals("generated")).findFirst().orElseThrow().level().name()).isEqualTo("WARN");
    }

    @Test
    void buildAlsoBuildsEveryEnabledAgentImage() {
        new Renderer(sandbox).render();

        sandbox.stackService().build(false, l -> {});

        List<List<String>> builds = callsStartingWith("podman", "build");
        assertThat(builds).hasSize(3);
        assertThat(builds.get(1)).contains("-t", "localhost/lyngvi-claude-code:" + no.syntakserror.lyngvi.core.version.Version.current(), "-f", "image/agents/claude-code.Containerfile");
        assertThat(builds.get(2)).contains("-t", "localhost/lyngvi-opencode:" + no.syntakserror.lyngvi.core.version.Version.current());
    }

    @Test
    void streamLogsAndFollowBlockedReturnCancellableHandles() {
        new Renderer(sandbox).render();
        runner.ok("podman logs", "line one\n1757000000.000 1 172.31.240.20 TCP_DENIED/403 1 CONNECT blocked.example:443 - HIER_NONE/- -\n");
        List<String> lines = new java.util.ArrayList<>();
        List<String> denied = new java.util.ArrayList<>();

        no.syntakserror.lyngvi.core.runtime.Runner.StreamHandle h1 = sandbox.stackService().streamLogs("proxy", 50, lines::add);
        no.syntakserror.lyngvi.core.runtime.Runner.StreamHandle h2 = sandbox.stackService().followBlocked(50, d -> denied.add(d.host()));
        h1.cancel();

        assertThat(lines).containsExactly("line one", "1757000000.000 1 172.31.240.20 TCP_DENIED/403 1 CONNECT blocked.example:443 - HIER_NONE/- -");
        assertThat(denied).containsExactly("blocked.example");
        assertThat(h1.isCancelled()).isTrue();
        assertThat(h2.waitFor()).isZero();
        assertThat(callsStartingWith("podman", "logs")).allSatisfy(c -> assertThat(c).contains("-f", "--tail", "50"));
    }

    @Test
    void reloadProxyFallsBackToAHupWhenReconfigureCannotFindThePidFile() {
        new Renderer(sandbox).render();
        runner.ok("podman ps", "[{\"Names\":[\"sb-proxy\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"squid\"}]")
              .on("podman exec sb-proxy squid", 1, "", "FATAL: no pid_filename configured\n")
              .ok("podman exec sb-proxy sh", "");

        assertThat(sandbox.stackService().reloadProxy()).isTrue();

        assertThat(callsStartingWith("podman", "exec")).anySatisfy(c -> assertThat(c).contains("sh", "-c").anySatisfy(a -> assertThat(a).contains("kill -HUP")));
    }

    @Test
    void imageToolchainsComeFromTheImageLabel() {
        new Renderer(sandbox).render();
        runner.ok("podman image exists", "").ok("podman image inspect", "java21,maven\n");
        assertThat(sandbox.stackService().imageToolchains()).contains(List.of("java21", "maven"));
        runner.on("podman image exists", 1, "", "");
        assertThat(sandbox.stackService().imageToolchains()).isEmpty();
    }
}
