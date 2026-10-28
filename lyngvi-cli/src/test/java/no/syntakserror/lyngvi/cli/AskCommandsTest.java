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

class AskCommandsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tmp;
    Path sb;
    Platform platform;
    HybridRunner runner;
    String session;

    record Run(int exit, String out, String err) {
        JsonNode lastJson() {
            try {
                return JSON.readTree(out.lines().filter(l -> l.startsWith("{")).reduce((a, b) -> b).orElse(""));
            } catch (Exception e) {
                throw new AssertionError("Not JSON: " + out, e);
            }
        }
    }

    private static void git(Runner r, Path repo, String... args) {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        r.run(cmd, repo, Duration.ofSeconds(30)).requireSuccess("git");
    }

    @BeforeEach
    void setUp() throws Exception {
        sb = tmp.resolve("sb");
        platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        runner = new HybridRunner(tmp.resolve("volumes"));
        runner.fake.ok("podman network ls", "[]").ok("podman ps", "[]").ok("podman image exists", "");
        Path repo = Files.createDirectories(tmp.resolve("api"));
        git(runner.real, repo, "init", "-q", "-b", "main");
        git(runner.real, repo, "config", "user.email", "h@example.org");
        git(runner.real, repo, "config", "user.name", "h");
        Files.writeString(repo.resolve("README.md"), "hi\n");
        git(runner.real, repo, "add", "-A");
        git(runner.real, repo, "commit", "-q", "-m", "init");
        assertThat(run("--json", "init", "--repo", repo.toString()).exit()).isZero();
        Path profile = sb.resolve("profile.json");
        Files.writeString(profile, Files.readString(profile).replace("\"enabled\": [\"claude-code\", \"opencode\"]", "\"enabled\": [\"claude-code\", \"opencode\", \"mock\"]"));
        assertThat(run("--json", "rebuild-config").exit()).isZero();
        session = run("--json", "session", "new", "--agent", "mock", "--history", "none", "--title", "Fix login").lastJson().get("id").asText();
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
    void askStreamsJsonlAndExitsZero() throws Exception {
        Path prompt = Files.writeString(tmp.resolve("p.txt"), "add a file");

        Run r = run("--json", "session", "ask", session, "--prompt-file", prompt.toString());

        assertThat(r.exit()).as(r.err()).isZero();
        List<String> lines = r.out().lines().toList();
        assertThat(lines).hasSize(7);
        assertThat(JSON.readTree(lines.getFirst()).get("type").asText()).isEqualTo("start");
        assertThat(JSON.readTree(lines.getFirst()).get("seq").asInt()).isEqualTo(1);
        assertThat(JSON.readTree(lines.getLast()).get("type").asText()).isEqualTo("done");
        assertThat(run("--json", "session", "show", session).lastJson().get("messages")).hasSize(2);
        assertThat(runner.volumes.dir("lyngvi-sb-" + session.substring(0, 8) + "-work").resolve("api/MOCK.md")).exists();
    }

    @Test
    void askInTextModePrintsTheReplyAndToolLines() throws Exception {
        Path prompt = Files.writeString(tmp.resolve("p.txt"), "add a file");

        Run r = run("session", "ask", session, "--prompt-file", prompt.toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("I will look at the repository.").contains("[tool] write_file /work/api/MOCK.md").contains("Done: wrote MOCK.md");
    }

    @Test
    void askFailureExitsWithOne() throws Exception {
        Path prompt = Files.writeString(tmp.resolve("p.txt"), "please fail");

        Run r = run("--json", "session", "ask", session, "--prompt-file", prompt.toString());

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.lastJson().get("stopReason").asText()).isEqualTo("error");
    }

    @Test
    void suggestReturnsBranchAndMessage() {
        Run r = run("--json", "session", "suggest", session);

        assertThat(r.exit()).isZero();
        assertThat(r.lastJson().get("branch").asText()).isEqualTo("agent/fix-login");
        assertThat(run("session", "suggest", session).out()).contains("agent/fix-login");
    }

    @Test
    void agentCommands() {
        Run list = run("--json", "agent", "list");
        assertThat(list.exit()).isZero();
        assertThat(list.lastJson().get("agents").findValuesAsText("id")).contains("mock", "mock-sh", "claude-code");

        Run text = run("agent", "list");
        assertThat(text.out()).contains("mock").contains("Mock agent");

        Run status = run("--json", "agent", "status", "mock");
        assertThat(status.lastJson().get("loggedIn").asBoolean()).isTrue();

        Run models = run("--json", "agent", "models", "mock");
        assertThat(models.lastJson().get("models").get(0).get("id").asText()).isEqualTo("mock-1");

        Run claude = run("--json", "agent", "models", "claude-code");
        assertThat(claude.exit()).isZero();
        assertThat(claude.lastJson().get("models").get(0).get("id").asText()).isEqualTo("sonnet");
        assertThat(run("--json", "agent", "status", "claude-code").lastJson().get("loggedIn").asBoolean()).isFalse();
        assertThat(run("--json", "agent", "models", "ghost").exit()).isEqualTo(4);
    }
}
