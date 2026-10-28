package no.syntakserror.lyngvi.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.SandboxConfigFile;
import no.syntakserror.lyngvi.core.contract.InitReport;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import no.syntakserror.lyngvi.core.runtime.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxTest {

    static final Platform PLATFORM = new Platform(Platform.Os.LINUX, Path.of(System.getProperty("user.home")),
            Map.of("XDG_STATE_HOME", no.syntakserror.lyngvi.core.testing.IsolatedState.dir()), 1234, 5678);

    static Path gitRepo(Path dir, String name) throws Exception {
        Path repo = Files.createDirectories(dir.resolve(name));
        Files.createDirectories(repo.resolve(".git"));
        return repo;
    }

    @Test
    void initCreatesTheExpectedTree(@TempDir Path tmp) throws Exception {
        Path sb = tmp.resolve("sb");
        Path repo = gitRepo(tmp, "web");

        InitReport report = Sandbox.init(sb, new InitOptions(Optional.empty(), List.of(repo), Optional.empty(), false), PLATFORM, new FakeRunner());

        assertThat(report.sandboxDir()).isEqualTo(sb.toAbsolutePath().toString());
        assertThat(report.profile().name()).isEqualTo("default");
        assertThat(report.repos()).extracting("name").containsExactly("web");
        assertThat(sb.resolve("profile.json")).isRegularFile();
        assertThat(sb.resolve("sandbox.config")).isRegularFile();
        assertThat(sb.resolve(".env")).isRegularFile();
        assertThat(sb.resolve("profiles/default/profile.json")).isRegularFile();
        assertThat(sb.resolve("templates/AGENTS.md.template")).isRegularFile();
        assertThat(sb.resolve("state/conversations")).isDirectory();
        assertThat(sb.resolve("state/agents")).isDirectory();
        assertThat(sb.resolve("state/locks")).isDirectory();
        assertThat(sb.resolve(".sessions")).isDirectory();

        var config = SandboxConfigFile.read(sb.resolve("sandbox.config"));
        assertThat(config.uid()).isEqualTo(1234);
        assertThat(config.gid()).isEqualTo(5678);
        assertThat(config.profilePack()).isEqualTo(sb.toAbsolutePath().resolve("profiles/default"));
        assertThat(config.defaultAgent()).isEqualTo("claude-code");
        assertThat(config.language()).isEqualTo("en");
        assertThat(config.resources().mem()).isEqualTo("8g");
        assertThat(config.repos()).hasSize(1);
        assertThat(config.repos().getFirst().path()).isEqualTo(repo.toAbsolutePath());
    }

    @Test
    void initUsesAnExplicitProfilePack(@TempDir Path tmp) throws Exception {
        Path pack = Files.createDirectories(tmp.resolve("acme-pack"));
        String json = Files.readString(Path.of("..", "profiles", "default", "profile.json"))
                .replace("\"name\": \"default\"", "\"name\": \"acme\"")
                .replace("\"language\": \"en\"", "\"language\": \"nb\"")
                .replace("\"default\": \"claude-code\"", "\"default\": \"opencode\"");
        Files.writeString(pack.resolve("profile.json"), json);
        Path sb = tmp.resolve("sb");

        InitReport report = Sandbox.init(sb, new InitOptions(Optional.of(pack), List.of(), Optional.empty(), false), PLATFORM, new FakeRunner());

        assertThat(report.profile().name()).isEqualTo("acme");
        assertThat(Files.readString(sb.resolve("profile.json"))).isEqualTo(json);
        var config = SandboxConfigFile.read(sb.resolve("sandbox.config"));
        assertThat(config.profilePack()).isEqualTo(pack.toAbsolutePath());
        assertThat(config.language()).isEqualTo("nb");
        assertThat(config.defaultAgent()).isEqualTo("opencode");
    }

    @Test
    void initRejectsAnAgentNotEnabledInTheProfile(@TempDir Path tmp) {
        assertThatThrownBy(() -> Sandbox.init(tmp.resolve("sb"),
                new InitOptions(Optional.empty(), List.of(), Optional.of("hal9000"), false), PLATFORM, new FakeRunner()))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("hal9000");
    }

    @Test
    void initRejectsARepositoryPathThatDoesNotExist(@TempDir Path tmp) {
        assertThatThrownBy(() -> Sandbox.init(tmp.resolve("sb"),
                new InitOptions(Optional.empty(), List.of(tmp.resolve("missing")), Optional.empty(), false), PLATFORM, new FakeRunner()))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void initTwiceRequiresForce(@TempDir Path tmp) throws Exception {
        Path sb = tmp.resolve("sb");
        InitOptions opts = new InitOptions(Optional.empty(), List.of(), Optional.empty(), false);
        Sandbox.init(sb, opts, PLATFORM, new FakeRunner());

        assertThatThrownBy(() -> Sandbox.init(sb, opts, PLATFORM, new FakeRunner()))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("--yes");

        Files.writeString(sb.resolve(".env"), "NODE_VERSION=20\n");
        Sandbox.init(sb, new InitOptions(Optional.empty(), List.of(), Optional.empty(), true), PLATFORM, new FakeRunner());

        assertThat(Files.readString(sb.resolve(".env"))).startsWith("NODE_VERSION=20\n");
    }

    @Test
    void openFailsOnAnUninitialisedDirectory(@TempDir Path tmp) {
        assertThatThrownBy(() -> Sandbox.open(tmp, PLATFORM, new FakeRunner()))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.NOT_INITIALIZED))
                .hasMessageContaining("init");
    }

    @Test
    void openLoadsConfigAndProfile(@TempDir Path tmp) throws Exception {
        Path sb = tmp.resolve("sb");
        Sandbox.init(sb, new InitOptions(Optional.empty(), List.of(gitRepo(tmp, "api")), Optional.empty(), false), PLATFORM, new FakeRunner());

        Sandbox sandbox = Sandbox.open(sb, PLATFORM, new FakeRunner());

        assertThat(sandbox.profile().name()).isEqualTo("default");
        assertThat(sandbox.config().repos()).extracting("name").containsExactly("api");
        assertThat(sandbox.config().repos().getFirst().history()).isEmpty();
        assertThat(sandbox.messages().get("version.line", "x")).isEqualTo("lyngvi x");
        assertThat(History.SHALLOW).isEqualTo(sandbox.profile().copy().history());
    }

    @Test
    void aDirectoryWithOnlyASandboxConfigIsNotInitialised(@TempDir Path tmp) throws Exception {
        java.nio.file.Files.writeString(tmp.resolve("sandbox.config"), "SCHEMA_VERSION=2\n");
        assertThatThrownBy(() -> Sandbox.open(tmp, PLATFORM, new FakeRunner()))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.NOT_INITIALIZED))
                .hasMessageContaining("profile.json").hasMessageContaining("--sandbox");
    }
}
