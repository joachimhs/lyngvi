package no.syntakserror.lyngvi.core.contract;

import java.util.List;

/**
 * Result of {@code session export}: the archive on the host and what it holds.
 *
 * @param path      the archive, as an absolute path
 * @param bytes     its size
 * @param files     entries in it, directories not counted
 * @param roots     the directories of the session it holds, one per working copy plus {@code output}
 * @param excluded  path segments left out of it (build output, and {@code .git} when the history was not wanted)
 * @param partial   true when a file could not be read and was left out; the rest of the archive stands
 */
public record ExportResult(String path, long bytes, int files, List<String> roots, List<String> excluded, boolean partial) {}
