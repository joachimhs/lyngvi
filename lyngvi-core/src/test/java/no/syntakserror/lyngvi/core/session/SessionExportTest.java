package no.syntakserror.lyngvi.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.ExportOptions;
import no.syntakserror.lyngvi.core.api.InitOptions;
import no.syntakserror.lyngvi.core.api.NewSessionOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.contract.ExportResult;
import no.syntakserror.lyngvi.core.render.Renderer;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.HybridRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code session export} (spec §5.9): the whole session as one zip on the host. Real git in the working
 * copy, real {@code zip} in the helper, scripted podman.
 */
class SessionExportTest {

    @TempDir Path tmp;
    HybridRunner runner;
    Sandbox sandbox;
    Path host;

    @BeforeEach
    void setUp() throws Exception {
        runner = new HybridRunner(tmp.resolve("volumes"));
        runner.fake.ok("podman network ls", "[]").ok("podman ps", "[]").ok("podman image exists", "");
        host = Files.createDirectories(tmp.resolve("api"));
        WorkspaceTest.git(runner.real, host, "init", "-q", "-b", "main");
        WorkspaceTest.git(runner.real, host, "config", "user.email", "h@example.org");
        WorkspaceTest.git(runner.real, host, "config", "user.name", "h");
        Files.writeString(host.resolve("README.md"), "# api\n");
        WorkspaceTest.git(runner.real, host, "add", "-A");
        WorkspaceTest.git(runner.real, host, "commit", "-q", "-m", "init");
        Platform platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(host), Optional.empty(), false), platform, runner);
        sandbox = Sandbox.open(tmp.resolve("sb"), platform, runner);
        new Renderer(sandbox).render();
    }

    private Conversation session(boolean withRepo) {
        return sandbox.sessionService().create(new NewSessionOptions(Optional.of("Fix login"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(History.NONE), Optional.empty(), Optional.empty(), !withRepo));
    }

    private Path volume(Conversation c) {
        return runner.volumes.dir(c.container() + "-work");
    }

    /** What the agent left behind: source, output, a build directory, and a link pointing out of the sandbox. */
    private void agentWork(Conversation c) throws Exception {
        Path work = volume(c);
        Files.writeString(work.resolve("api/src.txt"), "new source\n");
        Files.createDirectories(work.resolve("api/node_modules/pkg"));
        Files.writeString(work.resolve("api/node_modules/pkg/index.js"), "junk\n");
        Files.createSymbolicLink(work.resolve("api/escape"), Path.of("/etc/passwd"));
        Files.writeString(work.resolve("output/report.md"), "# findings\n");
    }

    private static List<String> entries(ExportResult r) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(Path.of(r.path()).toFile())) {
            zip.stream().filter(e -> !e.isDirectory()).forEach(e -> names.add(e.getName()));
        }
        return names;
    }

    /** Unpacks the archive, so what the human gets can be used the way the archive's own note says. */
    private static Path extract(ExportResult r, Path into) throws Exception {
        try (ZipFile zip = new ZipFile(Path.of(r.path()).toFile())) {
            for (var it = zip.entries().asIterator(); it.hasNext(); ) {
                var e = it.next();
                Path out = into.resolve(e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (var in = zip.getInputStream(e)) {
                        Files.copy(in, out);
                    }
                }
            }
        }
        return into;
    }

    private static String read(ExportResult r, String entry) throws Exception {
        try (ZipFile zip = new ZipFile(Path.of(r.path()).toFile())) {
            return new String(zip.getInputStream(zip.getEntry(entry)).readAllBytes());
        }
    }

    @Test
    void exportHoldsEveryWorkingCopyAndTheAgentsOutput() throws Exception {
        Conversation c = session(true);
        agentWork(c);
        Path zip = tmp.resolve("out/session.zip");

        ExportResult r = sandbox.sessionService().export(c.id(), ExportOptions.to(zip));

        assertThat(r.path()).isEqualTo(zip.toString());
        assertThat(zip).isRegularFile();
        assertThat(r.bytes()).isEqualTo(Files.size(zip));
        assertThat(r.files()).isEqualTo(entries(r).size());
        assertThat(r.partial()).isFalse();
        assertThat(r.roots()).containsExactly("api", "output");
        assertThat(entries(r)).contains("api/src.txt", "api/README.md", "output/report.md", SessionExport.NOTE);
    }

    /** The history is what makes the archive a way back: the agent's commits, and a diff against the baseline. */
    @Test
    void exportKeepsTheWorkingCopysGitHistory() throws Exception {
        Conversation c = session(true);
        agentWork(c);

        ExportResult r = sandbox.sessionService().export(c.id(), ExportOptions.to(tmp.resolve("with-git.zip")));

        assertThat(entries(r)).anyMatch(e -> e.startsWith("api/.git/"));
        assertThat(r.excluded()).doesNotContain(".git");
        Path extracted = extract(r, tmp.resolve("extracted"));
        assertThat(WorkspaceTest.git(runner.real, extracted.resolve("api"), "log", "--oneline")).contains("lyngvi: baseline");
        assertThat(WorkspaceTest.git(runner.real, extracted.resolve("api"), "status", "--porcelain")).contains("src.txt");
    }

    /**
     * The copy's {@code .git} is the agent's to write, and this archive is extracted on the human's machine,
     * where the note tells them to run git in it: the two places a repository keeps programs for git to run
     * do not travel (spec §5.9).
     */
    @Test
    void exportLeavesTheCopysGitHooksAndConfigBehind() throws Exception {
        Conversation c = session(true);
        agentWork(c);
        Path git = volume(c).resolve("api/.git");
        Files.createDirectories(git.resolve("hooks"));
        Files.writeString(git.resolve("hooks/post-checkout"), "#!/bin/sh\ntouch /tmp/pwned\n");
        Files.writeString(git.resolve("config"), "[core]\n\tfsmonitor = touch /tmp/pwned\n");

        ExportResult r = sandbox.sessionService().export(c.id(), ExportOptions.to(tmp.resolve("hooks.zip")));

        assertThat(entries(r)).contains("api/.git/HEAD")
                .doesNotContain("api/.git/config", "api/.git/hooks/post-checkout")
                .noneMatch(e -> e.startsWith("api/.git/hooks/"));
        assertThat(read(r, SessionExport.NOTE)).contains("hooks/ and config");
    }

    @Test
    void exportWithoutGitLeavesTheHistoryOut() throws Exception {
        Conversation c = session(true);
        agentWork(c);

        ExportResult r = sandbox.sessionService().export(c.id(),
                new ExportOptions(tmp.resolve("no-git.zip"), false, false));

        assertThat(entries(r)).contains("api/src.txt").noneMatch(e -> e.startsWith("api/.git/"));
        assertThat(r.excluded()).contains(".git");
        assertThat(read(r, SessionExport.NOTE)).contains(".git directories");
    }

    /** A link out of the sandbox is not something to hand the human a copy of; the same rule as {@code session fetch}. */
    @Test
    void exportLeavesSymbolicLinksBehind() throws Exception {
        Conversation c = session(true);
        agentWork(c);

        ExportResult r = sandbox.sessionService().export(c.id(), ExportOptions.to(tmp.resolve("links.zip")));

        assertThat(entries(r)).doesNotContain("api/escape");
    }

    /** Build output never came into the sandbox either: the profile's {@code copy.excludes} holds both ways. */
    @Test
    void exportLeavesBuildOutputOutUnlessItIsAskedFor() throws Exception {
        Conversation c = session(true);
        agentWork(c);

        ExportResult lean = sandbox.sessionService().export(c.id(), ExportOptions.to(tmp.resolve("lean.zip")));
        ExportResult all = sandbox.sessionService().export(c.id(),
                new ExportOptions(tmp.resolve("all.zip"), true, true));

        assertThat(entries(lean)).doesNotContain("api/node_modules/pkg/index.js");
        assertThat(lean.excluded()).contains("node_modules", "target");
        assertThat(entries(all)).contains("api/node_modules/pkg/index.js");
        assertThat(all.excluded()).isEmpty();
    }

    /** Whoever opens the archive, weeks later, should not have to ask what they are looking at. */
    @Test
    void theArchiveSaysWhatItIsAndHowToGetTheWorkOutOfIt() throws Exception {
        Conversation c = session(true);
        agentWork(c);

        ExportResult r = sandbox.sessionService().export(c.id(), ExportOptions.to(tmp.resolve("note.zip")));

        String note = read(r, SessionExport.NOTE);
        assertThat(note).contains("Fix login (" + c.shortId() + ")")
                .contains("api/  working copy of the repository on branch main")
                .contains("output/")
                .contains("git diff " + c.base().get("api").baseCommit().substring(0, 12))
                .contains("Symbolic links");
    }

    @Test
    void exportToADirectoryNamesTheArchiveAfterTheSession() throws Exception {
        Conversation c = session(true);
        Path dir = Files.createDirectories(tmp.resolve("downloads"));

        ExportResult r = sandbox.sessionService().export(c.id(), ExportOptions.to(dir));

        assertThat(Path.of(r.path())).isRegularFile().hasParent(dir);
        assertThat(Path.of(r.path()).getFileName().toString()).startsWith("lyngvi-fix-login-").endsWith(".zip");
    }

    @Test
    void exportSaysSoWhenTheSessionHasNoFilesAtAll() throws Exception {
        Conversation c = session(false);
        Files.delete(volume(c).resolve("output"));

        assertThatThrownBy(() -> sandbox.sessionService().export(c.id(), ExportOptions.to(tmp.resolve("empty.zip"))))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("no files to export")
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.NO_CHANGES));
        assertThat(tmp.resolve("empty.zip")).doesNotExist();
    }

    /** Exporting is a copy and nothing else: the next delivery is the same delivery it was. */
    @Test
    void exportChangesNothingInTheSession() throws Exception {
        Conversation c = session(true);
        agentWork(c);
        int waiting = sandbox.deliveryService().review(c.id(), Optional.empty()).totalFiles();

        sandbox.sessionService().export(c.id(), ExportOptions.to(tmp.resolve("twice.zip")));

        Conversation after = sandbox.sessionService().show(c.id());
        assertThat(after.deliveries()).isEmpty();
        assertThat(after.base()).isEqualTo(c.base());
        assertThat(sandbox.deliveryService().review(c.id(), Optional.empty()).totalFiles()).isEqualTo(waiting);
    }

    @Test
    void theDefaultFileNameIsTheTitleAndTheTime() {
        assertThat(SessionExport.fileName("Fix the login flow", 1_760_000_000_000L)).matches("lyngvi-fix-the-login-flow-\\d{8}-\\d{4}\\.zip");
        assertThat(SessionExport.fileName("  ", 0)).startsWith("lyngvi-export-");
        assertThat(SessionExport.fileName("A very long session title that nobody would ever type by hand", 0))
                .startsWith("lyngvi-a-very-long-session-title-that-");
    }
}
