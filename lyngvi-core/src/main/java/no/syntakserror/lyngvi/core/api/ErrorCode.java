package no.syntakserror.lyngvi.core.api;

/**
 * Stable error codes shared by the CLI, the GUI and the JSON contract.
 *
 * <p>The exit code is what {@code lyngvi-cli} returns to the shell; the
 * enum name is what appears as {@code error.code} in {@code --json} output.
 */
public enum ErrorCode {
    /** Unexpected failure inside Lyngvi itself. */
    INTERNAL(1),
    /** Malformed or missing command-line arguments. */
    INVALID_ARGUMENT(2),
    /** The sandbox directory is not initialised, or needs migration. */
    NOT_INITIALIZED(3),
    /** A validation rule rejected the request (protected branch, bad name, ...). */
    VALIDATION_FAILED(4),
    /** An external command (podman, git, ...) failed; details in {@code data.stderr}. */
    EXTERNAL_COMMAND_FAILED(5),
    /** The operation was interrupted by a signal or by the user. */
    INTERRUPTED(6),
    /** The operation had nothing to do (empty review or apply). */
    NO_CHANGES(7);

    private final int exitCode;

    ErrorCode(int exitCode) {
        this.exitCode = exitCode;
    }

    /** Process exit code for this error. */
    public int exitCode() {
        return exitCode;
    }
}
