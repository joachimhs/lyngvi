package no.syntakserror.lyngvi.core.delivery;

import java.time.Duration;
import java.util.Map;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.session.WorkGit;

/**
 * Builds the patch of a working copy since a commit without touching the copy's own index:
 * a temporary index is filled with {@code git add -A}, written as a tree, and diffed (spec §5.6 step 1).
 * Committed, uncommitted and untracked changes are all included.
 *
 * <p>The copy lives in the session's volume, so the whole sequence goes into one helper container
 * ({@link WorkGit}) instead of four runs of git on the host.
 */
public final class PatchBuilder {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /** Context lines that make every hunk span its whole file: well inside git's int, far beyond any real file. */
    private static final int WHOLE_FILE_CONTEXT = 10_000_000;

    private static final String SCRIPT = """
            set -e
            export GIT_INDEX_FILE=/tmp/lyngvi-index
            rm -f "$GIT_INDEX_FILE"
            $GIT read-tree HEAD
            $GIT add -A
            TREE=$($GIT write-tree)
            unset GIT_INDEX_FILE
            exec $GIT diff --binary --find-renames --no-color --no-ext-diff $CONTEXT "$FROM" "$TREE"
            """;

    private PatchBuilder() {}

    public static String build(WorkGit git, String fromCommit) {
        return build(git, fromCommit, false);
    }

    /**
     * @param wholeFiles when true every changed file appears in full (one hunk with unlimited context), so a viewer
     *     can show the file with its changes highlighted; the result is still an ordinary unified diff
     */
    public static String build(WorkGit git, String fromCommit, boolean wholeFiles) {
        Runner.Result r = git.sh(SCRIPT, Map.of("FROM", fromCommit, "CONTEXT", wholeFiles ? "-U" + WHOLE_FILE_CONTEXT : ""), TIMEOUT);
        return r.requireSuccess("git diff").stdout();
    }
}
