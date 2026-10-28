package no.syntakserror.lyngvi.core.delivery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.runtime.Runner;

/** Git operations on the host repository during {@code apply} (spec §5.7). */
public final class HostGit {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private final Runner runner;
    private final Path repo;

    public HostGit(Runner runner, Path repo) {
        this.runner = runner;
        this.repo = repo;
    }

    public Path repo() {
        return repo;
    }

    public Runner.Result run(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        return runner.run(cmd, repo, TIMEOUT);
    }

    String ok(String... args) {
        return run(args).requireSuccess("git " + args[0]).stdout().strip();
    }

    public String branch() {
        return ok("rev-parse", "--abbrev-ref", "HEAD");
    }

    public String headSha() {
        return ok("rev-parse", "HEAD");
    }

    public boolean isDirty() {
        return !ok("status", "--porcelain").isBlank();
    }

    /** The branch {@code origin/HEAD} points at, without the remote prefix; null when unknown. */
    public String defaultBranch() {
        Runner.Result r = run("symbolic-ref", "--short", "refs/remotes/origin/HEAD");
        if (!r.success()) return null;
        String s = r.stdout().strip();
        return s.startsWith("origin/") ? s.substring("origin/".length()) : s;
    }

    /** Writes a file's content into this repository's object database (so {@code git apply --3way} finds the pre-image). */
    public boolean importBlob(Path file) {
        return run("hash-object", "-w", "--", file.toString()).success();
    }

    /**
     * A commit recording the working tree and index as they are now, made <em>without</em> touching either
     * ({@code git stash create}); blank when there is nothing to record. With {@link #applySnapshot(String)} it
     * undoes an apply, so a delivery no longer has to stash the very changes its patch is built on.
     */
    public String snapshot() {
        Runner.Result r = run("stash", "create", "lyngvi: before apply");
        return r.success() ? r.stdout().strip() : "";
    }

    /** Back to HEAD, keeping untracked files (a patch's own leftovers are removed by the caller). */
    public void reset() {
        run("reset", "--hard", "--quiet");
    }

