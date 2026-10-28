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
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.ProcessRunner;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import no.syntakserror.lyngvi.core.testing.HybridRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class SessionCommandsTest {

    private static final ObjectMapper JSON = new ObjectMapper();


    @TempDir Path tmp;
    Path sb;
    Platform platform;
    HybridRunner runner;

    record Run(int exit, String out, String err) {
        JsonNode json() {
            try {
                return JSON.readTree(out.lines().reduce((a, b) -> b).orElse(""));
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
        runner.fake.ok("podman network ls", "[]").ok("podman ps", "[]").ok("podman run", "").ok("podman start", "")
                .ok("podman stop", "").ok("podman rm", "").ok("podman image exists", "");
        Path repo = Files.createDirectories(tmp.resolve("api"));
        git(runner.real, repo, "init", "-q", "-b", "main");
        git(runner.real, repo, "config", "user.email", "h@example.org");
        git(runner.real, repo, "config", "user.name", "h");
        Files.writeString(repo.resolve("README.md"), "hi\n");
        git(runner.real, repo, "add", "-A");
        git(runner.real, repo, "commit", "-q", "-m", "init");
        assertThat(run("--json", "init", "--repo", repo.toString()).exit()).isZero();
        assertThat(run("--json", "rebuild-config").exit()).isZero();
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
    void newListShowRenameStatus() {
        Run created = run("--json", "session", "new", "--title", "Fix login", "--history", "none");
        assertThat(created.exit()).as(created.err()).isZero();
        String id = created.json().get("id").asText();
        assertThat(created.json().get("repos").get(0).asText()).isEqualTo("api");
        assertThat(created.json().get("base").get("api").get("history").asText()).isEqualTo("none");

        Run text = run("session", "new");
        assertThat(text.exit()).isZero();
        assertThat(text.out()).contains("Session ").contains("api");

        Run list = run("--json", "session", "list");
        assertThat(list.json().get("sessions")).hasSize(2);
        assertThat(list.json().get("sessions").get(1).get("id").asText()).isEqualTo(id);
        assertThat(list.json().get("sessions").get(1).get("container").get("state").asText()).isEmpty();

        Run show = run("--json", "session", "show", id.substring(0, 8));
        assertThat(show.json().get("title").asText()).isEqualTo("Fix login");

        Run renamed = run("--json", "session", "rename", id, "Login redirect");
        assertThat(renamed.json().get("title").asText()).isEqualTo("Login redirect");

        Run status = run("session", "status", id);
        assertThat(status.out()).contains("Login redirect").contains("not created");
    }

    /** A repository the conversation turns out to need is added to it; the copy lands in the session's volume. */
    @Test
    void addRepoPutsAnotherWorkingCopyInASessionThatExists() throws Exception {
        String id = run("--json", "session", "new", "--history", "none").json().get("id").asText();
        Path web = Files.createDirectories(tmp.resolve("web"));
        git(runner.real, web, "init", "-q", "-b", "main");
        git(runner.real, web, "config", "user.email", "h@example.org");
        git(runner.real, web, "config", "user.name", "h");
        Files.writeString(web.resolve("index.html"), "<p>hi</p>\n");
        git(runner.real, web, "add", "-A");
        git(runner.real, web, "commit", "-q", "-m", "init");
        assertThat(run("--json", "repo", "add", web.toString()).exit()).isZero();

        Run added = run("--json", "session", "add-repo", id, "web", "--history", "none");

        assertThat(added.exit()).as(added.err()).isZero();
        assertThat(added.json().get("repos")).hasSize(2);
        assertThat(added.json().get("base").get("web").get("history").asText()).isEqualTo("none");
        assertThat(runner.volumes.dir("lyngvi-sb-" + id.substring(0, 8) + "-work").resolve("web/index.html")).exists();

        // the same repository twice is refused, and the session is as it was
        Run again = run("session", "add-repo", id, "web");
        assertThat(again.exit()).isNotZero();
        assertThat(run("--json", "session", "show", id).json().get("repos")).hasSize(2);
    }

    /** The agent's output is in the session's volume, so the human gets at it with {@code session fetch}. */
    @Test
    void fetchCopiesTheOutputDirectoryOutOfTheSession() throws Exception {
        String id = run("--json", "session", "new", "--history", "none").json().get("id").asText();
        Path volume = runner.volumes.dir("lyngvi-sb-" + id.substring(0, 8) + "-work");
        Files.writeString(volume.resolve("output/report.md"), "# findings\n");
        Files.createSymbolicLink(volume.resolve("output/escape"), Path.of("/etc/passwd"));
        Path dest = tmp.resolve("fetched");

        Run fetched = run("--json", "session", "fetch", id, "--to", dest.toString());

        assertThat(fetched.exit()).as(fetched.err()).isZero();
        assertThat(fetched.json().get("destination").asText()).isEqualTo(dest.toString());
        assertThat(Files.readString(dest.resolve("report.md"))).isEqualTo("# findings\n");
        // a link out of the sandbox is not something to hand the human a copy of
        assertThat(dest.resolve("escape")).doesNotExist();
        assertThat(run("--json", "session", "fetch", id, "--path", "../etc").exit()).isEqualTo(2);
    }

    /** The last resort when a delivery cannot be made to work: the whole session as one zip (spec §5.9). */
    @Test
    void exportWritesTheWholeSessionToAZip() throws Exception {
        String id = run("--json", "session", "new", "--title", "Fix login", "--history", "none").json().get("id").asText();
        Path volume = runner.volumes.dir("lyngvi-sb-" + id.substring(0, 8) + "-work");
        Files.writeString(volume.resolve("api/src.txt"), "new source\n");
        Files.writeString(volume.resolve("output/report.md"), "# findings\n");
        Path zip = tmp.resolve("rescue.zip");

        Run exported = run("--json", "session", "export", id, "--to", zip.toString());

        assertThat(exported.exit()).as(exported.err()).isZero();
        assertThat(exported.json().get("path").asText()).isEqualTo(zip.toString());
        assertThat(exported.json().get("excluded").toString()).contains("node_modules");
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(zip.toFile())) {
            assertThat(archive.stream().map(java.util.zip.ZipEntry::getName))
                    .contains("EXPORT.txt", "api/src.txt", "output/report.md");
        }

        Run text = run("session", "export", id, "--to", tmp.resolve("plain.zip").toString(), "--no-git");
        assertThat(text.exit()).as(text.err()).isZero();
        assertThat(text.out()).contains("Exported").contains("plain.zip").contains(".git");
    }

    /** {@code clean} looks before it leaps: the first run prints, and only {@code --yes} removes. */
    @Test
    void cleanReportsFirstAndRemovesOnlyWithYes() {
        String id = run("--json", "session", "new", "--history", "none").json().get("id").asText();
        String volume = "lyngvi-sb-" + id.substring(0, 8) + "-work";
        assertThat(run("clean").out()).doesNotContain(volume);   // a live session is nobody's to reclaim

        // A volume that outlived its conversation — someone removed the session files by hand, say.
        assertThat(run("--json", "session", "remove", id).exit()).isZero();
        runner.volumes.run(List.of("podman", "volume", "create", "--label", "lyngvi.project=sb",
                "--label", "lyngvi.session=" + id, volume), tmp, Duration.ofSeconds(5));

        Run listed = run("clean");
        assertThat(listed.exit()).isZero();
        assertThat(listed.out()).contains(volume).contains("--yes");
        assertThat(runner.volumes.dir(volume)).isDirectory();

        Run removed = run("--yes", "clean", "--work");
        assertThat(removed.exit()).isZero();
        assertThat(runner.volumes.dir(volume)).doesNotExist();
        assertThat(run("clean").exit()).isEqualTo(7);   // nothing left: the same exit code as an empty review
    }

    @Test
    void aPresetSizesTheSessionAndSingleOptionsOverrideIt() {
        Run created = run("--json", "session", "new", "--preset", "build", "--mem", "10g");

        assertThat(created.exit()).as(created.err()).isZero();
        assertThat(created.json().get("resources").get("mem").asText()).isEqualTo("10g");
        assertThat(created.json().get("resources").get("memswap").asText()).isEqualTo("24g");
        assertThat(created.json().get("resources").get("cpus").asText()).isEqualTo("6.0");
        assertThat(created.json().get("resources").get("pids").asText()).isEqualTo("4096");

        assertThat(run("--json", "session", "new", "--preset", "nope").exit()).isEqualTo(2);
    }

    @Test
    void startStopRemoveAndGc() {
        String id = run("--json", "session", "new").json().get("id").asText();
        String container = "lyngvi-sb-" + id.substring(0, 8);

        Run start = run("--json", "session", "start", id);
        assertThat(start.exit()).as(start.err()).isZero();
        assertThat(runner.fake.calls().stream().anyMatch(c -> c.get(0).equals("podman") && c.get(1).equals("run") && c.contains(container))).isTrue();

        runner.fake.ok("podman ps", "[{\"Names\":[\"" + container + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"}]");
        Run stop = run("--json", "session", "stop", id);
        assertThat(stop.exit()).isZero();
        assertThat(runner.fake.calls()).contains(List.of("podman", "stop", container));

        Run gc = run("--json", "session", "gc", "--idle", "0");
        assertThat(gc.exit()).isZero();

        Run remove = run("--json", "session", "remove", id);
        assertThat(remove.exit()).isZero();
        assertThat(remove.json().get("removed").asBoolean()).isTrue();
        assertThat(run("--json", "session", "list").json().get("sessions")).isEmpty();
        assertThat(runner.fake.calls()).contains(List.of("podman", "rm", "-f", container));
    }

    @Test
    void shellExecsIntoTheContainer() {
        String id = run("--json", "session", "new").json().get("id").asText();
        String container = "lyngvi-sb-" + id.substring(0, 8);
        runner.fake.ok("podman ps", "[{\"Names\":[\"" + container + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"}]");

        Run r = run("shell", id);

        assertThat(r.exit()).isZero();
        assertThat(runner.fake.calls()).contains(List.of("podman", "exec", "-it", "-w", "/work", container, "bash"));
    }

    @Test
    void unknownSessionAndBadOptionsAreErrors() {
        assertThat(run("--json", "session", "show", "nope").exit()).isEqualTo(4);
        assertThat(run("--json", "session", "new", "--repos", "nope").exit()).isEqualTo(4);
        assertThat(run("--json", "session", "new", "--history", "deep").exit()).isEqualTo(2);
        Run noRepos = run("--json", "session", "new", "--no-repos");
        assertThat(noRepos.exit()).isZero();
        assertThat(noRepos.json().get("repos")).isEmpty();
    }
}
