package no.syntakserror.lyngvi.core.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.api.InitOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.DoctorReport;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import no.syntakserror.lyngvi.core.version.Version;
import no.syntakserror.lyngvi.core.runtime.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostChecksTest {

    private static final Platform PLATFORM = new Platform(Platform.Os.LINUX, Path.of(System.getProperty("user.home")),
            Map.of("XDG_STATE_HOME", no.syntakserror.lyngvi.core.testing.IsolatedState.dir()), 1234, 5678);

    private static FakeRunner healthyTools() {
        return new FakeRunner()
                .ok("podman --version", "podman version 5.3.1\n")
                .ok("podman compose version", "podman-compose version 1.2.0\n")
                .ok("git --version", "git version 2.50.1\n")
                .ok("openssl version", "OpenSSL 3.6.3\n")
                .ok("git -C", "true\n")
                .ok("podman network ls", "[]")
                .ok("podman image exists", "")
                .ok("openssl req", "")
                .ok("openssl x509", "X509v3 Subject Alternative Name:\n    DNS:gateway, DNS:localhost, IP Address:127.0.0.1, IP Address:172.31.240.10\n");
    }

    /** An initialised and rendered sandbox, so the Podman-side checks have files to inspect. */
    private static Sandbox sandbox(Path tmp, FakeRunner runner, List<Path> repos) {
        return sandbox(tmp, runner, repos, true);
    }

    private static Sandbox sandbox(Path tmp, FakeRunner runner, List<Path> repos, boolean render) {
        Platform platform = new Platform(PLATFORM.os(), PLATFORM.home(), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), PLATFORM.uid(), PLATFORM.gid());
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), repos, Optional.empty(), false), platform, runner);
        Sandbox sb = Sandbox.open(tmp.resolve("sb"), platform, runner);
        if (render) {
            new no.syntakserror.lyngvi.core.render.Renderer(sb).render();
            // The scripted openssl writes nothing; the certificate check reads the scripted SAN output instead.
            try {
                Files.writeString(sb.paths().gatewayCert(), "fake");
                Files.writeString(sb.paths().gatewayKey(), "fake");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
        return sb;
    }

    private static Check check(DoctorReport r, String id) {
        return r.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void healthyHostPassesEveryCheck(@TempDir Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("api"));
        FakeRunner runner = healthyTools();
        Sandbox sb = sandbox(tmp, runner, List.of(repo));

        DoctorReport report = sb.sandboxService().doctor();

        assertThat(report.fail()).as(report.checks().toString()).isZero();
        assertThat(report.warn()).as(report.checks().toString()).isZero();
        assertThat(report.healthy()).isTrue();
        assertThat(report.checks()).extracting("id").contains(
                "podman", "compose", "git", "openssl", "ids", "profile", "env", "state", "templates", "repo:api");
        assertThat(check(report, "podman").message()).contains("5.3.1");
    }

    @Test
    void missingPodmanIsAFailureWithAHint(@TempDir Path tmp) {
        FakeRunner runner = new FakeRunner()
                .ok("git --version", "git version 2.50.1\n")
                .ok("openssl version", "OpenSSL 3.6.3\n");
        Sandbox sb = sandbox(tmp, runner, List.of(), false);

        DoctorReport report = sb.sandboxService().doctor();

        assertThat(check(report, "podman").level()).isEqualTo(Check.Level.FAIL);
        assertThat(check(report, "podman").hint()).isNotBlank();
        assertThat(check(report, "compose").level()).isEqualTo(Check.Level.FAIL);
        assertThat(report.healthy()).isFalse();
    }

    @Test
    void oldGitIsAFailure(@TempDir Path tmp) {
        FakeRunner runner = healthyTools().ok("git --version", "git version 2.28.0\n");

        DoctorReport report = sandbox(tmp, runner, List.of()).sandboxService().doctor();

        assertThat(check(report, "git").level()).isEqualTo(Check.Level.FAIL);
        assertThat(check(report, "git").message()).contains("2.28.0");
    }

    @Test
    void legacyPodmanComposeIsAccepted(@TempDir Path tmp) {
        FakeRunner runner = healthyTools()
                .on("podman compose version", 125, "", "Error: unrecognized command\n")
                .ok("podman-compose --version", "podman-compose version 1.0.6\n");

        DoctorReport report = sandbox(tmp, runner, List.of()).sandboxService().doctor();

        assertThat(check(report, "compose").level()).isEqualTo(Check.Level.OK);
        assertThat(check(report, "compose").message()).contains("podman-compose");
    }

    @Test
    void uidMismatchIsAFailure(@TempDir Path tmp) {
        FakeRunner runner = healthyTools();
        sandbox(tmp, runner, List.of());
        Platform otherUser = new Platform(Platform.Os.LINUX, PLATFORM.home(), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 99, 5678);

        DoctorReport report = Sandbox.open(tmp.resolve("sb"), otherUser, runner).sandboxService().doctor();

        assertThat(check(report, "ids").level()).isEqualTo(Check.Level.FAIL);
        assertThat(check(report, "ids").message()).contains("1234").contains("99");
    }

    @Test
    void missingOrNonGitRepositoriesAreWarnings(@TempDir Path tmp) throws Exception {
        Path plain = Files.createDirectories(tmp.resolve("plain"));
        FakeRunner runner = healthyTools().on("git -C", 128, "", "fatal: not a git repository\n");
        Sandbox sb = sandbox(tmp, runner, List.of(plain));
        Files.delete(plain);

        DoctorReport report = sb.sandboxService().doctor();

        assertThat(check(report, "repo:plain").level()).isEqualTo(Check.Level.WARN);
        assertThat(report.warn()).as(report.checks().toString()).isEqualTo(1);
        assertThat(report.healthy()).isTrue();
    }

    @Test
    void missingBundledFilesAreAWarning(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, healthyTools(), List.of());
        Files.delete(tmp.resolve("sb/templates/AGENTS.md.template"));

        DoctorReport report = sb.sandboxService().doctor();

        assertThat(check(report, "templates").level()).isEqualTo(Check.Level.WARN);
        assertThat(check(report, "templates").hint()).contains("upgrade");
    }

    @Test
    void podmanThatCannotReachItsMachineFailsWithTheKnownHostsLine(@TempDir Path tmp) {
        FakeRunner runner = healthyTools()
                .on("podman info", 125, "", "Cannot connect to Podman. Please verify your connection to the Linux system using `podman system connection list`\n"
                        + "Error: unable to connect to Podman socket: knownhosts: /Users/me/.ssh/known_hosts:35: illegal base64 data at input byte 68\n");
        DoctorReport report = sandbox(tmp, runner, List.of(), false).sandboxService().doctor();
        Check c = check(report, "podman");
        assertThat(c.level()).isEqualTo(Check.Level.FAIL);
        assertThat(c.message()).contains("known_hosts").contains("35");
        assertThat(c.hint()).contains("line 35").contains("~/.ssh/known_hosts");
    }

    @Test
    void podmanInfoFailingForAnotherReasonIsReportedWithItsMessage(@TempDir Path tmp) {
        FakeRunner runner = healthyTools().on("podman info", 125, "", "Error: unable to connect to Podman socket: dial unix /x: no such file\n");
        Check c = check(sandbox(tmp, runner, List.of(), false).sandboxService().doctor(), "podman");
        assertThat(c.level()).isEqualTo(Check.Level.FAIL);
        assertThat(c.message()).contains("no such file");
    }

    @Test
    void cliDirectoryMustMatchTheRunningVersion(@TempDir Path tmp) throws Exception {
        Sandbox sb = sandbox(tmp, healthyTools(), List.of(), false);
        assertThat(sb.sandboxService().doctor().checks()).noneSatisfy(c -> assertThat(c.id()).isEqualTo("cli"));
        Files.createDirectories(sb.paths().cli());
        Files.writeString(sb.paths().cli().resolve("VERSION"), "1.9.0\n");
        Check stale = check(sb.sandboxService().doctor(), "cli");
        assertThat(stale.level()).isEqualTo(Check.Level.FAIL);
        assertThat(stale.message()).contains("1.9.0");
        Files.writeString(sb.paths().cli().resolve("VERSION"), Version.current() + "\n");
        assertThat(check(sb.sandboxService().doctor(), "cli").level()).isEqualTo(Check.Level.OK);
    }

    @Test
    void deepChecksRunAnEgressSelfTestFromAThrowawayContainer(@TempDir Path tmp) throws Exception {
        FakeRunner runner = healthyTools()
                .ok("podman ps", "[{\"Names\":[\"sb-proxy\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"squid\"}]")
                .ok("podman run", "allowed=200 denied=000 direct=000 gateway=200\n");
        Sandbox sb = sandbox(tmp, runner, List.of(), true);
        DoctorReport report = sb.sandboxService().doctor(true);
        assertThat(check(report, "egress-allowed").level()).isEqualTo(Check.Level.OK);
        assertThat(check(report, "egress-denied").level()).isEqualTo(Check.Level.OK);
        assertThat(check(report, "egress-direct").level()).isEqualTo(Check.Level.OK);
        assertThat(check(report, "egress-gateway").level()).isEqualTo(Check.Level.OK);
        List<String> run = runner.calls().stream().filter(c -> c.size() > 2 && c.get(0).equals("podman") && c.get(1).equals("run")).findFirst().orElseThrow();
        assertThat(run).containsSequence("--network", "sb_internal").contains("--cap-drop");
        assertThat(String.join(" ", run)).contains("registry.npmjs.org").contains("172.31.240.5:3128").contains("172.31.240.10:8080/healthz");

        runner.ok("podman run", "allowed=000 denied=200 direct=200 gateway=000\n");
        DoctorReport bad = sb.sandboxService().doctor(true);
        assertThat(check(bad, "egress-allowed").level()).isEqualTo(Check.Level.FAIL);
        assertThat(check(bad, "egress-denied").level()).isEqualTo(Check.Level.FAIL);
        assertThat(check(bad, "egress-direct").level()).isEqualTo(Check.Level.FAIL);
        assertThat(check(bad, "egress-gateway").level()).isEqualTo(Check.Level.WARN);
        assertThat(sb.sandboxService().doctor(false).checks()).noneSatisfy(c -> assertThat(c.id()).startsWith("egress"));
    }
}
