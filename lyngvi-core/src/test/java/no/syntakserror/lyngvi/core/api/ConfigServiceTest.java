package no.syntakserror.lyngvi.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.RepoEntry;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigServiceTest {

    @TempDir Path tmp;
    Sandbox sandbox;
    ConfigService config;

    @BeforeEach
    void init() {
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(), Optional.empty(), false), SandboxTest.PLATFORM, new FakeRunner());
        sandbox = Sandbox.open(tmp.resolve("sb"), SandboxTest.PLATFORM, new FakeRunner());
        config = sandbox.configService();
    }

    @Test
    void addsListsAndRemovesRepositories() throws Exception {
        Path api = SandboxTest.gitRepo(tmp, "api");
        Path web = SandboxTest.gitRepo(tmp, "web");

        RepoEntry added = config.addRepo(api, Optional.empty(), Optional.empty());
        config.addRepo(web, Optional.of("frontend"), Optional.of(History.NONE));

        assertThat(added.name()).isEqualTo("api");
        assertThat(config.repos()).extracting("name").containsExactly("api", "frontend");
        assertThat(Sandbox.open(tmp.resolve("sb"), SandboxTest.PLATFORM, new FakeRunner()).config().repos()).hasSize(2);

        RepoEntry removed = config.removeRepo("frontend");
        assertThat(removed.path()).isEqualTo(web.toAbsolutePath());
        assertThat(config.repos()).extracting("name").containsExactly("api");

        config.removeRepo(api.toString());
        assertThat(config.repos()).isEmpty();
    }

    @Test
    void rejectsDuplicateNamesAndPaths() throws Exception {
        Path api = SandboxTest.gitRepo(tmp, "api");
        Path other = SandboxTest.gitRepo(tmp.resolve("other"), "api");
        config.addRepo(api, Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> config.addRepo(other, Optional.empty(), Optional.empty()))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("api");
        assertThatThrownBy(() -> config.addRepo(api, Optional.of("api2"), Optional.empty()))
                .hasMessageContaining("already registered");
    }

    @Test
    void rejectsReservedAndInvalidNames() throws Exception {
        Path api = SandboxTest.gitRepo(tmp, "api");

        assertThatThrownBy(() -> config.addRepo(api, Optional.of("output"), Optional.empty()))
                .isInstanceOf(SandboxException.class).hasMessageContaining("reserved");
        assertThatThrownBy(() -> config.addRepo(api, Optional.of("bad name"), Optional.empty()))
                .isInstanceOf(SandboxException.class);
    }

    @Test
    void rejectsPathsThatAreNotDirectories(@TempDir Path other) throws Exception {
        Path file = Files.writeString(other.resolve("file.txt"), "x");

        assertThatThrownBy(() -> config.addRepo(file, Optional.empty(), Optional.empty()))
                .isInstanceOf(SandboxException.class).hasMessageContaining("directory");
    }

    @Test
    void removingAnUnknownRepositoryFails() {
        assertThatThrownBy(() -> config.removeRepo("nope"))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void setResourcesMergesAndValidates() {
        ResourceLimits updated = config.setResources(new ResourceLimits("16g", null, null, "4096"));

        assertThat(updated).isEqualTo(new ResourceLimits("16g", "12g", "4.0", "4096"));
        assertThat(config.resources()).isEqualTo(updated);
        assertThatThrownBy(() -> config.setResources(new ResourceLimits("lots", null, null, null)))
                .isInstanceOf(SandboxException.class).hasMessageContaining("mem");
        assertThatThrownBy(() -> config.setResources(new ResourceLimits(null, null, null, null)))
                .isInstanceOf(SandboxException.class).hasMessageContaining("at least one");
    }

    @Test
    void profileInfoDescribesTheActiveProfile() {
        var info = config.profile();

        assertThat(info.name()).isEqualTo("default");
        assertThat(info.label()).isEqualTo("Default profile");
        assertThat(info.pack()).isEqualTo(tmp.resolve("sb").toAbsolutePath().resolve("profiles/default").toString());
    }

    @Test
    void toolchainsCanBeChangedInTheLocalProfile() throws Exception {
        Sandbox sb = sandbox;
        assertThat(sb.configService().availableToolchains()).contains("node22", "java25", "python312", "go123");
        assertThat(sb.configService().toolchains()).containsExactly("node22");

        sb.configService().setToolchains(List.of("node22", "java25"));

        assertThat(sb.profile().toolchains()).containsExactly("node22", "java25");
        assertThat(java.nio.file.Files.readString(sb.paths().profileJson())).contains("\"java25\"");
        assertThatThrownBy(() -> sb.configService().setToolchains(List.of("nope"))).isInstanceOf(SandboxException.class).hasMessageContaining("nope");
    }

    @Test
    void toolchainDescriptionsComeFromTheFragmentHeaders() {
        assertThat(config.availableToolchains()).contains("xvfb", "chromium");
        var descriptions = config.toolchainDescriptions();
        assertThat(descriptions).containsEntry("java21", "Temurin via SDKMAN").containsKey("xvfb").containsKey("chromium");
        assertThat(descriptions).as("a header without a parenthesis gives no description").doesNotContainKey("node22");
    }

    /** A hand-edited fragment in another encoding made the tooltip lookup throw, and the Toolchain tab came up empty. */
    @Test
    void aFragmentThatIsNotUtf8StillListsAndDoesNotBreakTheDescriptions() throws Exception {
        java.nio.file.Files.write(sandbox.paths().imageFragment("odd"),
                "# --- toolchain: odd (latin-1 \u00e6\u00f8\u00e5) ---\nUSER root\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        assertThat(config.availableToolchains()).contains("odd", "node22");
        assertThat(config.toolchainDescriptions()).containsKey("odd").containsKey("java21");
    }

    /** The port a local model server answers on is egress policy: it lands in the profile and in the rendered proxy. */
    @Test
    void aLocalModelPortIsOpenedInTheProfileAndInTheProxyConfiguration() throws Exception {
        // opening a port re-renders and reloads, so the runner has to answer what a render and a reload ask
        Sandbox sb = Sandbox.open(tmp.resolve("sb"), SandboxTest.PLATFORM, new FakeRunner()
                .ok("podman network ls", "[]").ok("openssl req", "").ok("openssl x509", "").ok("podman ps", ""));
        assertThat(sb.configService().localModelPorts()).containsExactly(11434, 1234);
        // the host those ports are open to: what tells a denial about this machine from one about the network
        assertThat(sb.configService().localModelHost()).isEqualTo("host.containers.internal");

        assertThat(sb.configService().openLocalModelPort(8188)).containsExactly(11434, 1234, 8188);

        assertThat(sb.profile().localModels().ports()).containsExactly(11434, 1234, 8188);
        assertThat(Files.readString(sb.paths().profileJson())).contains("8188");
        assertThat(Files.readString(sb.paths().squidConf())).contains("acl local_models_port port 11434 1234 8188");

        // opening the same port again is not an error, and does not list it twice
        assertThat(sb.configService().openLocalModelPort(8188)).containsExactly(11434, 1234, 8188);
        assertThatThrownBy(() -> sb.configService().openLocalModelPort(0)).isInstanceOf(SandboxException.class).hasMessageContaining("port");
        assertThatThrownBy(() -> sb.configService().openLocalModelPort(70000)).isInstanceOf(SandboxException.class);
    }

    @Test
    void protectedBranchesCanBeChangedInTheLocalProfile() throws Exception {
        assertThat(config.protectedBranches()).containsExactly("main", "master", "develop");
        config.setProtectedBranches(List.of("develop", "release"));
        assertThat(sandbox.profile().git().protectedBranches()).containsExactly("develop", "release");
        assertThat(java.nio.file.Files.readString(sandbox.paths().profileJson())).contains("\"release\"").doesNotContain("\"master\"");
        assertThatThrownBy(() -> config.setProtectedBranches(List.of("bad name"))).isInstanceOf(SandboxException.class);
    }
}
