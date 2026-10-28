package no.syntakserror.lyngvi.core.session;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.Profile;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.stack.SessionVolume;
import no.syntakserror.lyngvi.core.version.Version;

/**
 * Creates a session's working copy of one repository (spec §5.2).
 *
 * <p>The copy always gets a <em>new</em> {@code .git}: a plain {@code git init}
 * for {@link History#NONE}, or a sanitised clone ({@code --no-local
 * --no-hardlinks --template=<empty> --no-tags}) for shallow and full history.
 * Hooks, configuration and remotes of the host repository never travel.
 *
 * <p>The copy is built in a staging directory on the host and moved into the session's volume by
 * {@link #createIn}, which is the only way a session is made. The staging directory holds nothing but
 * what the host repository already contains, exists only while no container has seen it, and is deleted
 * as soon as the volume has the copy — from then on the tree is the agent's and stays off the host.
 */
public final class Workspace {

    private static final Version GIT_WITH_REVISION = Version.parse("2.49.0");

    /** What was created; {@code baseCommit} is the commit deliveries are measured from. */
    public record Result(String hostSha, String hostBranch, String baseCommit, boolean hostDirty,
                         History history, Integer depth, List<String> skipped) {}

    private Workspace() {}

    /** Builds the working copy of {@code repo} and leaves it in the session's volume, not on the host. */
    public static Result createIn(Runner runner, SessionVolume volume, String repo, Path hostRepo, History mode,
                                  int depth, Profile.Git git, CopyFilter filter) {
        Path staging = volume.tempDir("staging-");
        try {
            Result result = create(runner, hostRepo, staging.resolve(repo), mode, depth, git, filter);
            volume.copyIn(staging.resolve(repo), repo);
            return result;
        } finally {
            SessionVolume.deleteTemp(staging);
        }
    }

    public static Result create(Runner runner, Path hostRepo, Path dest, History mode, int depth,
                                Profile.Git git, CopyFilter filter) {
        HostRepo host = HostRepo.inspect(runner, hostRepo);
        if (!host.isGit() || host.headSha() == null) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    hostRepo + " is not a git repository with at least one commit", Map.of("path", hostRepo.toString()));
        }
        History effective = mode;
        List<String> skipped = new ArrayList<>();
        CopyFilter effectiveFilter = filter.withRepoIgnoreFile(hostRepo);
        try {
            Files.createDirectories(dest.getParent());
            if (mode != History.NONE) {
                boolean detached = "HEAD".equals(host.branch());
                if (detached && !supportsRevisionClone(runner, hostRepo)) {
                    effective = History.NONE;
                } else {
                    clone(runner, host, dest, mode, depth, detached);
                }
            }
            if (effective == History.NONE) {
                Files.createDirectories(dest);
                copyTree(hostRepo, dest, effectiveFilter, skipped, false);
                HostRepo.git(runner, dest, "init", "-q", "-b", "work");
            } else {
                copyTree(hostRepo, dest, effectiveFilter, skipped, true);
            }
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot create working copy " + dest + ": " + e.getMessage(), e);
        }
        HostRepo.git(runner, dest, "config", "--local", "user.name", git.committer().name());
        HostRepo.git(runner, dest, "config", "--local", "user.email", git.committer().email());
        HostRepo.git(runner, dest, "config", "--local", "commit.gpgsign", "false");
        HostRepo.git(runner, dest, "config", "--local", "core.hooksPath", "/dev/null");
        boolean changes = !HostRepo.git(runner, dest, "status", "--porcelain").stdout().isBlank();
        if (changes || effective == History.NONE) {
            HostRepo.git(runner, dest, "add", "-A");
            String message = effective == History.NONE ? "lyngvi: baseline" : "lyngvi: baseline (uncommitted host changes)";
            HostRepo.git(runner, dest, "commit", "-q", "--allow-empty", "-m", message);
        }
        String baseCommit = HostRepo.git(runner, dest, "rev-parse", "HEAD").stdout().strip();
        return new Result(host.headSha(), host.branch(), baseCommit, host.dirty(), effective,
                effective == History.SHALLOW ? depth : null, skipped);
    }

    private static void clone(Runner runner, HostRepo host, Path dest, History mode, int depth, boolean detached) throws IOException {
        Path emptyTemplate = Files.createTempDirectory("lyngvi-empty-template");
        List<String> cmd = new ArrayList<>(List.of("git", "clone", "--quiet", "--no-local", "--no-hardlinks",
                "--template=" + emptyTemplate, "--no-tags"));
        if (detached) {
            cmd.addAll(List.of("--revision", host.headSha()));
        } else {
            cmd.addAll(List.of("--single-branch", "--branch", host.branch()));
        }
        if (mode == History.SHALLOW) {
            cmd.addAll(List.of("--depth", String.valueOf(depth)));
        }
        cmd.add("file://" + host.path());
        cmd.add(dest.toString());
        runner.run(cmd, dest.getParent(), HostRepo.TIMEOUT).requireSuccess("git clone");
        HostRepo.git(runner, dest, "remote", "remove", "origin");
        if (detached) {
            HostRepo.git(runner, dest, "switch", "-q", "-c", "work");
        } else {
            HostRepo.git(runner, dest, "branch", "-m", "work");
        }
    }

    private static boolean supportsRevisionClone(Runner runner, Path cwd) {
        Runner.Result r = runner.run(List.of("git", "--version"), cwd, HostRepo.TIMEOUT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(r.stdout());
        if (!m.find()) return false;
        String v = m.group(1);
        if (v.chars().filter(ch -> ch == '.').count() == 1) v = v + ".0";
        return Version.parse(v).compareTo(GIT_WITH_REVISION) >= 0;
    }

    /**
     * Copies the host's working tree into {@code dest} through the filter. With {@code sync},
     * files present in {@code dest} but absent on the host are deleted first, so uncommitted
     * deletions on the host are mirrored on top of a clone.
     */
    static void copyTree(Path host, Path dest, CopyFilter filter, List<String> skipped, boolean sync) throws IOException {
        if (sync) {
            Files.walkFileTree(dest, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path rel = dest.relativize(dir);
                    if (!rel.toString().isEmpty() && filter.excluded(rel, true)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path rel = dest.relativize(file);
                    if (!filter.excluded(rel, false) && !Files.exists(host.resolve(rel), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        Files.delete(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Files.walkFileTree(host, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = host.relativize(dir);
                if (!rel.toString().isEmpty() && filter.excluded(rel, true)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(dest.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = host.relativize(file);
                if (filter.excluded(rel, false)) return FileVisitResult.CONTINUE;
                if (attrs.isRegularFile() && attrs.size() > filter.maxFileBytes()) {
                    skipped.add(rel.toString());
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, dest.resolve(rel), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
