package no.syntakserror.lyngvi.core.testing;

import no.syntakserror.lyngvi.core.runtime.Runner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/**
 * Test double: scripted results keyed by the leading words of a command.
 * Unscripted commands behave like a missing executable. Records every call.
 */
public final class FakeRunner implements Runner {

    private final Map<String, Result> scripted = new LinkedHashMap<>();
    private final List<List<String>> calls = new ArrayList<>();

    /** Scripts a successful result for commands starting with {@code prefix} (words separated by spaces). */
    public FakeRunner ok(String prefix, String stdout) {
        return on(prefix, 0, stdout, "");
    }

    public FakeRunner on(String prefix, int exitCode, String stdout, String stderr) {
        scripted.put(prefix, new Result(List.of(prefix.split(" ")), exitCode, stdout, stderr));
        return this;
    }

    public List<List<String>> calls() {
        return calls;
    }

    private final List<String> stdins = new ArrayList<>();

    /** The stdin text passed to the n-th call (in call order); null when none was given. */
    public String stdinOf(int callIndex) {
        return stdins.get(callIndex);
    }

    @Override
    public StreamHandle stream(List<String> command, Path workingDir, java.util.Map<String, String> extraEnv, String stdin, Consumer<String> onLine) {
        stdins.add(stdin);
        Result r = run(command, workingDir, Duration.ofMinutes(1), onLine);
        stdins.set(stdins.size() - 1, stdin);
        return new StreamHandle() {
            private boolean cancelled;
            @Override public void cancel() { cancelled = true; }
            @Override public int waitFor() { return r.exitCode(); }
            @Override public boolean isCancelled() { return cancelled; }
        };
    }

    @Override
    public int interactive(List<String> command, Path workingDir) {
        calls.add(List.copyOf(command));
        stdins.add(null);
        return 0;
    }

    @Override
    public Result run(List<String> command, Path workingDir, Duration timeout) {
        return run(command, workingDir, timeout, null);
    }

    @Override
    public Result run(List<String> command, Path workingDir, Duration timeout, Consumer<String> onLine) {
        calls.add(List.copyOf(command));
        if (stdins.size() < calls.size()) stdins.add(null);
        String joined = String.join(" ", command);
        String best = null;
        for (String prefix : scripted.keySet()) {
            if ((joined.equals(prefix) || joined.startsWith(prefix + " ")) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        if (best == null) {
            throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, "Command not found: " + command.getFirst(),
                    Map.of("command", command.getFirst()));
        }
        Result r = scripted.get(best);
        if (onLine != null) {
            r.stdout().lines().forEach(onLine);
        }
        return new Result(List.copyOf(command), r.exitCode(), r.stdout(), r.stderr());
    }
}
