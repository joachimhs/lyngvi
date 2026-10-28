package no.syntakserror.lyngvi.core.ask;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.runtime.Runner;

/**
 * Wraps an external adapter ({@code agents/<id>/adapter.sh}) in the process contract of spec §6.2:
 * actions as the first argument, everything else as {@code AS_*} environment variables, JSONL out.
 */
public final class ProcessAdapter implements AgentAdapter {

    private static final ObjectMapper JSON = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Duration SHORT = Duration.ofMinutes(2);

    private final AgentDescriptor descriptor;
    private final Runner runner;
    private final Path script;

    public ProcessAdapter(AgentDescriptor descriptor, Runner runner) {
        this.descriptor = descriptor;
        this.runner = runner;
        this.script = descriptor.dir().resolve("adapter.sh");
    }

    @Override
    public AgentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public boolean needsContainer() {
        return descriptor.needsContainer();
    }

    /** The {@code AS_*} variables for an action. Attachments are newline-separated (env cannot carry NUL). */
    public static Map<String, String> environment(String action, AskRequest req, Map<String, String> extra) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AS_ACTION", action);
        env.put("AS_SESSION_ID", req.sessionId());
        env.put("AS_SESSION_DIR", req.sessionDir().toString());
        env.put("AS_CONTAINER", req.container() == null ? "" : req.container());
        // An adapter that runs outside the container reaches the session's files through the volume, never
        // through a host path: there is none. These two are what it takes to mount it (spec §6.2).
        env.put("AS_VOLUME", req.volume() == null ? "" : req.volume());
        env.put("AS_IMAGE", req.image() == null ? "" : req.image());
        env.put("AS_REPOS", String.join(",", req.repos()));
        env.put("AS_AGENT_SESSION_ID", req.agentSessionId() == null ? "" : req.agentSessionId());
        env.put("AS_MODEL", req.model() == null ? "" : req.model());
        env.put("AS_PROMPT_FILE", req.promptFile() == null ? "" : req.promptFile().toString());
        env.put("AS_ATTACHMENTS", String.join("\n", req.attachments()));
        env.put("AS_WORKDIR", "/work");
        env.put("AS_LOCAL", req.local() ? "1" : "0");
        env.putAll(extra);
        return env;
    }

    @Override
    public AgentRunHandle ask(AskRequest request, Consumer<AgentEvent> onEvent) {
        Map<String, String> env = environment("ask", request, descriptor.env());
        AtomicBoolean done = new AtomicBoolean(false);
        Runner.StreamHandle handle = runner.stream(List.of(script.toString(), "ask"), descriptor.dir(), env, line -> {
            AgentEvents.parse(line).ifPresent(e -> {
                if (done.get()) return;
                if (e instanceof AgentEvent.Done) done.set(true);
                onEvent.accept(e);
            });
        });
        return new AgentRunHandle() {
            private Integer exit;

            @Override
            public void cancel() {
                handle.cancel();
            }

            @Override
            public synchronized int waitFor() {
                if (exit != null) return exit;
                exit = handle.waitFor();
                if (!done.get()) {
                    done.set(true);
                    if (handle.isCancelled()) {
                        onEvent.accept(AgentEvent.done("cancelled"));
                    } else {
                        onEvent.accept(AgentEvent.error("ADAPTER_EXIT", "adapter ended with exit code " + exit + " without a done event"));
                        onEvent.accept(AgentEvent.done("error"));
                    }
                }
                return exit;
            }
        };
    }

    @Override
    public Suggestion suggest(AskRequest request, String diff) {
        try {
            Path diffFile = Files.createTempFile("lyngvi-diff", ".patch");
            Files.writeString(diffFile, diff == null ? "" : diff);
            try {
                Map<String, String> env = environment("suggest", request, descriptor.env());
                env.put("AS_DIFF_FILE", diffFile.toString());
                Runner.Result r = runner.run(List.of(script.toString(), "suggest"), descriptor.dir(), SHORT, env, null).requireSuccess("adapter suggest");
                return JSON.readValue(r.stdout(), Suggestion.class);
            } finally {
                Files.deleteIfExists(diffFile);
            }
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, "adapter suggest returned invalid JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Model> models() {
        return read("models", new TypeReference<List<Model>>() {});
    }

    @Override
    public AuthStatus authStatus() {
        return read("auth-status", new TypeReference<AuthStatus>() {});
    }

    @Override
    public AgentUsage usage() {
        return AgentUsage.parse(read("usage", new TypeReference<Map<String, Object>>() {}));
    }

    @Override
    public int login(String mode, Consumer<String> onLine) {
        Map<String, String> env = new LinkedHashMap<>(descriptor.env());
        env.put("AS_ACTION", "login");
        env.put("AS_AUTH_MODE", mode == null ? "" : mode);
        env.put("AS_JSON", "1");
        return runner.stream(List.of(script.toString(), "login"), descriptor.dir(), env, onLine).waitFor();
    }

    @Override
    public void logout() {
        Map<String, String> env = new LinkedHashMap<>(descriptor.env());
        env.put("AS_ACTION", "logout");
        runner.run(List.of(script.toString(), "logout"), descriptor.dir(), SHORT, env, null).requireSuccess("adapter logout");
    }

    private <T> T read(String action, TypeReference<T> type) {
        Map<String, String> env = new LinkedHashMap<>(descriptor.env());
        env.put("AS_ACTION", action);
        Runner.Result r = runner.run(List.of(script.toString(), action), descriptor.dir(), SHORT, env, null).requireSuccess("adapter " + action);
        try {
            return JSON.readValue(r.stdout(), type);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, "adapter " + action + " returned invalid JSON: " + e.getMessage(), e);
        }
    }
}
