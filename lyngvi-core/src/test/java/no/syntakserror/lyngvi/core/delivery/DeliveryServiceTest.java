package no.syntakserror.lyngvi.core.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.api.ApplyOptions;
import no.syntakserror.lyngvi.core.api.DeliveryService;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.InitOptions;
import no.syntakserror.lyngvi.core.api.NewSessionOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.contract.ApplyResult;
import no.syntakserror.lyngvi.core.contract.FileChange;
import no.syntakserror.lyngvi.core.contract.ReviewResult;
import no.syntakserror.lyngvi.core.render.Renderer;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.testing.HybridRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real git on both sides; no Podman. */
class DeliveryServiceTest {

    @TempDir Path tmp;
    HybridRunner runner;
    Sandbox sandbox;
    DeliveryService delivery;
    Path host;

    static String git(Runner r, Path repo, String... args) {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        return r.run(cmd, repo, Duration.ofSeconds(30)).requireSuccess("git " + args[0]).stdout().strip();
    }

    @BeforeEach
    void setUp() throws Exception {
        runner = new HybridRunner(tmp.resolve("volumes"));
        runner.fake.ok("podman network ls", "[]").ok("podman ps", "[]").ok("podman image exists", "");
        host = Files.createDirectories(tmp.resolve("api"));
        git(runner.real, host, "init", "-q", "-b", "main");
        git(runner.real, host, "config", "user.email", "h@example.org");
        git(runner.real, host, "config", "user.name", "h");
        Files.writeString(host.resolve("README.md"), "# api\n\nline two\n");
        Files.writeString(host.resolve("keep.txt"), "keep\n");
        git(runner.real, host, "add", "-A");
        git(runner.real, host, "commit", "-q", "-m", "init");
        Platform platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(host), Optional.empty(), false), platform, runner);
        sandbox = Sandbox.open(tmp.resolve("sb"), platform, runner);
        new Renderer(sandbox).render();
        delivery = sandbox.deliveryService();
    }

    private Conversation session(History history) {
        return sandbox.sessionService().create(new NewSessionOptions(Optional.of("Fix login"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(history), Optional.empty(), Optional.empty(), false));
    }

    /** The working copy as the test sees it: the volume is a directory on disk here, and git runs in it for real. */
    private Path copy(Conversation c) {
        return runner.volumes.dir(c.container() + "-work").resolve("api");
    }

    /** The agent's work: one committed change, one uncommitted change, one untracked file, one executable. */
    private void agentWork(Conversation c) throws Exception {
        Path w = copy(c);
        Files.writeString(w.resolve("README.md"), "# api\n\nline two, improved\n");
        git(runner.real, w, "add", "-A");
        git(runner.real, w, "commit", "-q", "-m", "agent: improve readme");
        Files.writeString(w.resolve("src.txt"), "new source\n");
        Files.writeString(w.resolve("run.sh"), "#!/bin/sh\necho hi\n");
        w.resolve("run.sh").toFile().setExecutable(true);
        Files.writeString(w.resolve("keep.txt"), "keep\nmore\n");
    }

    private static FileChange file(ReviewResult r, String path) {
        return r.repos().get("api").files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void reviewClassifiesEverythingSinceTheBaseline() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);

        ReviewResult r = delivery.review(c.id(), Optional.empty());

        assertThat(r.from().kind()).isEqualTo("start");
        assertThat(r.repos().get("api").host().branch()).isEqualTo("main");
        assertThat(r.repos().get("api").host().isProtected()).isTrue();
        assertThat(r.repos().get("api").host().dirty()).isFalse();
        assertThat(r.repos().get("api").files()).extracting(FileChange::path).containsExactlyInAnyOrder("README.md", "src.txt", "run.sh", "keep.txt");
        assertThat(file(r, "README.md").bucket()).isEqualTo("apply");
        assertThat(file(r, "README.md").change()).isEqualTo("modified");
        assertThat(file(r, "src.txt").change()).isEqualTo("added");
        assertThat(file(r, "run.sh").bucket()).isEqualTo("hold");
        assertThat(file(r, "run.sh").reason()).isEqualTo("EXECUTABLE");
        assertThat(r.repos().get("api").files()).allMatch(f -> !f.conflict());
        assertThat(Files.readString(Path.of(r.repos().get("api").patch()))).contains("diff --git a/README.md b/README.md");
        assertThat(delivery.diff(c.id(), Optional.empty(), Optional.empty(), Optional.of("README.md"))).contains("+line two, improved").doesNotContain("src.txt");
    }

    @Test
    void diffCanCarryWholeFilesForAViewer() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 40; i++) text.append("line ").append(i).append('\n');
        Files.writeString(host.resolve("keep.txt"), text.toString());
        git(runner.real, host, "add", "-A");
        git(runner.real, host, "commit", "-q", "-m", "grow keep.txt");
        Conversation c = session(History.NONE);
        Files.writeString(copy(c).resolve("keep.txt"), text.toString().replace("line 40\n", "line forty\n"));

        String hunks = delivery.diff(c.id(), Optional.empty(), Optional.empty(), Optional.of("keep.txt"));
        String whole = delivery.diff(c.id(), Optional.empty(), Optional.empty(), Optional.of("keep.txt"), true);

        assertThat(hunks).contains("-line 40", "+line forty").doesNotContain(" line 1\n");
        assertThat(whole).contains("-line 40", "+line forty", " line 1\n", " line 39\n").startsWith("diff --git a/keep.txt b/keep.txt");
        assertThat(whole.lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(1);
    }

    @Test
    void applyCommitsOnlySelectedFilesToAWorkingBranchAndRecordsTheDelivery() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);

        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/fix-login", Optional.of("Fix login"), "commit", List.of(), List.of("README.md", "src.txt"), Optional.empty()));

        ApplyResult.RepoOutcome out = a.repos().get("api");
        assertThat(out.files()).isEqualTo(2);
        assertThat(out.branch()).isEqualTo("agent/fix-login");
        assertThat(out.stashed()).isFalse();
        assertThat(git(runner.real, host, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("agent/fix-login");
        assertThat(git(runner.real, host, "log", "-1", "--format=%s")).isEqualTo("Fix login");
        assertThat(git(runner.real, host, "rev-parse", "HEAD")).isEqualTo(out.hostCommit());
        assertThat(Files.readString(host.resolve("README.md"))).contains("improved");
        assertThat(host.resolve("src.txt")).exists();
        assertThat(host.resolve("run.sh")).doesNotExist();
        assertThat(Files.readString(host.resolve("keep.txt"))).isEqualTo("keep\n");
        assertThat(git(runner.real, host, "status", "--porcelain")).isEmpty();

        Conversation after = sandbox.sessionService().show(c.id());
        assertThat(after.deliveries()).hasSize(1);
        assertThat(after.deliveries().getFirst().repos().get("api").copyCommit()).isEqualTo(out.copyCommit());
        assertThat(git(runner.real, copy(c), "rev-parse", "HEAD")).isEqualTo(out.copyCommit());

        ReviewResult next = delivery.review(c.id(), Optional.empty());
        assertThat(next.from().kind()).isEqualTo("last");
        assertThat(next.repos().get("api").files()).extracting(FileChange::path).containsExactlyInAnyOrder("run.sh", "keep.txt");
    }

    /**
     * The working copy's {@code .git} is the agent's to write, and a delivery commits in it. A hook or a
     * {@code core.fsmonitor} planted there must never be run on our behalf (spec §5.2).
     */
    @Test
    void gitInTheWorkingCopyRunsNoProgramTheAgentPlantedThere() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);
        Path hooks = Files.createDirectories(copy(c).resolve(".git/hooks"));
        Path ran = tmp.resolve("hook-ran.txt");
        for (String hook : List.of("pre-commit", "post-commit", "prepare-commit-msg")) {
            Path file = Files.writeString(hooks.resolve(hook), "#!/bin/sh\necho ran > " + ran + "\n");
            file.toFile().setExecutable(true);
        }
        git(runner.real, copy(c), "config", "--local", "core.hooksPath", hooks.toString());
        git(runner.real, copy(c), "config", "--local", "core.fsmonitor", ran.toString());

        delivery.review(c.id(), Optional.empty());
        delivery.apply(c.id(), new ApplyOptions("agent/hooked", Optional.of("Fix login"), "commit", List.of(), List.of("README.md"), Optional.empty()));

        assertThat(ran).doesNotExist();
    }

    @Test
    void whatADeliveryLeavesBehindIsSaidInItsResult() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);

        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/x", Optional.of("x"), "commit", List.of(), List.of("README.md"), Optional.empty()));

        assertThat(a.notices()).anySatisfy(n -> assertThat(n).contains("api: 3 of 4 changed file(s) were not delivered"));
    }

    @Test
    void theHeldBackFileIsCountedEvenWhenTheWholeApplyBucketGoesOut() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);

        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/x", Optional.of("x"), "commit", List.of(), List.of(), Optional.empty()));

        // run.sh is executable, so the policy holds it back and nothing else went missing.
        assertThat(a.repos().get("api").files()).isEqualTo(3);
        assertThat(a.notices()).anySatisfy(n -> assertThat(n).contains("api: 1 of 4 changed file(s) were not delivered"));
    }

    @Test
    void dirtyHostIsLeftAloneAndOnlyTheDeliveredFilesAreCommitted() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);
        Files.writeString(host.resolve("local-notes.txt"), "mine\n");
        Files.writeString(host.resolve("keep.txt"), "keep\nlocal edit\n");
        git(runner.real, host, "add", "keep.txt");

        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/x", Optional.of("x"), "commit", List.of(), List.of("src.txt"), Optional.empty()));

        assertThat(a.repos().get("api").stashed()).isFalse();
        assertThat(a.repos().get("api").stashPopConflict()).isFalse();
        assertThat(host.resolve("local-notes.txt")).exists();
        assertThat(Files.readString(host.resolve("keep.txt"))).isEqualTo("keep\nlocal edit\n");
        assertThat(git(runner.real, host, "stash", "list")).isEmpty();
        // The commit holds the delivery, not the user's own staged work, which is still staged.
        assertThat(git(runner.real, host, "log", "-1", "--format=", "--name-only")).isEqualTo("src.txt");
        assertThat(git(runner.real, host, "diff", "--cached", "--name-only")).isEqualTo("keep.txt");
    }

    @Test
    void aSecondDeliveryLandsOnTopOfAStagedFirstOneWithoutCommittingOnTheHost() throws Exception {
        Conversation c = session(History.NONE);
        Path w = copy(c);
        Files.writeString(w.resolve("README.md"), "# api\n\nline two, first pass\n");
        delivery.apply(c.id(), new ApplyOptions("agent/x", Optional.empty(), "stage", List.of(), List.of("README.md"), Optional.empty()));
        assertThat(git(runner.real, host, "status", "--porcelain")).isEqualTo("M  README.md");

        Files.writeString(w.resolve("README.md"), "# api\n\nline two, second pass\n");
        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/x", Optional.empty(), "stage", List.of(), List.of("README.md"), Optional.empty()));

        assertThat(a.repos().get("api").stashed()).isFalse();
        assertThat(a.repos().get("api").threeWay()).isFalse();
        assertThat(a.repos().get("api").conflicts()).isEmpty();
        assertThat(Files.readString(host.resolve("README.md"))).isEqualTo("# api\n\nline two, second pass\n");
        assertThat(git(runner.real, host, "status", "--porcelain")).isEqualTo("M  README.md");
        assertThat(git(runner.real, host, "stash", "list")).isEmpty();
        assertThat(sandbox.sessionService().show(c.id()).deliveries()).hasSize(2);
    }

    @Test
    void aFileFromAnEarlierDeliveryCanBeDeletedWhileTheHostHasItStagedOnly() throws Exception {
        Conversation c = session(History.NONE);
        Path w = copy(c);
        Files.writeString(w.resolve("src.txt"), "new source\n");
        delivery.apply(c.id(), new ApplyOptions("agent/x", Optional.empty(), "stage", List.of(), List.of("src.txt"), Optional.empty()));
        assertThat(host.resolve("src.txt")).exists();

        Files.delete(w.resolve("src.txt"));
        ReviewResult r = delivery.review(c.id(), Optional.empty());
        assertThat(file(r, "src.txt").change()).isEqualTo("deleted");
        assertThat(file(r, "src.txt").conflict()).isFalse();

        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/x", Optional.empty(), "stage", List.of(), List.of("src.txt"), Optional.empty()));

        assertThat(a.repos().get("api").conflicts()).isEmpty();
        assertThat(host.resolve("src.txt")).doesNotExist();
        // The addition and its removal cancel out, so the branch is back at its committed state.
        assertThat(git(runner.real, host, "status", "--porcelain")).isEmpty();
        assertThat(delivery.diff(c.id(), Optional.empty(), Optional.empty(), Optional.empty())).isEmpty();
    }

    @Test
    void anUnstagedHostEditOfADeliveredFileDoesNotBlockTheNextDelivery() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 20; i++) text.append("line ").append(i).append('\n');
        Files.writeString(host.resolve("long.txt"), text.toString());
        git(runner.real, host, "add", "-A");
        git(runner.real, host, "commit", "-q", "-m", "long");
        Conversation c = session(History.NONE);
        Path w = copy(c);
        Files.writeString(w.resolve("long.txt"), text.toString().replace("line 2\n", "line 2, agent\n"));
        delivery.apply(c.id(), new ApplyOptions("agent/x", Optional.empty(), "stage", List.of(), List.of("long.txt"), Optional.empty()));
        // The user edits the far end of the same file and does not stage it: `git apply --index` refuses that file.
        Files.writeString(host.resolve("long.txt"), Files.readString(host.resolve("long.txt")).replace("line 20\n", "line 20, mine\n"));

        Files.writeString(w.resolve("long.txt"), text.toString().replace("line 2\n", "line 2, agent again\n"));
        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/x", Optional.empty(), "stage", List.of(), List.of("long.txt"), Optional.empty()));

        assertThat(a.repos().get("api").conflicts()).isEmpty();
        assertThat(a.notices()).anySatisfy(n -> assertThat(n).contains("working tree"));
        assertThat(Files.readString(host.resolve("long.txt"))).contains("line 2, agent again").contains("line 20, mine");
        assertThat(git(runner.real, host, "stash", "list")).isEmpty();
    }

    @Test
    void aBranchSwitchThatWouldOverwriteLocalChangesStashesThemAndNeverDropsThem() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);
        git(runner.real, host, "switch", "-q", "-c", "agent/s");
        Files.writeString(host.resolve("keep.txt"), "keep\nbranch version\n");
        git(runner.real, host, "commit", "-q", "-am", "branch change");
        git(runner.real, host, "switch", "-q", "main");
        Files.writeString(host.resolve("keep.txt"), "keep\nlocal edit\n");

        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/s", Optional.empty(), "stage", List.of(), List.of("src.txt"), Optional.empty()));

        assertThat(a.repos().get("api").stashed()).isTrue();
        assertThat(host.resolve("src.txt")).exists();
        boolean inTree = Files.readString(host.resolve("keep.txt")).contains("local edit");
        boolean inStash = !git(runner.real, host, "stash", "list").isBlank();
        assertThat(inTree || inStash).as("the local edit is either in the working tree or still in the stash").isTrue();
    }

    @Test
    void workTheAgentLeftStagedInTheCopyIsNotSweptIntoTheDeliveryCommit() throws Exception {
        Conversation c = session(History.NONE);
        Path w = copy(c);
        Files.writeString(w.resolve("src.txt"), "new source\n");
        Files.writeString(w.resolve("later.txt"), "not delivered yet\n");
        git(runner.real, w, "add", "later.txt");

        delivery.apply(c.id(), new ApplyOptions("agent/x", Optional.empty(), "stage", List.of(), List.of("src.txt"), Optional.empty()));

        ReviewResult next = delivery.review(c.id(), Optional.empty());
        assertThat(next.repos().get("api").files()).extracting(FileChange::path).containsExactly("later.txt");
    }

    @Test
    void protectedBranchesAreRefused() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);

        assertThatThrownBy(() -> delivery.apply(c.id(), new ApplyOptions("main", Optional.of("x"), "commit", List.of(), List.of(), Optional.empty())))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .satisfies(e -> assertThat(((SandboxException) e).data()).containsEntry("code", "PROTECTED_BRANCH"));
        assertThatThrownBy(() -> delivery.apply(c.id(), new ApplyOptions("bad name", Optional.of("x"), "commit", List.of(), List.of(), Optional.empty())))
                .hasMessageContaining("branch");
        assertThat(git(runner.real, host, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("main");
    }

    @Test
    void heldFilesNeedExplicitIncludeAndRejectedFilesNeverApply() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);
        Path w = copy(c);
        Files.createSymbolicLink(w.resolve("evil-link"), Path.of("/etc/passwd"));

        ApplyResult all = delivery.apply(c.id(), new ApplyOptions("agent/a", Optional.of("a"), "commit", List.of(), List.of(), Optional.empty()));
        assertThat(all.repos().get("api").files()).isEqualTo(3);
        assertThat(host.resolve("run.sh")).doesNotExist();
        assertThat(host.resolve("evil-link")).doesNotExist();

        ApplyResult held = delivery.apply(c.id(), new ApplyOptions("agent/a", Optional.of("b"), "commit", List.of(), List.of("run.sh"), Optional.empty()));
        assertThat(held.repos().get("api").files()).isEqualTo(1);
        assertThat(Files.isExecutable(host.resolve("run.sh"))).isTrue();

        ReviewResult r = delivery.review(c.id(), Optional.empty());
        assertThat(file(r, "evil-link").reason()).isEqualTo("SYMLINK");
    }

    @Test
    void rejectedIncludesFail() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);

        assertThatThrownBy(() -> delivery.apply(c.id(), new ApplyOptions("agent/a", Optional.of("a"), "commit", List.of(), List.of("nope.txt"), Optional.empty())))
                .isInstanceOf(SandboxException.class).hasMessageContaining("nope.txt");
    }

    @Test
    void stageModeLeavesChangesStagedWithoutACommit() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);
        String before = git(runner.real, host, "rev-parse", "HEAD");

        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/s", Optional.empty(), "stage", List.of(), List.of("src.txt"), Optional.empty()));

        assertThat(a.repos().get("api").hostCommit()).isNull();
        assertThat(git(runner.real, host, "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(git(runner.real, host, "diff", "--cached", "--name-only")).isEqualTo("src.txt");
        assertThat(sandbox.sessionService().show(c.id()).deliveries().getFirst().mode()).isEqualTo("stage");
    }

    @Test
    void threeWayMergeIsUsedWhenHistoryIsAvailable() throws Exception {
        Conversation c = session(History.SHALLOW);
        Path w = copy(c);
        Files.writeString(w.resolve("README.md"), "# api\n\nline two, agent version\n");
        Files.writeString(host.resolve("README.md"), "# api\n\nline two, host version\n");
        git(runner.real, host, "commit", "-q", "-am", "host change");

        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/c", Optional.of("c"), "stage", List.of(), List.of(), Optional.empty()));

        assertThat(a.repos().get("api").threeWay()).isTrue();
        assertThat(a.repos().get("api").conflicts()).containsExactly("README.md");
        assertThat(Files.readString(host.resolve("README.md"))).contains("<<<<<<<").contains("agent version").contains("host version");
    }

    @Test
    void failureRollsBackTheHost() throws Exception {
        Conversation c = session(History.NONE);
        Path w = copy(c);
        Files.writeString(w.resolve("README.md"), "# api\n\nline two, agent version\n");
        Files.writeString(w.resolve("src.txt"), "new\n");
        // A change no merge can apply: the host replaced the file with a directory of the same name.
        git(runner.real, host, "rm", "-q", "README.md");
        Files.createDirectories(host.resolve("README.md"));
        Files.writeString(host.resolve("README.md/index.md"), "moved\n");
        git(runner.real, host, "add", "-A");
        git(runner.real, host, "commit", "-q", "-m", "host change");
        Files.writeString(host.resolve("dirty.txt"), "dirty\n");

        assertThatThrownBy(() -> delivery.apply(c.id(), new ApplyOptions("agent/r", Optional.of("r"), "commit", List.of(), List.of(), Optional.empty())))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).data()).containsEntry("code", "APPLY_FAILED"));

        assertThat(git(runner.real, host, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("main");
        assertThat(host.resolve("README.md/index.md")).hasContent("moved\n");
        assertThat(host.resolve("src.txt")).doesNotExist();
        assertThat(host.resolve("dirty.txt")).exists();
        assertThat(git(runner.real, host, "stash", "list")).isEmpty();
        assertThat(sandbox.sessionService().show(c.id()).deliveries()).isEmpty();
    }

    @Test
    void noChangesReportsAlternatives() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);
        delivery.apply(c.id(), new ApplyOptions("agent/a", Optional.of("a"), "commit", List.of(), List.of("README.md", "src.txt", "keep.txt", "run.sh"), Optional.empty()));

        assertThatThrownBy(() -> delivery.review(c.id(), Optional.empty()))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> {
                    SandboxException se = (SandboxException) e;
                    assertThat(se.code()).isEqualTo(ErrorCode.NO_CHANGES);
                    assertThat(se.data().get("alternatives").toString()).contains("start");
                });
        ReviewResult fromStart = delivery.review(c.id(), Optional.of("start"));
        assertThat(fromStart.repos().get("api").files()).hasSize(4);
    }

    @Test
    void baselineIndexSelectsAnEarlierDelivery() throws Exception {
        Conversation c = session(History.NONE);
        agentWork(c);
        delivery.apply(c.id(), new ApplyOptions("agent/a", Optional.of("a"), "commit", List.of(), List.of("README.md"), Optional.empty()));
        delivery.apply(c.id(), new ApplyOptions("agent/a", Optional.of("b"), "commit", List.of(), List.of("src.txt"), Optional.empty()));

        ReviewResult r = delivery.review(c.id(), Optional.of("0"));

        assertThat(r.from().kind()).isEqualTo("delivery");
        assertThat(r.from().index()).isEqualTo(0);
        assertThat(r.repos().get("api").files()).extracting(FileChange::path).contains("src.txt", "keep.txt", "run.sh").doesNotContain("README.md");
    }

    @Test
    void threeWayMergeWorksWithoutHistoryBecauseBaselineBlobsAreImported() throws Exception {
        Conversation c = session(History.NONE);
        Path w = copy(c);
        Files.writeString(w.resolve("README.md"), "# api\n\nline two, agent version\n");
        Files.writeString(host.resolve("README.md"), "# api\n\nline two, host version\n");
        git(runner.real, host, "commit", "-q", "-am", "host change");

        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/n", Optional.of("n"), "stage", List.of(), List.of(), Optional.empty()));

        assertThat(a.repos().get("api").threeWay()).isTrue();
        assertThat(a.repos().get("api").conflicts()).containsExactly("README.md");
        assertThat(Files.readString(host.resolve("README.md"))).contains("<<<<<<<").contains("agent version").contains("host version");
    }

    @Test
    void baselineTakenFromUncommittedHostChangesStillMergesThreeWay() throws Exception {
        // The session copied an uncommitted host edit; its blob never existed in the host object database.
        Files.writeString(host.resolve("README.md"), "# api\n\nuncommitted at session start\n");
        Conversation c = session(History.SHALLOW);
        Path w = copy(c);
        Files.writeString(w.resolve("README.md"), "# api\n\nuncommitted at session start\nagent adds a line\n");
        Files.writeString(host.resolve("README.md"), "# api\n\nuncommitted at session start\nhost adds a line\n");
        git(runner.real, host, "commit", "-q", "-am", "host change");

        ApplyResult a = delivery.apply(c.id(), new ApplyOptions("agent/u", Optional.of("u"), "stage", List.of(), List.of(), Optional.empty()));

        assertThat(a.repos().get("api").threeWay()).isTrue();
        assertThat(a.repos().get("api").conflicts()).containsExactly("README.md");
        assertThat(Files.readString(host.resolve("README.md"))).contains("<<<<<<<").contains("agent adds a line").contains("host adds a line");
    }
}
