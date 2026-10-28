package no.syntakserror.lyngvi.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.Profile;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.ProcessRunner;
import no.syntakserror.lyngvi.core.runtime.Runner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses the real git on the build machine. */
class WorkspaceTest {

    static final Profile.Git GIT = new Profile.Git(List.of("main"), "agent/", new Profile.Git.Committer("lyngvi", "lyngvi@local"));
    static final CopyFilter FILTER = new CopyFilter(List.of("node_modules", "build", "*.egg-info"), 50L << 20);

    @TempDir Path tmp;
    Runner runner;
    Path host;

    static String git(Runner runner, Path repo, String... args) {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        return runner.run(cmd, repo, Duration.ofSeconds(30)).requireSuccess("git " + args[0]).stdout().strip();
    }

    @BeforeEach
    void hostRepoWithHistory() throws Exception {
        runner = new ProcessRunner(Platform.current());
        host = Files.createDirectories(tmp.resolve("host"));
        git(runner, host, "init", "-q", "-b", "main");
        git(runner, host, "config", "user.name", "Host User");
        git(runner, host, "config", "user.email", "host@example.org");
        git(runner, host, "config", "core.sshCommand", "ssh -o Nope=yes");
        git(runner, host, "remote", "add", "origin", "https://user:secret@git.example.org/repo.git");
        Files.writeString(host.resolve(".git/hooks/pre-commit"), "#!/bin/sh\necho HOOK\n");
        host.resolve(".git/hooks/pre-commit").toFile().setExecutable(true);
        for (int i = 1; i <= 5; i++) {
            Files.writeString(host.resolve("file.txt"), "version " + i + "\n");
            git(runner, host, "add", "-A");
            git(runner, host, "commit", "-q", "-m", "commit " + i);
        }
        Files.createDirectories(host.resolve("node_modules/left-pad"));
        Files.writeString(host.resolve("node_modules/left-pad/index.js"), "x");
        Files.writeString(host.resolve(".gitignore"), "node_modules/\n");
        git(runner, host, "add", "-A");
        git(runner, host, "commit", "-q", "-m", "ignore");
    }

