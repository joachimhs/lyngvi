package no.syntakserror.lyngvi.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.testing.HybridRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class DeliveryCommandsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tmp;
    Path sb;
    Path host;
    Platform platform;
    HybridRunner runner;
    String session;

    record Run(int exit, String out, String err) {
        JsonNode json() {
            try {
                return JSON.readTree(out.lines().filter(l -> l.startsWith("{")).reduce((a, b) -> b).orElse(""));
            } catch (Exception e) {
                throw new AssertionError("Not JSON: " + out, e);
            }
        }
    }

    private static String git(Runner r, Path repo, String... args) {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        return r.run(cmd, repo, Duration.ofSeconds(30)).requireSuccess("git").stdout().strip();
    }

    @BeforeEach
    void setUp() throws Exception {
        sb = tmp.resolve("sb");
        platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        runner = new HybridRunner(tmp.resolve("volumes"));
        runner.fake.ok("podman network ls", "[]").ok("podman ps", "[]").ok("podman image exists", "");
        host = Files.createDirectories(tmp.resolve("api"));
        git(runner.real, host, "init", "-q", "-b", "main");
        git(runner.real, host, "config", "user.email", "h@example.org");
        git(runner.real, host, "config", "user.name", "h");
        Files.writeString(host.resolve("README.md"), "hi\n");
        git(runner.real, host, "add", "-A");
        git(runner.real, host, "commit", "-q", "-m", "init");
        assertThat(run("--json", "init", "--repo", host.toString()).exit()).isZero();
        assertThat(run("--json", "rebuild-config").exit()).isZero();
        session = run("--json", "session", "new", "--history", "none", "--title", "Fix login").json().get("id").asText();
        Path copy = runner.volumes.dir("lyngvi-sb-" + session.substring(0, 8) + "-work").resolve("api");
        Files.writeString(copy.resolve("README.md"), "hi\nimproved\n");
        Files.writeString(copy.resolve("new.txt"), "new\n");
        Files.writeString(copy.resolve("run.sh"), "#!/bin/sh\n");
        copy.resolve("run.sh").toFile().setExecutable(true);
    }

    private Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = Main.commandLine(platform, runner);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        String[] full = new String[args.length + 2];
        full[0] = "--sandbox"; full[1] = sb.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return new Run(cmd.execute(full), out.toString(), err.toString());
    }

    @Test
    void diffPrintsTheRawPatch() {
        Run r = run("session", "diff", session);

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("diff --git a/README.md b/README.md").contains("+improved").contains("new.txt");

        Run one = run("session", "diff", session, "--path", "new.txt");
        assertThat(one.out()).contains("new.txt").doesNotContain("README.md");
    }

    @Test
    void reviewShowsBucketsInJsonAndText() {
        Run r = run("--json", "session", "review", session);

        assertThat(r.exit()).as(r.err()).isZero();
        JsonNode api = r.json().get("repos").get("api");
        assertThat(api.get("host").get("branch").asText()).isEqualTo("main");
        assertThat(api.get("host").get("isProtected").asBoolean()).isTrue();
        assertThat(api.get("files").findValuesAsText("path")).containsExactlyInAnyOrder("README.md", "new.txt", "run.sh");
        assertThat(r.json().get("from").get("kind").asText()).isEqualTo("start");

        Run text = run("session", "review", session);
        assertThat(text.out()).contains("apply").contains("hold").contains("run.sh").contains("EXECUTABLE");
    }

    @Test
    void applyDeliversToABranchAndRecordsIt() {
        Run r = run("--json", "session", "apply", session, "--branch", "agent/fix", "--message", "Fix login");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.json().get("repos").get("api").get("files").asInt()).isEqualTo(2);
        assertThat(r.json().get("repos").get("api").get("branch").asText()).isEqualTo("agent/fix");
        assertThat(git(runner.real, host, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("agent/fix");
        assertThat(git(runner.real, host, "log", "-1", "--format=%s")).isEqualTo("Fix login");
        assertThat(host.resolve("run.sh")).doesNotExist();

        Run text = run("session", "apply", session, "--branch", "agent/fix", "--message", "Add script", "--include", "run.sh");
        assertThat(text.exit()).isZero();
        assertThat(text.out()).contains("agent/fix").contains("1 file");
        assertThat(host.resolve("run.sh")).exists();

        Run none = run("--json", "session", "review", session);
        assertThat(none.exit()).isEqualTo(7);
        assertThat(none.json().get("error").get("code").asText()).isEqualTo("NO_CHANGES");
        assertThat(none.json().get("error").get("data").get("alternatives").get(0).get("from").asText()).isEqualTo("start");
    }

    @Test
    void applyRefusesProtectedBranchesAndBadArguments() {
        assertThat(run("--json", "session", "apply", session, "--branch", "main", "--message", "x").exit()).isEqualTo(4);
        assertThat(run("--json", "session", "apply", session, "--branch", "agent/x").exit()).isEqualTo(2);
        assertThat(run("--json", "session", "apply", session, "--branch", "agent/x", "--message", "x", "--mode", "yolo").exit()).isEqualTo(2);
        assertThat(git(runner.real, host, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("main");
    }
}