    /** Puts the dirty state {@code snapshot} recorded back on a reset tree, staged files still staged. */
    public void applySnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return;
        if (!run("stash", "apply", "--index", "--quiet", snapshot).success()) {
            run("stash", "apply", "--quiet", snapshot);
        }
    }

    /** Local branch names. */
    public List<String> localBranches() {
        Runner.Result r = run("for-each-ref", "--format=%(refname:short)", "refs/heads");
        if (!r.success()) return List.of();
        return r.stdout().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    public boolean isValidBranchName(String name) {
        return name != null && !name.isBlank() && run("check-ref-format", "--branch", name).success();
    }

    public boolean branchExists(String name) {
        return run("rev-parse", "--verify", "--quiet", "refs/heads/" + name).success();
    }

    /** Stashes tracked and untracked changes; returns false when there was nothing to stash. */
    public boolean stashPush() {
        if (!isDirty()) return false;
        ok("stash", "push", "--include-untracked", "--quiet", "-m", "lyngvi: before apply");
        return true;
    }

    /** What {@link #stashPop()} did with the entry. */
    public enum StashResult { CLEAN, CONFLICT, KEPT }

    /**
     * Restores the stash. {@code CONFLICT}: the content landed in the tree with markers, so the entry is dropped.
     * {@code KEPT}: git restored nothing (it refuses to pop into an unmerged tree), so the entry is left for the
     * user — dropping it there would throw their local changes away.
     */
    public StashResult stashPop() {
        if (!unmergedFiles().isEmpty()) return StashResult.KEPT;
        if (run("stash", "pop", "--quiet").success()) return StashResult.CLEAN;
        if (unmergedFiles().isEmpty()) return StashResult.KEPT;
        run("stash", "drop", "--quiet");
        return StashResult.CONFLICT;
    }

    /** Switches to {@code branch} (creating it from HEAD when new), carrying local changes along as git allows. */
    public Runner.Result trySwitchTo(String branch) {
        return branchExists(branch) ? run("switch", "--quiet", branch) : run("switch", "--quiet", "-c", branch);
    }

    public void switchTo(String branch) {
        Runner.Result r = trySwitchTo(branch);
        if (!r.success()) throw unexpected("git switch " + branch, r);
    }

    public void deleteBranch(String branch) {
        run("branch", "-D", branch);
    }

    public boolean checkApply(Path patch, String include) {
        return run("apply", "--check", "--include=" + escape(include), patch.toString()).success();
    }

    /** Applies the included files to index and working tree. With {@code threeWay}, conflicts are left as markers. */
    public Runner.Result applyIndex(Path patch, List<String> includes, boolean threeWay) {
        List<String> args = new ArrayList<>(List.of("apply", "--index"));
        if (threeWay) args.add("--3way");
        for (String i : includes) args.add("--include=" + escape(i));
        args.add(patch.toString());
        return run(args.toArray(String[]::new));
    }

    /**
     * Applies the included files to the working tree only. {@code --index} refuses a file whose index and working
     * tree differ; this does not, so a delivered file the user has edited without staging it still gets the change.
     */
    public Runner.Result applyWorkTree(Path patch, List<String> includes) {
        List<String> args = new ArrayList<>(List.of("apply"));
        for (String i : includes) args.add("--include=" + escape(i));
        args.add(patch.toString());
        return run(args.toArray(String[]::new));
    }

    /** Stages {@code paths} as they are in the working tree, deletions included. */
    public void stage(List<String> paths) {
        List<String> known = matchable(paths);
        if (known.isEmpty()) return;
        List<String> args = new ArrayList<>(List.of("add", "-A", "--"));
        for (String p : known) args.add(literal(p));
        ok(args.toArray(String[]::new));
    }

    /**
     * Commits {@code paths} only — never {@code -a}, and never the rest of the index, so local work the user had
     * staged before the delivery stays staged. Null when those paths hold nothing to commit.
     */
    public String commit(String message, List<String> paths) {
        List<String> known = matchable(paths);
        if (known.isEmpty()) return null;
        List<String> staged = new ArrayList<>(List.of("diff", "--cached", "--quiet", "--"));
        for (String p : known) staged.add(literal(p));
        if (run(staged.toArray(String[]::new)).success()) return null;
        List<String> args = new ArrayList<>(List.of("commit", "--quiet", "-m", message, "--"));
        for (String p : known) args.add(literal(p));
        ok(args.toArray(String[]::new));
        return headSha();
    }

    /**
     * Of {@code paths}, the ones git can still resolve: in the index, in {@code HEAD}, or on disk. A path that is
     * in none of the three aborts the whole command — {@code git add} exits 128 and {@code git commit} exits 1 on
     * a pathspec that matches nothing — and a delivery full of renames has such paths whenever the branch already
     * holds the rename: the old name is then gone from the index and from the disk alike. Dropping it loses
     * nothing, because there is nothing left at that path to stage or commit.
     *
     * <p>{@code HEAD} has to be asked separately: staging a deletion takes the path out of the index, so between
     * {@link #stage} and {@link #commit} a deleted file lives only in {@code HEAD}, and leaving it out here would
     * keep the deletion out of the commit.
     */
    private List<String> matchable(List<String> paths) {
        if (paths.isEmpty()) return List.of();
        Set<String> known = new HashSet<>(listPaths(List.of("ls-files", "-z"), paths));
        known.addAll(listPaths(List.of("ls-tree", "-r", "-z", "--name-only", "HEAD"), paths));
        return paths.stream().filter(p -> known.contains(p) || Files.exists(repo.resolve(p))).toList();
    }

    /**
     * The paths {@code command} reports for {@code paths}; empty when git refuses it, which is what an unborn
     * branch does to {@code ls-tree HEAD}. NUL-separated, because git quotes unusual names in its normal output.
     */
    private List<String> listPaths(List<String> command, List<String> paths) {
        List<String> args = new ArrayList<>(command);
        args.add("--");
        for (String p : paths) args.add(literal(p));
        Runner.Result r = run(args.toArray(String[]::new));
        if (!r.success()) return List.of();
        return Arrays.stream(r.stdout().split("\0")).filter(s -> !s.isEmpty()).toList();
    }

    /** Files currently in an unmerged state. */
    public List<String> unmergedFiles() {
        String out = run("diff", "--name-only", "--diff-filter=U").stdout();
        return out.isBlank() ? List.of() : List.of(out.strip().split("\n"));
    }

    /** A pathspec that means exactly this path: no globbing, whatever characters it contains. */
    public static String literal(String path) {
        return ":(literal)" + path;
    }

    static String escape(String path) {
        StringBuilder sb = new StringBuilder();
        for (char ch : path.toCharArray()) {
            if (ch == '*' || ch == '?' || ch == '[' || ch == '\\') sb.append('\\');
            sb.append(ch);
        }
        return sb.toString();
    }

    static SandboxException unexpected(String what, Runner.Result r) {
        return new SandboxException(no.syntakserror.lyngvi.core.api.ErrorCode.EXTERNAL_COMMAND_FAILED,
                what + ": " + r.stderr().strip(), Map.of("stderr", r.stderr(), "stdout", r.stdout()));
    }
}
