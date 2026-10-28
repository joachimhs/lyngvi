package no.syntakserror.lyngvi.core.api;

import java.util.Map;
import java.util.Objects;

/**
 * The single exception type the core throws to its front-ends.
 *
 * <p>Front-ends translate {@link #code()} to an exit code, a localised
 * message or a dialog; {@link #data()} carries structured detail such as
 * {@code stderr} of a failed external command or alternatives the user can
 * choose between.
 */
public class SandboxException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> data;

    public SandboxException(ErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public SandboxException(ErrorCode code, String message, Throwable cause) {
        this(code, message, Map.of(), cause);
    }

    public SandboxException(ErrorCode code, String message, Map<String, Object> data) {
        this(code, message, data, null);
    }

    public SandboxException(ErrorCode code, String message, Map<String, Object> data, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.data = Map.copyOf(Objects.requireNonNull(data, "data"));
    }

    public ErrorCode code() {
        return code;
    }

    /** Structured detail; immutable, never null. */
    public Map<String, Object> data() {
        return data;
    }

    public int exitCode() {
        return code.exitCode();
    }
}
