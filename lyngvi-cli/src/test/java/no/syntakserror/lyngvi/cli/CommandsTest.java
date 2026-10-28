package no.syntakserror.lyngvi.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import no.syntakserror.lyngvi.core.runtime.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CommandsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tmp;
    Path sb;
    FakeRunner runner;
    /** Points XDG_STATE_HOME at the temp dir so the subnet registry never touches the developer's own state. */
    Platform PLATFORM;

    record Run(int exit, String out, String err) {
        JsonNode json() {
            try {
                return JSON.readTree(out);
            } catch (Exception e) {
                throw new AssertionError("Not JSON: " + out, e);
            }
        }
    }

    @BeforeEach
    void setUp() {
        sb = tmp.resolve("sb");
        PLATFORM = new Platform(Platform.Os.LINUX, Path.of(System.getProperty("user.home")),
                Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        runner = new FakeRunner()
                .ok("podman --version", "podman version 5.3.1\n")
                .ok("podman compose version", "1.2.0\n")
                .ok("git --version", "git version 2.50.1\n")
                .ok("openssl version", "OpenSSL 3.6.3\n")
                .ok("git -C", "true\n")
                .ok("openssl req", "").ok("openssl x509", "")
                .ok("podman network ls", "[]")
                .ok("podman image exists", "");
    }

    private Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = Main.commandLine(PLATFORM, runner);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        int exit = cmd.execute(args);
        return new Run(exit, out.toString(), err.toString());
    }

    private Run init(String... extra) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("api"));
        String[] args = new String[6 + extra.length];
        args[0] = "--sandbox"; args[1] = sb.toString(); args[2] = "--json"; args[3] = "init"; args[4] = "--repo"; args[5] = repo.toString();
        System.arraycopy(extra, 0, args, 6, extra.length);
        return run(args);
    }

    @Test
    void initCreatesASandboxAndReportsIt() throws Exception {
        Run r = init();

        assertThat(r.err()).isEmpty();
        assertThat(r.exit()).isZero();
        JsonNode j = r.json();
        assertThat(j.get("ok").asBoolean()).isTrue();
        assertThat(j.get("sandboxDir").asText()).isEqualTo(sb.toAbsolutePath().toString());
        assertThat(j.get("profile").get("name").asText()).isEqualTo("default");
        assertThat(j.get("repos").get(0).get("name").asText()).isEqualTo("api");
        assertThat(sb.resolve("sandbox.config")).isRegularFile();
    }

    @Test
    void initWithoutJsonPrintsHumanText() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("api"));

        Run r = run("--sandbox", sb.toString(), "init", "--repo", repo.toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("Sandbox is ready in " + sb.toAbsolutePath())
                .contains("Profile: default")
                .contains("api -> /work/api");
    }

    @Test
    void initTwiceFailsWithoutYesAndSucceedsWithIt() throws Exception {
        init();

        Run again = init();
        assertThat(again.exit()).isEqualTo(4);
        assertThat(again.json().get("error").get("code").asText()).isEqualTo("VALIDATION_FAILED");

        Run forced = init("--yes");
        assertThat(forced.exit()).isZero();
    }

    @Test
    void commandsOnAnUninitialisedDirectoryExitWithThree() {
        Run r = run("--sandbox", sb.toString(), "--json", "status");

        assertThat(r.exit()).isEqualTo(3);
        assertThat(r.json().get("error").get("code").asText()).isEqualTo("NOT_INITIALIZED");
    }

    @Test
    void statusReportsTheConfiguration() throws Exception {
        init();

        Run r = run("--sandbox", sb.toString(), "--json", "status");

        assertThat(r.exit()).isZero();
        JsonNode j = r.json();
        assertThat(j.get("uid").asInt()).isEqualTo(1234);
        assertThat(j.get("resources").get("mem").asText()).isEqualTo("8g");
        assertThat(j.get("repos").get(0).get("name").asText()).isEqualTo("api");
        assertThat(j.get("subnetPrefix").isNull()).isTrue();

        Run text = run("--sandbox", sb.toString(), "status");
        assertThat(text.out()).contains("UID/GID: 1234/5678").contains("api -> /work/api");
    }

    @Test
    void doctorExitsWithOneWhenAnythingFails() throws Exception {
        init();
        run("--sandbox", sb.toString(), "rebuild-config");
        runner.on("git --version", 0, "git version 2.20.0\n", "");

        Run r = run("--sandbox", sb.toString(), "--json", "doctor");

        assertThat(r.exit()).isEqualTo(1);
        JsonNode j = r.json();
        assertThat(j.get("ok").asBoolean()).isTrue();
        assertThat(j.get("fail").asInt()).isEqualTo(1);
        assertThat(j.get("checks").findValuesAsText("id")).contains("git", "repo:api");

        Run text = run("--sandbox", sb.toString(), "doctor");
        assertThat(text.exit()).isEqualTo(1);
        assertThat(text.out()).contains("git 2.20.0").contains("1 failures");
    }

    @Test
    void doctorExitsWithZeroOnAHealthyHost() throws Exception {
        init();
        run("--sandbox", sb.toString(), "rebuild-config");

        Run r = run("--sandbox", sb.toString(), "doctor");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("0 failures");
    }

    @Test
    void repoAddListAndRemove() throws Exception {
        init();
        Path web = Files.createDirectories(tmp.resolve("web"));

        Run add = run("--sandbox", sb.toString(), "--json", "repo", "add", web.toString(), "--name", "frontend", "--history", "none");
        assertThat(add.exit()).isZero();
        assertThat(add.json().get("name").asText()).isEqualTo("frontend");
        assertThat(add.json().get("history").asText()).isEqualTo("none");

        Run list = run("--sandbox", sb.toString(), "--json", "repo", "list");
        assertThat(list.json().get("repos").findValuesAsText("name")).containsExactly("api", "frontend");

        Run text = run("--sandbox", sb.toString(), "repo", "list");
        assertThat(text.out()).contains("frontend");

        Run remove = run("--sandbox", sb.toString(), "--json", "repo", "remove", "frontend");
        assertThat(remove.exit()).isZero();
        assertThat(run("--sandbox", sb.toString(), "--json", "repo", "list").json().get("repos")).hasSize(1);
    }

    @Test
    void repoAddRejectsAReservedName() throws Exception {
        init();
        Path web = Files.createDirectories(tmp.resolve("web"));

        Run r = run("--sandbox", sb.toString(), "--json", "repo", "add", web.toString(), "--name", "output");

        assertThat(r.exit()).isEqualTo(4);
        assertThat(r.json().get("error").get("message").asText()).contains("reserved");
    }

    @Test
    void resourcesShowAndSet() throws Exception {
        init();

        Run set = run("--sandbox", sb.toString(), "--json", "resources", "set", "--mem", "16g", "--pids", "4096");
        assertThat(set.exit()).isZero();
        assertThat(set.json().get("mem").asText()).isEqualTo("16g");
        assertThat(set.json().get("memswap").asText()).isEqualTo("12g");

        Run show = run("--sandbox", sb.toString(), "--json", "resources", "show");
        assertThat(show.json().get("pids").asText()).isEqualTo("4096");

        Run bad = run("--sandbox", sb.toString(), "--json", "resources", "set", "--cpus", "many");
        assertThat(bad.exit()).isEqualTo(4);

        Run none = run("--sandbox", sb.toString(), "--json", "resources", "set");
        assertThat(none.exit()).isEqualTo(2);
    }

    @Test
    void resourcesPresetsListAndSetTheWholeSetAtOnce() throws Exception {
        init();

        Run presets = run("--sandbox", sb.toString(), "--json", "resources", "presets");
        assertThat(presets.exit()).isZero();
        assertThat(presets.json().get("presets").findValuesAsText("id")).contains("review", "dev", "build");

        // The preset sets all four; --cpus on top of it wins.
        Run set = run("--sandbox", sb.toString(), "--json", "resources", "set", "--preset", "review", "--cpus", "2.0");
        assertThat(set.exit()).isZero();
        assertThat(set.json().get("mem").asText()).isEqualTo("2g");
        assertThat(set.json().get("memswap").asText()).isEqualTo("3g");
        assertThat(set.json().get("pids").asText()).isEqualTo("1024");
        assertThat(set.json().get("cpus").asText()).isEqualTo("2.0");

        Run unknown = run("--sandbox", sb.toString(), "--json", "resources", "set", "--preset", "nope");
        assertThat(unknown.exit()).isEqualTo(2);
        assertThat(unknown.json().get("error").get("message").asText()).contains("review", "dev", "build");

        Run text = run("--sandbox", sb.toString(), "resources", "presets");
        assertThat(text.out()).contains("review").contains("mem=2g memswap=3g cpus=1.0 pids=1024");
    }

    @Test
    void profileShow() throws Exception {
        init();

        Run r = run("--sandbox", sb.toString(), "--json", "profile", "show");

        assertThat(r.exit()).isZero();
        assertThat(r.json().get("name").asText()).isEqualTo("default");
        assertThat(r.json().get("pack").asText()).endsWith("profiles/default");
    }

    @Test
    void sandboxDirDefaultsToTheHomeEnvironmentVariable() throws Exception {
        init();
        Platform withHome = new Platform(Platform.Os.LINUX, PLATFORM.home(), Map.of("LYNGVI_HOME", sb.toString()), 1234, 5678);
        StringWriter out = new StringWriter();
        CommandLine cmd = Main.commandLine(withHome, runner);
        cmd.setOut(new PrintWriter(out, true));

        int exit = cmd.execute("--json", "status");

        assertThat(exit).isZero();
        assertThat(JSON.readTree(out.toString()).get("sandboxDir").asText()).isEqualTo(sb.toAbsolutePath().toString());
    }

    @Test
    void protectedBranchesAreEditedPerSandbox() {
        run("--sandbox", sb.toString(), "--json", "init");
        Run r = run("--sandbox", sb.toString(), "--json", "profile", "protected", "remove", "main");
        assertThat(r.exit()).isZero();
        assertThat(r.json().get("protectedBranches").findValuesAsText("")).isEmpty();
        assertThat(r.json().get("protectedBranches").toString()).doesNotContain("\"main\"").contains("\"master\"");
        Run add = run("--sandbox", sb.toString(), "--json", "profile", "protected", "add", "release");
        assertThat(add.json().get("protectedBranches").toString()).contains("\"release\"");
        assertThat(run("--sandbox", sb.toString(), "profile", "protected", "list").out()).contains("release").doesNotContain("main\n");
    }

    @Test
    void migrateConvertsAOneOneTwoSandbox() throws Exception {
        Path fixture = Path.of("..", "lyngvi-core", "src", "test", "resources", "migrate", "112").toAbsolutePath().normalize();
        try (var files = Files.walk(fixture)) {
            for (Path p : files.toList()) {
                Path target = sb.resolve(fixture.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target);
            }
        }
        Run blocked = run("--sandbox", sb.toString(), "--json", "status");
        assertThat(blocked.exit()).isEqualTo(3);
        assertThat(blocked.json().get("error").get("data").get("code").asText()).isEqualTo("MIGRATION_REQUIRED");

        Run r = run("--sandbox", sb.toString(), "--json", "migrate");
        assertThat(r.exit()).isZero();
        assertThat(r.json().get("migratedConversations").asInt()).isEqualTo(1);
        assertThat(r.json().get("gatewayInstances").asInt()).isEqualTo(2);
        assertThat(run("--sandbox", sb.toString(), "--json", "status").exit()).isZero();
        assertThat(run("--sandbox", sb.toString(), "migrate").out()).contains("Already migrated");
    }
}
