package no.syntakserror.lyngvi.core.session;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.runtime.Runner;

/**
 * Facts about a repository on the host, read before anything is copied (spec §5.2 step 1).
 *
 * @param branch the current branch, or {@code HEAD} when detached
 */
public record HostRepo(Path path, boolean isGit, String headSha, String branch, boolean dirty) {

    static final Duration TIMEOUT = Duration.ofMinutes(5);

    public static HostRepo inspect(Runner runner, Path repo) {
        Path abs = repo.toAbsolutePath().normalize();
        Runner.Result inside = tryGit(runner, abs, "rev-parse", "--is-inside-work-tree");
        if (inside == null || !inside.success() || !inside.stdout().strip().equals("true")) {
            return new HostRepo(abs, false, null, null, false);
        }
        Runner.Result head = tryGit(runner, abs, "rev-parse", "HEAD");
        if (head == null || !head.success()) {
            return new HostRepo(abs, true, null, "HEAD", true);
        }
        String branch = git(runner, abs, "rev-parse", "--abbrev-ref", "HEAD").stdout().strip();
        boolean dirty = !git(runner, abs, "status", "--porcelain").stdout().isBlank();
        return new HostRepo(abs, true, head.stdout().strip(), branch, dirty);
    }

    static Runner.Result git(Runner runner, Path repo, String... args) {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        return runner.run(cmd, repo, TIMEOUT).requireSuccess("git " + args[0]);
    }

    private static Runner.Result tryGit(Runner runner, Path repo, String... args) {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        try {
            return runner.run(cmd, repo, TIMEOUT);
        } catch (SandboxException e) {
            return null;
        }
    }
}