    @Test
    void noneModeCopiesFilesAndCreatesASingleBaselineCommit() throws Exception {
        Path dest = tmp.resolve("copy");

        Workspace.Result r = Workspace.create(runner, host, dest, History.NONE, 100, GIT, FILTER);

        assertThat(r.history()).isEqualTo(History.NONE);
        assertThat(r.hostSha()).isEqualTo(git(runner, host, "rev-parse", "HEAD"));
        assertThat(r.hostBranch()).isEqualTo("main");
        assertThat(r.hostDirty()).isFalse();
        assertThat(r.baseCommit()).isNotEqualTo(r.hostSha());
        assertThat(git(runner, dest, "rev-list", "--count", "HEAD")).isEqualTo("1");
        assertThat(git(runner, dest, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("work");
        assertThat(Files.readString(dest.resolve("file.txt"))).isEqualTo("version 5\n");
        assertThat(dest.resolve("node_modules")).doesNotExist();
        assertThat(git(runner, dest, "status", "--porcelain")).isEmpty();
    }

    @Test
    void shallowModeClonesLimitedHistoryWithBaseCommitEqualToHost() throws Exception {
        Path dest = tmp.resolve("copy");

        Workspace.Result r = Workspace.create(runner, host, dest, History.SHALLOW, 3, GIT, FILTER);

        assertThat(r.baseCommit()).isEqualTo(r.hostSha());
        assertThat(git(runner, dest, "rev-list", "--count", "HEAD")).isEqualTo("3");
        assertThat(git(runner, dest, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("work");
        assertThat(git(runner, dest, "log", "--oneline")).contains("ignore").doesNotContain("commit 1");
    }

    @Test
    void fullModeClonesEverything() throws Exception {
        Path dest = tmp.resolve("copy");

        Workspace.Result r = Workspace.create(runner, host, dest, History.FULL, 100, GIT, FILTER);

        assertThat(git(runner, dest, "rev-list", "--count", "HEAD")).isEqualTo("6");
        assertThat(r.baseCommit()).isEqualTo(r.hostSha());
    }

    @Test
    void theCopyNeverHasHostHooksConfigOrRemotes() throws Exception {
        for (History mode : History.values()) {
            Path dest = tmp.resolve("copy-" + mode.json());

            Workspace.create(runner, host, dest, mode, 100, GIT, FILTER);

            assertThat(dest.resolve(".git/hooks/pre-commit")).as(mode.name()).doesNotExist();
            assertThat(git(runner, dest, "remote")).as(mode.name()).isEmpty();
            Runner.Result ssh = runner.run(List.of("git", "-C", dest.toString(), "config", "--local", "--get-all", "core.sshCommand"), dest, Duration.ofSeconds(30));
            assertThat(ssh.stdout()).as(mode.name()).doesNotContain("Nope");
            assertThat(git(runner, dest, "config", "--local", "user.email")).isEqualTo("lyngvi@local");
            assertThat(git(runner, dest, "config", "--local", "core.hooksPath")).isEqualTo("/dev/null");
            assertThat(git(runner, dest, "config", "--local", "commit.gpgsign")).isEqualTo("false");
        }
    }

    @Test
    void dirtyHostChangesAreLayeredOnTopAndCommittedAsBaseline() throws Exception {
        Files.writeString(host.resolve("file.txt"), "edited but not committed\n");
        Files.writeString(host.resolve("new.txt"), "new\n");
        Files.delete(host.resolve(".gitignore"));
        Path dest = tmp.resolve("copy");

        Workspace.Result r = Workspace.create(runner, host, dest, History.SHALLOW, 100, GIT, FILTER);

        assertThat(r.hostDirty()).isTrue();
        assertThat(r.baseCommit()).isNotEqualTo(r.hostSha());
        assertThat(git(runner, dest, "log", "-1", "--format=%s")).isEqualTo("lyngvi: baseline (uncommitted host changes)");
        assertThat(Files.readString(dest.resolve("file.txt"))).isEqualTo("edited but not committed\n");
        assertThat(dest.resolve("new.txt")).exists();
        assertThat(dest.resolve(".gitignore")).doesNotExist();
        assertThat(git(runner, dest, "status", "--porcelain")).isEmpty();
    }

    @Test
    void detachedHeadIsSupported() throws Exception {
        git(runner, host, "checkout", "-q", "HEAD~1");
        Path dest = tmp.resolve("copy");

        Workspace.Result r = Workspace.create(runner, host, dest, History.SHALLOW, 2, GIT, FILTER);

        assertThat(r.hostBranch()).isEqualTo("HEAD");
        assertThat(r.baseCommit()).isEqualTo(r.hostSha());
        assertThat(Files.readString(dest.resolve("file.txt"))).isEqualTo("version 5\n");
    }

    @Test
    void hostTemplateDirHooksDoNotLeak() throws Exception {
        Path template = Files.createDirectories(tmp.resolve("template/hooks"));
        Files.writeString(template.resolve("post-checkout"), "#!/bin/sh\necho LEAK\n");
        Runner withTemplate = new ProcessRunner(new Platform(Platform.current().os(), Platform.current().home(),
                java.util.Map.of("PATH", System.getenv("PATH"), "HOME", System.getProperty("user.home"), "GIT_TEMPLATE_DIR", tmp.resolve("template").toString(),
                        "XDG_STATE_HOME", no.syntakserror.lyngvi.core.testing.IsolatedState.dir()),
                Platform.current().uid(), Platform.current().gid()));
        Path dest = tmp.resolve("copy");

        Workspace.create(withTemplate, host, dest, History.SHALLOW, 100, GIT, FILTER);

        assertThat(dest.resolve(".git/hooks/post-checkout")).doesNotExist();
    }

    @Test
    void lyngviIgnoreAndSizeLimitAreHonoured() throws Exception {
        Files.writeString(host.resolve(".lyngviignore"), "secrets/\n*.bin\n");
        Files.createDirectories(host.resolve("secrets"));
        Files.writeString(host.resolve("secrets/key.txt"), "k");
        Files.writeString(host.resolve("blob.bin"), "b");
        byte[] big = new byte[3 << 20];
        Files.write(host.resolve("big.dat"), big);
        CopyFilter small = new CopyFilter(List.of("node_modules"), 1L << 20);
        Path dest = tmp.resolve("copy");

        Workspace.Result r = Workspace.create(runner, host, dest, History.NONE, 100, GIT, small);

        assertThat(dest.resolve("secrets")).doesNotExist();
        assertThat(dest.resolve("blob.bin")).doesNotExist();
        assertThat(dest.resolve("big.dat")).doesNotExist();
        assertThat(r.skipped()).contains("big.dat");
        assertThat(dest.resolve("file.txt")).exists();
    }

    @Test
    void hostRepoInspectionReportsFacts() {
        HostRepo h = HostRepo.inspect(runner, host);

        assertThat(h.isGit()).isTrue();
        assertThat(h.branch()).isEqualTo("main");
        assertThat(h.headSha()).hasSize(40);
        assertThat(h.dirty()).isFalse();
        assertThat(HostRepo.inspect(runner, tmp).isGit()).isFalse();
    }
}
