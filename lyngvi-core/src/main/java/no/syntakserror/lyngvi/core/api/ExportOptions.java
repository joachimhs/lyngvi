package no.syntakserror.lyngvi.core.api;

import java.nio.file.Path;

/**
 * Options for {@code session export} (spec §5.9): where the archive goes, whether the working copies' git
 * history goes with it, and whether the directories the profile's {@code copy.excludes} keeps out of a
 * working copy — {@code node_modules}, {@code target} and the rest, which hold the agent's builds and
 * nothing of its work — go in too.
 *
 * <p>{@code to} may be a directory, in which case the archive gets a name of its own inside it.
 */
public record ExportOptions(Path to, boolean includeGit, boolean includeBuildOutput) {

    /** The archive as a last resort should be wanted: the history included, the build output not. */
    public static ExportOptions to(Path path) {
        return new ExportOptions(path, true, false);
    }
}
