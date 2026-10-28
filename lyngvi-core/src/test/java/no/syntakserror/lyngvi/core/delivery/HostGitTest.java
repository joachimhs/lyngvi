package no.syntakserror.lyngvi.core.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real git. The pathspec handling of {@link HostGit#stage} and {@link HostGit#commit}. */
class HostGitTest {

    @TempDir Path tmp;
    ProcessRunner runner;
    Path repo;
    HostGit git;

    String git(String... args) {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        return runner.run(cmd, repo, Duration.ofSeconds(30)).requireSuccess("git " + args[0]).stdout().strip();
    }

    @BeforeEach
    void setUp() throws Exception {
        runner = new ProcessRunner(Platform.current());
        repo = Files.createDirectories(tmp.resolve("repo"));
        git("init", "-q", "-b", "main");
        git("config", "user.email", "h@example.org");
        git("config", "user.name", "h");
        Files.writeString(repo.resolve("old.txt"), "content\n");
        git("add", "-A");
        git("commit", "-q", "-m", "init");
        this.git = new HostGit(runner, repo);
    }

    /**
     * A delivery of renames names both ends of every rename. Re-applying one — the obvious thing to do after an
     * apply that stopped half way — hands {@code stage} an old path that the branch no longer has at all, and
     * {@code git add} answers a pathspec matching nothing with exit 128, which used to abort the whole delivery.
     */
    @Test
    void stageIgnoresPathsThatAreNeitherTrackedNorOnDisk() throws Exception {
        Files.move(repo.resolve("old.txt"), repo.resolve("new.txt"));
        git.stage(List.of("new.txt", "old.txt"));
        git.commit("renamed", List.of("new.txt", "old.txt"));

        // The rename is in; replaying it must not throw, and must find nothing left to do.
        git.stage(List.of("new.txt", "old.txt"));
        assertThat(git.commit("again", List.of("new.txt", "old.txt"))).isNull();
        assertThat(git("log", "--oneline")).hasLineCount(2);
    }

    @Test
    void stageAndCommitStillCarryThePathsThatDoExist() throws Exception {
        Files.writeString(repo.resolve("added.txt"), "new\n");
        Files.writeString(repo.resolve("old.txt"), "edited\n");

        git.stage(List.of("added.txt", "old.txt", "never/existed.txt"));
        String sha = git.commit("delivered", List.of("added.txt", "old.txt", "never/existed.txt"));

        assertThat(sha).isNotNull();
        assertThat(git("show", "--name-only", "--format=", "HEAD").lines()).containsExactlyInAnyOrder("added.txt", "old.txt");
    }

    @Test
    void commitReportsNothingToCommitWhenNoPathMatches() {
        assertThat(git.commit("nothing", List.of("never/existed.txt"))).isNull();
    }

    /** A deletion still has to reach the commit: the path is gone from disk but git still tracks it. */
    @Test
    void stageCarriesADeletion() throws Exception {
        Files.delete(repo.resolve("old.txt"));
        git.stage(List.of("old.txt"));
        assertThat(git.commit("deleted", List.of("old.txt"))).isNotNull();
        assertThat(git("ls-files")).isEmpty();
    }

    @Test
    void literalPathspecsAreNotGlobs() throws Exception {
        Path odd = repo.resolve("a[1].txt");
        Files.writeString(odd, "x\n");
        git.stage(List.of("a[1].txt"));
        assertThat(git.commit("odd name", List.of("a[1].txt"))).isNotNull();
        assertThat(git("ls-files").lines()).contains("a[1].txt");
    }
}
