package no.syntakserror.lyngvi.core.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.GatewayService;
import no.syntakserror.lyngvi.core.api.InitOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.contract.GatewayInstanceInfo;
import no.syntakserror.lyngvi.core.contract.GatewayPluginInfo;
import no.syntakserror.lyngvi.core.contract.SecretStatus;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayServiceTest {

    @TempDir Path tmp;
    Sandbox sb;
    FakeRunner runner;

    private Platform platform() {
        return new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
    }

    @BeforeEach
    void init() {
        runner = new FakeRunner().ok("podman network ls", "[]").ok("openssl req", "").ok("openssl x509", "")
                .ok("podman ps", "[]").ok("podman exec", "{\"ok\":true,\"instances\":[]}\n").ok("podman compose", "");
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(), Optional.empty(), false), platform(), runner);
        sb = Sandbox.open(tmp.resolve("sb"), platform(), runner);
    }

    private GatewayService gw() {
        return sb.gatewayService();
    }

    @Test
    void initBundlesTheGatewayRuntimePluginsContainerfileAndAnEmptyManifest() throws Exception {
        Path g = sb.paths().gateway();
        assertThat(g.resolve("Containerfile")).isRegularFile();
        assertThat(g.resolve("gateway.jar")).isRegularFile();
        assertThat(g.resolve("plugins/confluence.jar")).isRegularFile();
        assertThat(g.resolve("plugins/jira.jar")).isRegularFile();
        assertThat(Files.size(g.resolve("gateway.jar"))).isGreaterThan(100_000);
        assertThat(ManifestIo.read(sb.paths().gatewayManifest())).isEqualTo(Manifest.empty());
        sb.sandboxService().rebuildConfig();
        assertThat(Files.readString(sb.paths().compose())).contains("  gateway:").contains("localhost/lyngvi-gateway:");
    }

    @Test
    void pluginsListsTheBuiltInAndTheBundledJars() {
        List<GatewayPluginInfo> plugins = gw().plugins();
        assertThat(plugins).extracting(GatewayPluginInfo::type).containsExactly("http", "confluence", "jira");
        assertThat(plugins.get(0).source()).isEqualTo("builtin");
        assertThat(plugins.get(1).source()).isEqualTo("confluence.jar");
        assertThat(plugins.get(1).allowlistLabel()).isEqualTo("pages/spaces");
        assertThat(plugins.get(2).label()).isEqualTo("Jira");
    }

    @Test
    void instanceAddWritesManifestAllowlistSecretExampleHostsAndCompose() throws Exception {
        GatewayInstanceInfo info = gw().instanceAdd("docs", "http", "Docs", "docs.gateway.local", List.of("docs.internal"), "bearer", null, Map.of("restBase", "/api", "pathAllow", List.of("^/pages")));
        assertThat(info.id()).isEqualTo("docs");
        assertThat(info.status()).isEqualTo("no-secret");
        Manifest m = ManifestIo.read(sb.paths().gatewayManifest());
        Instance i = m.instance("docs");
        assertThat(i.hostname()).isEqualTo("docs.gateway.local");
        assertThat(i.baseUrlEnv()).isEqualTo("DOCS_BASE_URL");
        assertThat(i.secret().env()).isEqualTo("DOCS_TOKEN");
        assertThat(i.allowlist().file()).isEqualTo("docs.txt");
        assertThat(i.config()).containsEntry("restBase", "/api");
        assertThat(sb.paths().gatewayAllowlists().resolve("docs.txt")).isRegularFile();
        assertThat(sb.paths().gatewaySecrets().resolve("docs.env.example")).isRegularFile();
        assertThat(sb.paths().gatewaySecrets().resolve("docs.env")).isRegularFile().isEmptyFile();
        assertThat(Files.getPosixFilePermissions(sb.paths().gatewaySecrets().resolve("docs.env"))).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Files.readString(sb.paths().gatewayHosts())).contains("docs.gateway.local").contains("docs.internal");
        assertThat(Files.readString(sb.paths().compose())).contains("./gateway/secrets/docs.env");
    }

    @Test
    void instanceAddRejectsBadIdsDuplicatesAndUnknownPlugins() {
        assertThatThrownBy(() -> gw().instanceAdd("Bad Id", "http", null, "x.gateway.local", List.of(), null, null, Map.of()))
                .isInstanceOf(SandboxException.class).extracting(e -> ((SandboxException) e).code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> gw().instanceAdd("docs", "nope", null, "x.gateway.local", List.of(), null, null, Map.of()))
                .isInstanceOf(SandboxException.class).hasMessageContaining("nope").hasMessageContaining("http, confluence, jira");
        gw().instanceAdd("docs", "http", null, "docs.gateway.local", List.of(), null, null, Map.of());
        assertThatThrownBy(() -> gw().instanceAdd("docs", "http", null, "other.gateway.local", List.of(), null, null, Map.of()))
                .isInstanceOf(SandboxException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> gw().instanceAdd("wiki", "confluence", null, "docs.gateway.local", List.of(), null, null, Map.of()))
                .isInstanceOf(SandboxException.class).hasMessageContaining("hostname");
    }

    @Test
    void instanceRemoveKeepsAllowlistAndSecretFiles() throws Exception {
        gw().instanceAdd("docs", "http", null, "docs.gateway.local", List.of(), null, null, Map.of());
        gw().allowAdd("docs", "/pages");
        gw().instanceRemove("docs");
        assertThat(ManifestIo.read(sb.paths().gatewayManifest()).instances()).isEmpty();
        assertThat(sb.paths().gatewayAllowlists().resolve("docs.txt")).isRegularFile();
        assertThat(Files.readString(sb.paths().gatewayHosts())).doesNotContain("docs.gateway.local");
        assertThatThrownBy(() -> gw().instanceRemove("docs")).isInstanceOf(SandboxException.class).extracting(e -> ((SandboxException) e).code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void secretSetWritesAPrivateEnvFileAndStatusMasksTheValue() throws Exception {
        gw().instanceAdd("docs", "http", null, "docs.gateway.local", List.of(), null, null, Map.of());
        SecretStatus before = gw().secretStatus("docs");
        assertThat(before.present()).isFalse();
        gw().secretSet("docs", "https://docs.example.com/", "abcdefgh12345678");
        Path env = sb.paths().gatewaySecrets().resolve("docs.env");
        assertThat(Files.readString(env)).isEqualTo("DOCS_BASE_URL=https://docs.example.com\nDOCS_TOKEN=abcdefgh12345678\n");
        assertThat(Files.getPosixFilePermissions(env)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        SecretStatus after = gw().secretStatus("docs");
        assertThat(after.present()).isTrue();
        assertThat(after.baseUrl()).isEqualTo("https://docs.example.com");
        assertThat(after.masked()).isEqualTo("****5678");
        assertThat(gw().list().getFirst().status()).isEqualTo("no-allowlist");
        gw().allowAdd("docs", "/pages");
        assertThat(gw().list().getFirst().status()).isEqualTo("ready");
        gw().secretClear("docs");
        assertThat(env).isRegularFile().isEmptyFile();
        assertThat(gw().secretStatus("docs").present()).isFalse();
    }

    @Test
    void secretSetKeepsTheOtherValueWhenOnlyOneIsGiven() throws Exception {
        gw().instanceAdd("docs", "http", null, "docs.gateway.local", List.of(), null, null, Map.of());
        gw().secretSet("docs", "https://docs.example.com", null);
        gw().secretSet("docs", null, "tok");
        assertThat(Files.readString(sb.paths().gatewaySecrets().resolve("docs.env"))).isEqualTo("DOCS_BASE_URL=https://docs.example.com\nDOCS_TOKEN=tok\n");
        assertThatThrownBy(() -> gw().secretSet("docs", "not a url", null)).isInstanceOf(SandboxException.class).hasMessageContaining("URL");
    }

    @Test
    void allowlistEntriesAreNormalisedDeduplicatedAndRemovable() throws Exception {
        gw().instanceAdd("docs", "http", null, "docs.gateway.local", List.of(), null, null, Map.of());
        assertThat(gw().allowAdd("docs", "  /pages  ")).isTrue();
        assertThat(gw().allowAdd("docs", "/pages")).isFalse();
        gw().allowAdd("docs", "/blog");
        assertThat(gw().allowList("docs")).containsExactly("/pages", "/blog");
        assertThat(gw().allowRemove("docs", "/pages")).isTrue();
        assertThat(gw().allowRemove("docs", "/pages")).isFalse();
        assertThat(gw().allowList("docs")).containsExactly("/blog");
        assertThatThrownBy(() -> gw().allowAdd("docs", "# comment")).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> gw().allowAdd("nope", "/x")).isInstanceOf(SandboxException.class).hasMessageContaining("nope");
    }

    @Test
    void pluginInstallValidatesTheJarAndPluginRemoveDeletesIt() throws Exception {
        Path bad = tmp.resolve("bad.jar");
        Files.writeString(bad, "not a jar");
        assertThatThrownBy(() -> gw().pluginInstall(bad)).isInstanceOf(SandboxException.class).extracting(e -> ((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        Path copy = tmp.resolve("my-confluence.jar");
        Files.copy(sb.paths().gatewayPlugins().resolve("confluence.jar"), copy);
        assertThatThrownBy(() -> gw().pluginInstall(copy)).isInstanceOf(SandboxException.class).hasMessageContaining("confluence");
        gw().pluginRemove("confluence.jar");
        GatewayPluginInfo installed = gw().pluginInstall(copy);
        assertThat(installed.type()).isEqualTo("confluence");
        assertThat(sb.paths().gatewayPlugins().resolve("my-confluence.jar")).isRegularFile();
        assertThat(gw().plugins()).extracting(GatewayPluginInfo::source).containsExactly("builtin", "jira.jar", "my-confluence.jar");
    }

    @Test
    void statusRunsTheHealthCommandInTheGatewayContainer() {
        runner.ok("podman ps", "[{\"Names\":[\"sb-gateway\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"gw\"}]");
        Map<String, Object> health = gw().status(false);
        assertThat(health).containsEntry("ok", true);
        assertThat(runner.calls()).contains(List.of("podman", "exec", "sb-gateway", "/opt/jre/bin/java", "-jar", "/app/gateway.jar", "--health"));
    }

    @Test
    void statusFailsClearlyWhenTheGatewayIsNotRunning() {
        assertThatThrownBy(() -> gw().status(false)).isInstanceOf(SandboxException.class).hasMessageContaining("not running");
    }

    @Test
    void certRenewForcesANewCertificate() {
        int before = runner.calls().size();
        gw().certRenew();
        assertThat(runner.calls().subList(before, runner.calls().size())).anySatisfy(c -> assertThat(c).startsWith("openssl", "req"));
    }

    @Test
    void instanceChangesReloadARunningProxySoSquidSeesTheNewHost() {
        runner.ok("podman ps", "[{\"Names\":[\"sb-proxy\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"squid\"}]");
        gw().instanceAdd("docs", "http", null, "docs.gateway.local", List.of(), null, null, Map.of());
        assertThat(runner.calls()).contains(List.of("podman", "exec", "sb-proxy", "squid", "-k", "reconfigure"));
    }
}
