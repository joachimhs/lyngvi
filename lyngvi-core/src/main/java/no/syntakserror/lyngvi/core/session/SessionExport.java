package no.syntakserror.lyngvi.core.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.ExportOptions;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.contract.ExportResult;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.stack.SessionVolume;

/**
 * The session's files as one zip on the host (spec §5.9).
 *
 * <p>This is the way out of the sandbox that depends on nothing else: not on the host's repositories, not on
 * a patch applying, not on git. A delivery is the normal way — it is reviewed, classified and applied file by
 * file — but when it cannot be made to work, the work still has to be reachable, and a zip of the working
 * copies is something any machine can open. Nothing is applied anywhere and no baseline moves: the next
 * review says exactly what it said before.
 *
 * <p>The archive is built inside the session's volume by a helper container ({@link SessionVolume#zipTo}) in
 * the sandbox's own temp area, and then moved to where the user asked for it — so a half-written archive
 * never appears at that path, and the host writes nothing it has not already measured.
 */
public final class SessionExport {

    /** The agent's own output directory, which goes in the archive next to the working copies. */
    public static final String OUTPUT = "output";
    /** What the archive says about itself, as its first entry. */
    public static final String NOTE = "EXPORT.txt";
    /** The name the archive has while it is being built, in the sandbox's temp area. */
    private static final String STAGED = "export.zip";
    /**
     * What is left out of an exported {@code .git}, whatever else goes in. The working copy's {@code .git}
     * is the agent's to write, and this archive is extracted on the human's machine, where they are told to
     * run {@code git} in it — so the two places a repository keeps programs for git to run as whoever runs
     * it ({@code hooks/}, and {@code config} with {@code core.hooksPath}, {@code core.fsmonitor},
     * {@code core.pager}, {@code core.sshCommand} or an alias in it) do not travel. That is the same reason
     * the host never runs git in the volume without disarming it (spec §5.1). Nothing is lost by it:
     * {@code log}, {@code status}, {@code diff} and {@code fetch} all work in a copy without them.
     */
    private static final List<String> GIT_INTERNALS = List.of("*/.git/hooks", "*/.git/config");

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter NOTE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private SessionExport() {}

