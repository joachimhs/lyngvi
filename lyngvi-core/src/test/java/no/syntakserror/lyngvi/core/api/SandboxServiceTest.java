package no.syntakserror.lyngvi.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import no.syntakserror.lyngvi.core.contract.StatusReport;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import no.syntakserror.lyngvi.core.version.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxServiceTest {

    @Test
    void statusReportsConfigurationAndRepositories(@TempDir Path tmp) throws Exception {
        Path repo = SandboxTest.gitRepo(tmp, "api");
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(repo), Optional.empty(), false), SandboxTest.PLATFORM, new FakeRunner());
        Sandbox sandbox = Sandbox.open(tmp.resolve("sb"), SandboxTest.PLATFORM, new FakeRunner());

        StatusReport status = sandbox.sandboxService().status();

        assertThat(status.version()).isEqualTo(Version.current().toString());
        assertThat(status.sandboxDir()).isEqualTo(tmp.resolve("sb").toAbsolutePath().toString());
        assertThat(status.profile().name()).isEqualTo("default");
        assertThat(status.uid()).isEqualTo(1234);
        assertThat(status.gid()).isEqualTo(5678);
        assertThat(status.resources().mem()).isEqualTo("8g");
        assertThat(status.defaultAgent()).isEqualTo("claude-code");
        assertThat(status.language()).isEqualTo("en");
        assertThat(status.subnetPrefix()).isNull();
        assertThat(status.repos()).hasSize(1);
        assertThat(status.repos().getFirst().name()).isEqualTo("api");
        assertThat(status.repos().getFirst().path()).isEqualTo(repo.toAbsolutePath().toString());
        assertThat(status.repos().getFirst().history()).isNull();
    }

    @Test
    void upgradeRefreshesBundledFilesWritesVersionAndRerenders(@TempDir Path tmp) throws Exception {
        FakeRunner runner = new FakeRunner().ok("podman network ls", "[]").ok("openssl req", "").ok("openssl x509", "");
        Platform platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), java.util.Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(), Optional.empty(), false), platform, runner);
        Sandbox sandbox = Sandbox.open(tmp.resolve("sb"), platform, runner);
        java.nio.file.Files.writeString(tmp.resolve("sb/image/base.Containerfile"), "FROM old\n");
        java.nio.file.Files.writeString(tmp.resolve("sb/VERSION"), "2.0.0-M7\n");
        // user-owned files must survive
        java.nio.file.Files.createDirectories(tmp.resolve("sb/gateway/allowlists"));
        java.nio.file.Files.writeString(tmp.resolve("sb/gateway/allowlists/keep.txt"), "mine\n");
        assertThat(sandbox.sandboxService().doctor().checks()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo("bundle");
            assertThat(c.level()).isEqualTo(no.syntakserror.lyngvi.core.doctor.Check.Level.WARN);
            assertThat(c.hint()).contains("upgrade");
        });

        no.syntakserror.lyngvi.core.contract.UpgradeReport report = sandbox.sandboxService().upgrade();

        assertThat(report.from()).isEqualTo("2.0.0-M7");
        assertThat(report.to()).isEqualTo(Version.current().toString());
        assertThat(report.updated()).contains("image/base.Containerfile");
        assertThat(java.nio.file.Files.readString(tmp.resolve("sb/image/base.Containerfile"))).contains("getent group");
        assertThat(java.nio.file.Files.readString(tmp.resolve("sb/VERSION")).strip()).isEqualTo(Version.current().toString());
        assertThat(java.nio.file.Files.readString(tmp.resolve("sb/Containerfile"))).contains("getent group");
        assertThat(tmp.resolve("sb/gateway/allowlists/keep.txt")).hasContent("mine\n");
        assertThat(sandbox.sandboxService().doctor().checks()).noneSatisfy(c -> assertThat(c.id()).isEqualTo("bundle"));
    }
}
