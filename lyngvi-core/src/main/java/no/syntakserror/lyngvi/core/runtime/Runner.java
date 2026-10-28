package no.syntakserror.lyngvi.core.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/**
 * Runs external commands. The core depends on this interface so tests can
 * script command output without a real {@code podman} or {@code git}.
 */
public interface Runner {

    /** Runs to completion, capturing all output. */
    Result run(List<String> command, Path workingDir, Duration timeout);

    /** Runs to completion, delivering each stdout line to {@code onLine} as it arrives. */
    Result run(List<String> command, Path workingDir, Duration timeout, Consumer<String> onLine);

    /** Runs to completion with extra environment variables for the child. */
    default Result run(List<String> command, Path workingDir, Duration timeout, Map<String, String> extraEnv, Consumer<String> onLine) {
        return run(command, workingDir, timeout, onLine);
    }

    /** Starts a long-running command whose stdout lines stream to {@code onLine}; the handle cancels or waits. */
    default StreamHandle stream(List<String> command, Path workingDir, Map<String, String> extraEnv, Consumer<String> onLine) {
        return stream(command, workingDir, extraEnv, null, onLine);
    }

    /** As {@link #stream(List, Path, Map, Consumer)}, writing {@code stdin} to the child's stdin first (null: nothing). */
    StreamHandle stream(List<String> command, Path workingDir, Map<String, String> extraEnv, String stdin, Consumer<String> onLine);

    /** A started stream. {@link #waitFor()} returns only after every line has been delivered. */
    interface StreamHandle {
        void cancel();
        int waitFor();
        boolean isCancelled();
        default String stderr() { return ""; }
    }

    /** Runs attached to the user's terminal (stdin, stdout, stderr inherited) and returns the exit code. */
    default int interactive(List<String> command, Path workingDir) {
        throw new UnsupportedOperationException("interactive commands are not supported by " + getClass().getSimpleName());
    }

    /** Outcome of a finished command. */
    record Result(List<String> command, int exitCode, String stdout, String stderr) {

        public boolean success() {
            return exitCode == 0;
        }

        /** Throws {@link ErrorCode#EXTERNAL_COMMAND_FAILED} with stderr and exit code in the data. */
        public Result requireSuccess(String what) {
            if (!success()) {
                throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED,
                        what + " failed with exit code " + exitCode + (stderr.isBlank() ? "" : ": " + stderr.strip()),
                        Map.of("command", String.join(" ", command), "exitCode", exitCode, "stderr", stderr, "stdout", stdout));
            }
            return this;
        }
    }
}
