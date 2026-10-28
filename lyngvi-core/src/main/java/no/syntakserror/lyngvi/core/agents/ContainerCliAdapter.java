package no.syntakserror.lyngvi.core.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.api.StackService;
import no.syntakserror.lyngvi.core.ask.AgentAdapter;
import no.syntakserror.lyngvi.core.ask.AgentContext;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.ask.AgentEvent;
import no.syntakserror.lyngvi.core.ask.AgentRunHandle;
import no.syntakserror.lyngvi.core.ask.AgentUsage;
import no.syntakserror.lyngvi.core.ask.AskRequest;
import no.syntakserror.lyngvi.core.ask.AuthStatus;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.ask.Suggestion;
import no.syntakserror.lyngvi.core.config.DotEnv;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.Runner;

/**
 * Base for agents that are command-line tools inside the session's container: the
 * command comes from {@code agent.json} templates, the prompt goes in on stdin, the
 * agent's own JSON stream is mapped to events by a {@link StreamMapper}.
 *
 * <p>Template placeholders: {@code {model:--model}} expands to {@code --model <m>} when a
 * model is set; {@code {session:A|B}} expands to {@code A <new uuid>} on the first turn and
 * {@code B <agentSessionId>} afterwards (an empty side expands to nothing).
 */
public abstract class ContainerCliAdapter implements AgentAdapter {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(model|session):([^}]*)}");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;]*[a-zA-Z]");
    /** {@code provider/model}, {@code model:tag} and plain names; an id starts and ends alphanumeric, so a heading like {@code Anthropic:} is not one. */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][\\w.:+@/-]*[A-Za-z0-9]|[A-Za-z0-9]");
    private static final Duration SHORT = Duration.ofMinutes(5);
    private static final ObjectMapper CATALOGUE = new ObjectMapper();

    protected final AgentDescriptor descriptor;
    protected final AgentContext ctx;

    protected ContainerCliAdapter(AgentDescriptor descriptor, AgentContext ctx) {
        this.descriptor = descriptor;
        this.ctx = ctx;
    }

    protected abstract StreamMapper newMapper();

    /** Hook for adapters to normalize or prefix model identifiers (e.g. OpenRouter routing). */
    protected String resolveModel(String model) {
        return model;
    }

    @Override
    public AgentDescriptor descriptor() {
        return descriptor;
    }

    /** {@code agents/<id>/agent.json} commands.{name} with placeholders expanded. */
    protected List<String> command(String name, AskRequest req, String sessionForTemplate) {
        List<String> template = descriptor.commands().get(name);
        if (template == null) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "agents/" + descriptor.id() + "/agent.json has no commands." + name, Map.of("agent", descriptor.id()));
        }
        List<String> out = new ArrayList<>();
        for (String token : template) {
            Matcher m = PLACEHOLDER.matcher(token);
            if (!m.matches()) {
                out.add(token);
                continue;
            }
            if (m.group(1).equals("model")) {
                String model = resolveModel(req.model());
                if (model != null && !model.isBlank()) {
                    out.add(m.group(2));
                    out.add(model);
                }
            } else {
                String[] sides = m.group(2).split("\\|", -1);
                String fresh = sides[0];
                String resume = sides.length > 1 ? sides[1] : "";
                if (req.agentSessionId() == null || req.agentSessionId().isBlank()) {
                    if (!fresh.isBlank()) {
                        out.add(fresh);
                        out.add(sessionForTemplate);
                    }
                } else if (!resume.isBlank()) {
                    out.add(resume);
                    out.add(req.agentSessionId());
                }
            }
        }
        return out;
    }

    private List<String> exec(AskRequest req, List<String> command) {
        List<String> cmd = new ArrayList<>(List.of("podman", "exec", "-i", "-w", "/work", req.container()));
        cmd.addAll(command);
        return cmd;
    }

    @Override
    public AgentRunHandle ask(AskRequest req, Consumer<AgentEvent> onEvent) {
        StreamMapper mapper = newMapper();
        String prompt = readPrompt(req);
        List<String> cmd = exec(req, command("ask", req, UUID.randomUUID().toString()));
        AtomicBoolean finished = new AtomicBoolean(false);
        Runner.StreamHandle handle = ctx.runner().stream(cmd, ctx.paths().root(), Map.of(), prompt, line -> {
            for (AgentEvent e : mapper.map(line)) onEvent.accept(e);
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
                if (finished.compareAndSet(false, true)) {
                    for (AgentEvent e : mapper.finish(exit, handle.isCancelled(), handle.stderr())) onEvent.accept(e);
                }
                return exit;
            }
        };
    }

    @Override
    public void prepare(AskRequest req) {
        if (descriptor.instructionFile() == null || descriptor.instructionFile().isBlank()) return;
        ctx.runner().run(List.of("podman", "exec", req.container(), "ln", "-sfn", "/work/AGENTS.md", "/work/" + descriptor.instructionFile()),
                ctx.paths().root(), SHORT);
    }

    @Override
    public Suggestion suggest(AskRequest req, String diff) {
        String prompt = "Suggest a git branch name and a one-line commit message for the change below.\n"
                + "Reply with exactly two lines:\nBRANCH: <lowercase-branch-name>\nMESSAGE: <commit message>\n\n"
                + "Task: " + readPrompt(req).strip() + "\n\nDiff:\n" + (diff == null ? "" : diff);
        List<String> cmd = exec(req, command("suggest", req.withAgentSessionId(null), UUID.randomUUID().toString()));
        StringBuilder out = new StringBuilder();
        int exit = ctx.runner().stream(cmd, ctx.paths().root(), Map.of(), prompt, line -> out.append(line).append('\n')).waitFor();
        if (exit != 0) {
            throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, descriptor.id() + " suggest exited with " + exit);
        }
        return parseSuggestion(out.toString());
    }

    static Suggestion parseSuggestion(String text) {
        String body = text;
        try {
            var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text.strip());
            if (n.has("result")) body = n.path("result").asText();
        } catch (Exception ignored) {
            // plain text
        }
        String branch = null;
        String message = null;
        for (String line : body.split("\n")) {
            String l = line.strip();
            if (l.toUpperCase().startsWith("BRANCH:")) branch = l.substring(7).strip();
            else if (l.toUpperCase().startsWith("MESSAGE:")) message = l.substring(8).strip();
        }
        return new Suggestion(branch, message);
    }

    @Override
    public List<Model> models() {
        return models(false);
    }

    /** The agent's own catalogue, with the models configured for a model server of the user's own in front of it. */
    @Override
    public List<Model> models(boolean refresh) {
        return withLocalModels(catalogue(refresh));
    }

    /**
     * With a {@code commands.models} template in {@code agent.json} the agent's own catalogue is used: fetched once
     * into {@code state/agents/<id>/models.json} and re-fetched when {@code refresh} is set. The models declared in
     * {@code agent.json} stay in front — they carry the curated labels and the default. Anything that can go wrong
     * (no image built yet, no network, an output format the parser does not recognise) falls back to the declared list.
     */
    private List<Model> catalogue(boolean refresh) {
        List<String> template = descriptor.commands().get("models");
        if (template == null || template.isEmpty()) return descriptor.models();
        if (!refresh) {
            List<Model> cached = readCatalogue();
            if (!cached.isEmpty()) return cached;
        }
        List<Model> fetched = fetchCatalogue(template);
        if (!fetched.isEmpty()) {
            List<Model> merged = merge(descriptor.models(), fetched);
            writeCatalogue(merged);
            return merged;
        }
        List<Model> cached = readCatalogue();
        return cached.isEmpty() ? descriptor.models() : cached;
    }

    private List<Model> fetchCatalogue(List<String> template) {
        if (ctx.subnetPrefix() == null) return List.of();
        try {
            Runner.Result r = ctx.runner().run(oneOff(template, false), ctx.paths().root(), SHORT);
            return r.success() ? parseModels(r.stdout().lines().toList()) : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The {@code models} command's stdout as models, skipping blank and decorative lines. */
    protected List<Model> parseModels(List<String> lines) {
        List<Model> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : lines) {
            String line = ANSI.matcher(raw).replaceAll("").strip();
            if (line.isEmpty()) continue;
            toModel(line).filter(model -> seen.add(model.id())).ifPresent(out::add);
        }
        return out;
    }

    /**
     * One output line as a model, or empty when the line is not one. The default reads {@code <id>[ <label>]};
     * adapters whose CLI prints something else, or that can build a nicer label, override this.
     */
    protected Optional<Model> toModel(String line) {
        String[] parts = line.split("\\s+", 2);
        if (!MODEL_ID.matcher(parts[0]).matches()) return Optional.empty();
        return Optional.of(new Model(parts[0], parts.length > 1 && !parts[1].isBlank() ? parts[1].strip() : parts[0], false, false));
    }

    /**
     * The models of the user's own model server, right behind the declared ones. They are in the catalogue because
     * the user put them in the agent's configuration file, not because the agent's listing happened to work — so
     * they are offered even when that listing cannot be read, and they are not left at the end of a catalogue of
     * hundreds where the picker's first page never reaches them.
     */
    private List<Model> withLocalModels(List<Model> catalogue) {
        List<Model> local = localModels();
        if (local.isEmpty()) return catalogue;
        Map<String, Model> byId = new LinkedHashMap<>();
        for (Model m : descriptor.models()) byId.put(m.id(), m);
        for (Model m : local) byId.putIfAbsent(m.id(), m);
        for (Model m : catalogue) byId.putIfAbsent(m.id(), m);
        return List.copyOf(byId.values());
    }

    /**
     * {@code <provider>/<model>} for every model configured in the agent's own configuration file — the id the
     * agent's own listing prints for them — labelled the way a fetched model is. An agent that knows nothing of a
     * local model server, or whose configuration file cannot be read, has none.
     */
    private List<Model> localModels() {
        List<String> configured = new LocalModelConfig(ctx).configured(descriptor);
        if (configured.isEmpty()) return List.of();
        String provider = descriptor.localModels().provider();
        List<Model> out = new ArrayList<>();
        for (String model : configured) {
            String id = provider + "/" + model;
            Model offered = toModel(id).orElseGet(() -> new Model(id, id, false, false));
            out.add(new Model(offered.id(), offered.label(), false, true));
        }
        return List.copyOf(out);
    }

    /** Declared models first — their label and their default win — then everything else the agent offers. */
    private static List<Model> merge(List<Model> declared, List<Model> fetched) {
        Map<String, Model> byId = new LinkedHashMap<>();
        for (Model m : declared) byId.put(m.id(), m);
        for (Model m : fetched) byId.putIfAbsent(m.id(), m);
        return List.copyOf(byId.values());
    }

    private Path cataloguePath() {
        return ctx.paths().agentState(descriptor.id()).resolve("models.json");
    }

    private List<Model> readCatalogue() {
        Path f = cataloguePath();
        if (!Files.isRegularFile(f)) return List.of();
        try {
            return List.of(CATALOGUE.readValue(f.toFile(), Model[].class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private void writeCatalogue(List<Model> models) {
        try {
            Path f = cataloguePath();
            Files.createDirectories(f.getParent());
            CATALOGUE.writeValue(f.toFile(), models);
        } catch (Exception ignored) {
            // The catalogue is only a cache; not keeping it means the next call fetches again.
        }
    }

    /** Login state from {@code state/agents/<id>/}: either a credentials file or a variable in the {@code env} file. */
    @Override
    public AuthStatus authStatus() {
        Path state = ctx.paths().agentState(descriptor.id());
        for (Map.Entry<String, String> e : descriptor.authFiles().entrySet()) {
            String spec = e.getValue();
            if (spec.startsWith("env:")) {
                Path env = state.resolve("env");
                if (Files.isRegularFile(env) && !DotEnv.read(env).getOrDefault(spec.substring(4), "").isBlank()) {
                    return new AuthStatus(true, e.getKey(), null, null);
                }
            } else {
                Path f = state.resolve(spec);
                if (Files.isRegularFile(f) && fileHasCredentials(f)) {
                    return new AuthStatus(true, e.getKey(), accountFrom(f), null);
                }
            }
        }
        return new AuthStatus(false, descriptor.authModes().isEmpty() ? "none" : descriptor.authModes().getFirst(), null, null);
    }

    protected boolean fileHasCredentials(Path file) {
        try {
            return Files.size(file) > 2;
        } catch (IOException e) {
            return false;
        }
    }

    /** A short account label for a credentials file; subclasses read their own format. */
    protected String accountFrom(Path file) {
        return null;
    }

    /** {@code podman run --rm -it} with the state directory mounted and the proxy environment, running the login command for the mode. */
    @Override
    public Optional<List<String>> loginCommand(String mode) {
        String m = mode == null || mode.isBlank() ? descriptor.authModes().getFirst() : mode;
        Map<String, List<String>> byMode = descriptor.loginCommands().getOrDefault("login", Map.of());
        List<String> loginCmd = byMode.get(m);
        if (loginCmd == null) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, descriptor.id() + " has no login flow for mode '" + m + "' (modes: "
                    + String.join(", ", byMode.keySet()) + ")", Map.of("mode", m));
        }
        if (loginCmd.isEmpty()) return Optional.empty();
        if (ctx.subnetPrefix() == null) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Run 'setup.sh rebuild-config' before logging in (no network yet)");
        }
        return Optional.of(oneOff(loginCmd, true));
    }

    /**
     * {@code podman run --rm} of the agent's own image, with its state directory mounted and the proxy
     * environment: how the agent is reached when there is no session container (login, model catalogue).
     */
    private List<String> oneOff(List<String> command, boolean tty) {
        Path state = ensureState();
        List<String> cmd = new ArrayList<>(List.of("podman", "run", "--rm"));
        if (tty) cmd.add("-it");
        cmd.addAll(List.of("--network", ctx.project() + "_internal",
                "--add-host", "gateway:" + ctx.gatewayIp()));
        // As in a session container: a model server on the developer's own machine is reached by this name.
        if (ctx.platform().os() == Platform.Os.LINUX) {
            cmd.addAll(List.of("--add-host", "host.containers.internal:host-gateway"));
        }
        cmd.addAll(List.of("--userns", "keep-id",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges"));
        for (String k : List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")) {
            cmd.addAll(List.of("-e", k + "=" + ctx.proxyUrl()));
        }
        cmd.addAll(List.of("-e", "NO_PROXY=localhost,127.0.0.1,gateway", "-e", "no_proxy=localhost,127.0.0.1,gateway"));
        descriptor.env().forEach((k, v) -> cmd.addAll(List.of("-e", k + "=" + v)));
        if (descriptor.configDir() != null) {
            cmd.addAll(List.of("-v", state + ":" + descriptor.configDir() + ":rw,z"));
        }
        cmd.add(StackService.agentImage(descriptor.id()).replace(no.syntakserror.lyngvi.core.version.Version.current().toString(), ctx.version()));
        cmd.addAll(command);
        return cmd;
    }

    @Override
    public int login(String mode, Consumer<String> onLine) {
        Optional<List<String>> cmd = loginCommand(mode);
        if (cmd.isEmpty()) {
            String m = mode == null || mode.isBlank() ? descriptor.authModes().getFirst() : mode;
            ensureState();
            onLine.accept("Mode '" + m + "' is configured by putting the credential in " + ctx.paths().agentState(descriptor.id()).resolve("env")
                    + " (see agents/" + descriptor.id() + "/agent.json authFiles).");
            return 0;
        }
        return ctx.runner().interactive(cmd.get(), ctx.paths().root());
    }

    private Path ensureState() {
        Path state = ctx.paths().agentState(descriptor.id());
        try {
            Files.createDirectories(state);
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException | UnsupportedOperationException e) {
            // permissions are best effort on non-POSIX file systems
        }
        return state;
    }

    @Override
    public void logout() {
        Path state = ctx.paths().agentState(descriptor.id());
        for (String spec : descriptor.authFiles().values()) {
            try {
                if (spec.startsWith("env:")) {
                    Files.deleteIfExists(state.resolve("env"));
                } else {
                    Files.deleteIfExists(state.resolve(spec));
                }
            } catch (IOException e) {
                throw new SandboxException(ErrorCode.INTERNAL, "Cannot remove credentials: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public AgentUsage usage() {
        return AgentUsage.unsupported();
    }

    protected static String readPrompt(AskRequest req) {
        try {
            return req.promptFile() == null ? "" : Files.readString(req.promptFile());
        } catch (IOException e) {
            return "";
        }
    }
}
