package no.syntakserror.lyngvi.core.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.InitOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RendererTest {

    private static final Path GOLDEN = Path.of("src", "test", "resources", "golden", "m2");

    private static Platform platform(Path tmp) {
        return new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
    }

    private static FakeRunner runner() {
        return new FakeRunner()
                .ok("podman network ls", "[]")
                .ok("openssl req", "")
                .ok("openssl x509", "");
    }

    /** A sandbox named "sb" initialised with the default profile; rendering uses prefix 172.31.240. */
    private static Sandbox sandbox(Path tmp, FakeRunner runner) {
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(), Optional.empty(), false), platform(tmp), runner);
        return Sandbox.open(tmp.resolve("sb"), platform(tmp), runner);
    }

    private static String golden(String name) throws Exception {
        return Files.readString(GOLDEN.resolve(name));
    }

    @Test
    void rendersEveryGeneratedFileFromTheDefaultProfile(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, runner());

        RenderReport report = new Renderer(sb).render();

        assertThat(report.subnetPrefix()).isEqualTo("172.31.240");
        assertThat(Files.readString(sb.paths().containerfile())).isEqualTo(golden("Containerfile"));
        String compose = Files.readString(sb.paths().compose())
                .replace(tmp.resolve("home").toString(), "{{HOME}}")
                .replace("localhost/lyngvi-gateway:" + no.syntakserror.lyngvi.core.version.Version.current(), "localhost/lyngvi-gateway:{{VERSION}}");
        assertThat(compose).isEqualTo(golden("docker-compose.yml"));
        assertThat(Files.readString(sb.paths().allowedDomains())).isEqualTo(golden("allowed-domains.txt"));
        assertThat(Files.readString(sb.paths().gatewayHosts())).isEqualTo(golden("gateway-hosts.txt"));
        assertThat(Files.readString(sb.paths().squidConf())).isEqualTo(golden("squid.conf"));
        assertThat(Files.readString(sb.paths().agentsMd())).isEqualTo(golden("AGENTS.md"));
        assertThat(sb.config().subnetPrefix()).contains("172.31.240");
        assertThat(sb.paths().gatewayAllowlists()).isDirectory();
        assertThat(sb.paths().gatewaySecrets()).isDirectory();
        assertThat(sb.paths().gatewayPlugins()).isDirectory();
        assertThat(sb.paths().gatewayCerts()).isDirectory();
    }

    @Test
    void keepsThePrefixTheSandboxAlreadyUsesWhenTheRegistryLostItsEntry(@TempDir Path tmp) throws Exception {
        Sandbox first = sandbox(tmp, runner());
        new Renderer(first).render();
        assertThat(first.config().subnetPrefix()).contains("172.31.240");
        // The host-wide registry is lost, but the sandbox's own network still runs on the recorded prefix.
        Files.delete(platform(tmp).stateDir().resolve("subnets.txt"));
        FakeRunner liveNetwork = new FakeRunner()
                .ok("podman network ls", """
                        [{"name":"sb_internal","subnets":[{"subnet":"172.31.240.0/24","gateway":"172.31.240.1"}]}]""")
                .ok("openssl req", "")
                .ok("openssl x509", "");
        Sandbox sb = Sandbox.open(tmp.resolve("sb"), platform(tmp), liveNetwork);

        RenderReport report = new Renderer(sb).render();

        assertThat(report.subnetPrefix()).isEqualTo("172.31.240");
        assertThat(Files.readString(sb.paths().compose())).contains("172.31.240.0/24");
    }

    @Test
    void preservesTheUserSectionOfTheAllowlistAndItsInode(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, runner());
        new Renderer(sb).render();
        Path allowlist = sb.paths().allowedDomains();
        Object inode = Files.getAttribute(allowlist, "unix:ino");
        Files.writeString(allowlist, Files.readString(allowlist) + "example.org\n.example.net  # wildcard\n");

        new Renderer(sb).render();

        String content = Files.readString(allowlist);
        assertThat(content).endsWith("# user (kept across rebuilds; edit with 'setup.sh domain add|remove')\nexample.org\n.example.net  # wildcard\n");
        assertThat(content).containsOnlyOnce("# profile");
        assertThat(Files.getAttribute(allowlist, "unix:ino")).isEqualTo(inode);
    }

    @Test
    void includesAgentEgressAndAgentsMdAdditions(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, runner());
        Path agent = Files.createDirectories(sb.paths().agents().resolve("claude-code"));
        Files.writeString(agent.resolve("agent.json"), """
                {"id":"claude-code","label":"Claude Code","egress":["api.anthropic.com",{"host":"sentry.io","optional":true}]}
                """);
        Files.createDirectories(agent.resolve("AGENTS.d"));
        Files.writeString(agent.resolve("AGENTS.d/10-claude.md"), "## Claude Code\n\nUse CLAUDE.md.\n");
        Path pack = sb.config().profilePack();
        Files.createDirectories(pack.resolve("AGENTS.md.d"));
        Files.writeString(pack.resolve("AGENTS.md.d/10-org.md"), "## Our conventions\n\nBe nice.\n");

        new Renderer(sb).render();

        assertThat(Files.readString(sb.paths().allowedDomains())).contains("# agents\napi.anthropic.com  # claude-code\n");
        assertThat(Files.readString(sb.paths().allowedDomains())).doesNotContain("sentry.io");
        String agentsMd = Files.readString(sb.paths().agentsMd());
        assertThat(agentsMd).contains("## Our conventions").contains("## Claude Code");
    }

    @Test
    void rendersGatewayHostsAndSquidPeerWhenAManifestHasInstances(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, runner());
        Files.createDirectories(sb.paths().gateway());
        Files.writeString(sb.paths().gatewayManifest(), """
                {"schemaVersion":2,"ipSuffix":10,"instances":[
                  {"id":"docs","plugin":"http","label":"Docs","hostname":"docs.internal.example","hostAliases":["docs"],
                   "baseUrlEnv":"DOCS_BASE_URL","secret":{"env":"DOCS_TOKEN","scheme":"bearer"},
                   "allowlist":{"file":"docs.txt","mode":"path-prefix","label":"documents"},"capabilities":["read"],"config":{"pathAllow":["^/"]}}]}
                """);

        new Renderer(sb).render();

        assertThat(Files.readString(sb.paths().gatewayHosts())).isEqualTo(golden("gateway-hosts-with-docs.txt"));
        String squid = Files.readString(sb.paths().squidConf());
        assertThat(squid).contains("acl gateway_hosts dstdomain \"/etc/squid/gateway-hosts.txt\"")
                .contains("cache_peer 172.31.240.10 parent 8080 0 no-query originserver name=gateway")
                .contains("http_access allow !CONNECT gateway_hosts")
                .contains("http_access deny CONNECT gateway_hosts");
        assertThat(Files.readString(sb.paths().agentsMd())).contains("Docs").contains("http://docs.internal.example").contains("DOCS_GATEWAY_URL");
        assertThat(sb.paths().gatewayAllowlists().resolve("docs.txt")).isRegularFile();
        assertThat(Files.readString(sb.paths().gatewaySecrets().resolve("docs.env.example"))).contains("DOCS_BASE_URL=").contains("DOCS_TOKEN=");
    }

    /** The one private host a container may reach is the profile's, on the profile's ports; an empty list closes it. */
    @Test
    void opensTheLocalModelServerOnTheProfilesPortsOnly(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, runner());
        Files.writeString(sb.paths().profileJson(), Files.readString(sb.paths().profileJson())
                .replace("\"ports\": [11434, 1234]", "\"ports\": [10240]"));
        sb.reload();

        new Renderer(sb).render();

        assertThat(Files.readString(sb.paths().squidConf()))
                .contains("acl local_models_host dstdomain host.containers.internal\nacl local_models_port port 10240\n")
                .contains("http_access allow local_models_host local_models_port");

        Files.writeString(sb.paths().profileJson(), Files.readString(sb.paths().profileJson()).replace("\"ports\": [10240]", "\"ports\": []"));
        sb.reload();
        new Renderer(sb).render();

        assertThat(Files.readString(sb.paths().squidConf()))
                .doesNotContain("local_models_host")
                .contains("# local models: no ports open");
    }

    @Test
    void includesTheGatewayServiceOnlyWhenItsContainerfileExists(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, runner());
        Files.createDirectories(sb.paths().gateway());
        Files.writeString(sb.paths().gateway().resolve("Containerfile"), "FROM scratch\n");

        new Renderer(sb).render();

        String compose = Files.readString(sb.paths().compose());
        assertThat(compose).contains("  gateway:\n").contains("ipv4_address: 172.31.240.10").contains("./gateway/plugins:/app/plugins:ro,z");
        assertThat(compose).contains("keep-id:uid=1234,gid=5678");
    }

    @Test
    void rejectsUnknownToolchainsAndMissingJavaForKotlin(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, runner());
        Files.writeString(sb.paths().profileJson(), Files.readString(sb.paths().profileJson()).replace("\"toolchains\": [\"node22\"]", "\"toolchains\": [\"kotlin\"]"));
        sb.reload();

        assertThatThrownBy(() -> new Renderer(sb).render())
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("kotlin");

        Files.writeString(sb.paths().profileJson(), Files.readString(sb.paths().profileJson()).replace("[\"kotlin\"]", "[\"cobol\"]"));
        sb.reload();
        assertThatThrownBy(() -> new Renderer(sb).render()).hasMessageContaining("cobol");
    }

    @Test
    void concatenatesFragmentsInProfileOrderAndSubstitutesIds(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, runner());
        Files.writeString(sb.paths().profileJson(), Files.readString(sb.paths().profileJson()).replace("\"toolchains\": [\"node22\"]", "\"toolchains\": [\"java21\", \"maven\", \"node22\"]"));
        sb.reload();

        new Renderer(sb).render();

        String cf = Files.readString(sb.paths().containerfile());
        assertThat(cf).contains("ARG USER_UID=1234").contains("ARG USER_GID=5678").doesNotContain("{{");
        assertThat(cf.indexOf("toolchain: java21")).isLessThan(cf.indexOf("toolchain: maven"));
        assertThat(cf.indexOf("toolchain: maven")).isLessThan(cf.indexOf("toolchain: node22"));
        assertThat(cf).contains("LABEL no.syntakserror.lyngvi.toolchains=\"java21,maven,node22\"");
    }

    @Test
    void rendersTheGuiToolchainsAndTheirNotesForTheAgent(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, runner());
        Files.writeString(sb.paths().profileJson(), Files.readString(sb.paths().profileJson()).replace("\"toolchains\": [\"node22\"]", "\"toolchains\": [\"xvfb\", \"chromium\"]"));
        sb.reload();

        new Renderer(sb).render();

        String cf = Files.readString(sb.paths().containerfile());
        assertThat(cf.indexOf("toolchain: xvfb")).isLessThan(cf.indexOf("toolchain: chromium"));
        assertThat(cf).contains("ENV DISPLAY=:99").contains("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1")
                .contains("LABEL no.syntakserror.lyngvi.toolchains=\"xvfb,chromium\"");
        // The agents require node22, which has no note; the two GUI fragments do, in image order, before the agents.
        String agentsMd = Files.readString(sb.paths().agentsMd());
        int gui = agentsMd.indexOf("## Graphical programs and screenshots");
        int browser = agentsMd.indexOf("## Browser");
        assertThat(gui).isGreaterThan(agentsMd.indexOf("## How your work is delivered"));
        assertThat(browser).isGreaterThan(gui);
        assertThat(browser).isLessThan(agentsMd.indexOf("## Claude Code"));
        assertThat(agentsMd).contains("scrot /work/output/").contains("$CHROME_BIN").doesNotContain("{{");
        String claude = Files.readString(sb.paths().agentContainerfile("claude-code"));
        assertThat(claude.indexOf("toolchain: chromium")).isLessThan(claude.indexOf("toolchain: node22"));
    }

    @Test
    void generatesTheCertificateWithAllHostsInTheSan(@TempDir Path tmp) throws Exception {
        FakeRunner runner = runner();
        Sandbox sb = sandbox(tmp, runner);

        new Renderer(sb).render();

        List<String> req = runner.calls().stream().filter(c -> c.get(0).equals("openssl") && c.get(1).equals("req")).findFirst().orElseThrow();
        assertThat(req).contains("-x509", "-nodes", "-days", "3650", "-sha256");
        String san = req.get(req.indexOf("-addext") + 1);
        assertThat(san).isEqualTo("subjectAltName=DNS:gateway,DNS:localhost,IP:127.0.0.1,IP:172.31.240.10");
        assertThat(req).contains(sb.paths().gatewayCerts().resolve("gateway.key").toString(), sb.paths().gatewayCerts().resolve("gateway.crt").toString());
    }

    @Test
    void rendersOneContainerfilePerEnabledAgentWithRequiredFragments(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, runner());
        Files.writeString(sb.paths().profileJson(), Files.readString(sb.paths().profileJson()).replace("\"toolchains\": [\"node22\"]", "\"toolchains\": [\"python312\"]"));
        sb.reload();

        new Renderer(sb).render();

        Path claude = sb.paths().agentContainerfile("claude-code");
        assertThat(claude).isRegularFile();
        String cf = Files.readString(claude);
        assertThat(cf.indexOf("toolchain: python312")).isLessThan(cf.indexOf("toolchain: node22"));
        assertThat(cf.indexOf("toolchain: node22")).isLessThan(cf.indexOf("agent: claude-code"));
        assertThat(cf).doesNotContain("{{");
        assertThat(sb.paths().agentContainerfile("opencode")).isRegularFile();
        assertThat(sb.paths().agentContainerfile("mock-sh")).as("mock-sh has no image fragment").doesNotExist();
        assertThat(Files.readString(sb.paths().allowedDomains())).contains("api.anthropic.com  # claude-code").contains("models.dev  # opencode").doesNotContain("sentry.io");
        assertThat(Files.readString(sb.paths().agentsMd())).contains("## Claude Code").contains("## OpenCode");
    }
}
