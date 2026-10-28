package no.syntakserror.lyngvi.core.session;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.stack.SessionVolume;

/**
 * Git in a session's working copy (spec §5.2). The copy lives in the session's volume, so every command
 * runs in a helper container; the host never runs git in a tree the agent can write.
 *
 * <p>Two settings in the copy's {@code .git/config} would otherwise make git run the agent's own
 * programs for us: {@code core.hooksPath} and {@code core.fsmonitor}. Both are turned off on the command
 * line, where the repository's configuration cannot reach them, on every single call.
 */
public final class WorkGit {

    /** The {@code git} a script in the working copy calls: the options the copy's configuration cannot undo. */
    static final String SAFE_GIT = "git -c core.hooksPath=/dev/null -c core.fsmonitor=false";

    private final SessionVolume volume;
    private final String repo;

    public WorkGit(SessionVolume volume, String repo) {
        this.volume = volume;
        this.repo = repo;
    }

    /**
     * Runs a shell script in the working copy. The script calls git as {@code $GIT}, which carries the
     * hardening above; everything that comes from outside the sandbox is passed in {@code env}.
     */
    public Runner.Result sh(String script, Map<String, String> env, Duration timeout) {
        return volume.sh(repo, "GIT=\"" + SAFE_GIT + "\"\n" + script, env, timeout);
    }

    /** A scratch directory on the host for what a script writes to {@code $OUT}; see {@link SessionVolume#tempDir}. */
    public Path tempDir(String prefix) {
        return volume.tempDir(prefix);
    }

    /** As {@link #sh}, with a host directory mounted writable as {@code $OUT}. */
    public Runner.Result shToHost(Path hostDir, String script, Map<String, String> env, Duration timeout) {
        return volume.shToHost(repo, hostDir, "GIT=\"" + SAFE_GIT + "\"\n" + script, env, timeout);
    }
}