    /**
     * The name an export gets unless the user says otherwise: the session's title and the time, so two
     * exports of the same session do not land on each other. A file name, not a branch name — no prefix,
     * and short enough to read in a file dialog.
     */
    public static String fileName(String title, long at) {
        String slug = (title == null ? "" : title).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.length() > 30) slug = slug.substring(0, 30).replaceAll("-$", "");
        String stamp = FILE_TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
        return "lyngvi-" + (slug.isEmpty() ? "export" : slug) + "-" + stamp + ".zip";
    }

    /**
     * Writes the archive and returns what it holds. {@code profileExcludes} is the profile's
     * {@code copy.excludes} — the directories that never came into the sandbox in the first place, and so
     * hold nothing but what the agent's own builds left behind; {@code includeBuildOutput} keeps them.
     */
    public static ExportResult write(SessionVolume volume, Conversation c, List<String> profileExcludes, ExportOptions options) {
        Path target = target(options.to(), c);
        List<String> roots = new ArrayList<>(c.repos());
        roots.add(OUTPUT);
        List<String> excludes = new ArrayList<>();
        if (!options.includeGit()) excludes.add(".git");
        if (!options.includeBuildOutput()) excludes.addAll(profileExcludes);
        SessionVolume.Contents contents = new SessionVolume.Contents(roots, excludes,
                options.includeGit() ? GIT_INTERNALS : List.of());
        Path staging = volume.tempDir("export-");
        try {
            // before the archive is built, not after: a directory we cannot make is the user's typo, and
            // they should hear about it now rather than when the zip is already finished
            Files.createDirectories(target.getParent());
            Runner.Result r = volume.zipTo(staging, STAGED, contents, NOTE,
                    note(c, roots, excludes, options, System.currentTimeMillis()));
            boolean partial = check(r, volume.name());
            Files.move(staging.resolve(STAGED), target, StandardCopyOption.REPLACE_EXISTING);
            return describe(target, roots, excludes, partial);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + target + ": " + e.getMessage(),
                    Map.of("path", target.toString()), e);
        } finally {
            SessionVolume.deleteTemp(staging);
        }
    }

    /** Where the archive goes: the path given, or a name of ours inside it when that path is a directory. */
    private static Path target(Path to, Conversation c) {
        Path path = to.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) path = path.resolve(fileName(c.title(), System.currentTimeMillis()));
        if (path.getParent() == null) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "The export needs a file to write, not " + path,
                    Map.of("path", path.toString()));
        }
        return path;
    }

    /**
     * {@code zip}'s exit code, read as what it means for an export. 18 is «a file could not be read» — one
     * unreadable file does not take the other thousands with it, so the archive stands and the result says
     * it is incomplete. 12 is «nothing to do»: the session has no files to export at all.
     */
    private static boolean check(Runner.Result r, String volume) {
        return switch (r.exitCode()) {
            case 0 -> false;
            case 18 -> true;
            case 12 -> throw new SandboxException(ErrorCode.NO_CHANGES, "The session has no files to export",
                    Map.of("volume", volume));
            case 127 -> throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "The base image has no 'zip'; run 'setup.sh build' to rebuild it", Map.of("volume", volume));
            default -> throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED,
                    "zip failed in " + volume + " (exit " + r.exitCode() + ")",
                    Map.of("volume", volume, "stderr", r.stderr(), "stdout", r.stdout()));
        };
    }

    /** What the finished archive holds, read back from the archive itself rather than from what we meant to put in it. */
    private static ExportResult describe(Path target, List<String> roots, List<String> excludes, boolean partial) throws IOException {
        int entries = 0;
        try (ZipFile zip = new ZipFile(target.toFile())) {
            entries = (int) zip.stream().filter(e -> !e.isDirectory()).count();
        }
        return new ExportResult(target.toString(), Files.size(target), entries, List.copyOf(roots), List.copyOf(excludes), partial);
    }

    /**
     * The archive's own first page: what it is, where it came from, what was left out, and the two commands
     * that get the agent's work out of it. Whoever opens the zip — possibly weeks later, possibly not the
     * person who made it — should not have to ask anyone what they are looking at.
     */
    static String note(Conversation c, List<String> roots, List<String> excludes, ExportOptions options, long at) {
        StringBuilder sb = new StringBuilder();
        sb.append("Lyngvi export\n=============\n\n");
        sb.append("Session   ").append(c.title()).append(" (").append(c.shortId()).append(")\n");
        sb.append("Agent     ").append(c.agent()).append('\n');
        sb.append("Exported  ").append(NOTE_TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))).append('\n');
        sb.append("""

                This is a copy of the files a Lyngvi session had in its sandbox, as they were at the time above:
                one directory per working copy, and the agent's output/ directory. Nothing here has been applied
                to any repository on this machine, and exporting changed nothing in the session — it is still
                there, and a delivery can still be made from it.

                """);
        sb.append("Directories in this archive\n");
        for (String root : roots) {
            Conversation.RepoBase base = c.base().get(root);
            if (base == null) {
                sb.append("  ").append(root).append("/  (the agent's output)\n");
            } else {
                sb.append("  ").append(root).append("/  working copy of the repository on branch ")
                        .append(base.hostBranch()).append(", started from ").append(shortSha(base.hostSha())).append('\n');
            }
        }
        sb.append("\nWhat was left out\n");
        sb.append("  Symbolic links: a copy that leaves the sandbox is files only.\n");
        List<String> build = new ArrayList<>(excludes);
        if (!options.includeGit()) {
            build.remove(".git");
            sb.append("  The working copies' .git directories, and with them the history of what the agent did.\n");
        }
        if (!build.isEmpty()) {
            sb.append("  Build output and dependencies, wherever they occur:\n");
            wrap(sb, "    ", String.join(", ", build) + ".");
        }
        if (options.includeGit()) {
            sb.append("  Each .git's hooks/ and config: the agent could write to them, and you are about to\n");
            sb.append("  run git here. Everything below still works without them.\n");
            sb.append("\nWhat the agent changed, per working copy in this archive:\n");
            for (String root : roots) {
                Conversation.RepoBase base = c.base().get(root);
                if (base != null) {
                    sb.append("  cd ").append(root).append(" && git log --oneline && git diff ")
                            .append(shortSha(base.baseCommit())).append('\n');
                }
            }
            sb.append("\nTo get the agent's commits into your own clone of the repository:\n");
            sb.append("  git -C <your clone> fetch <this directory>/<repository> work\n");
            sb.append("  git -C <your clone> switch -c <a branch of your own> FETCH_HEAD\n");
            sb.append('\n');
            sb.append("What the agent had not committed is in the files, not in the history: 'git status' in\n");
            sb.append("the extracted copy names those files, and 'git diff' shows the lines.\n");
        }
        return sb.toString();
    }

    /** Appends {@code text} indented, broken at spaces, so the note reads in any terminal or editor. */
    private static void wrap(StringBuilder sb, String indent, String text) {
        int width = 96 - indent.length();
        for (String word : text.split(" ")) {
            int lastBreak = sb.lastIndexOf("\n") + 1;
            if (sb.length() == lastBreak) sb.append(indent);
            else if (sb.length() - lastBreak + word.length() + 1 > width) sb.append('\n').append(indent);
            else sb.append(' ');
            sb.append(word);
        }
        sb.append('\n');
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 12 ? String.valueOf(sha) : sha.substring(0, 12);
    }
}
