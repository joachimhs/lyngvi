package no.syntakserror.lyngvi.core.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import no.syntakserror.lyngvi.core.ask.AgentContext;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.config.DotEnv;

/** OpenCode inside the session container (spec §6.4). Behaviour comes from {@code agents/opencode/agent.json}. */
public final class OpenCodeAdapter extends ContainerCliAdapter {

    public static final String ID = "opencode";

    private static final Set<String> KNOWN_OPENCODE_PROVIDERS = Set.of(
            "anthropic", "openai", "google", "github-copilot", "ollama", "lmstudio",
            "azure", "bedrock", "openrouter", "vertex", "together",
            "groq", "mistral", "deepseek", "cohere", "fireworks"
    );

    /** Where opencode reads its own configuration: {@code XDG_CONFIG_HOME} inside the state directory (see {@code agent.json} env). */
    private static final List<String> CONFIG_FILES = List.of("config/opencode/opencode.json", "config/opencode/opencode.jsonc");

    public OpenCodeAdapter(AgentDescriptor descriptor, AgentContext ctx) {
        super(descriptor, ctx);
    }

    @Override
    protected StreamMapper newMapper() {
        return new OpenCodeStreamMapper(descriptor.id());
    }

    /**
     * {@code opencode models} prints one {@code provider/model} per line. Requiring the slash keeps the headings and
     * hints it prints around the list out of the catalogue, and splits the label. Everything after the first slash is
     * the model — OpenRouter ids carry their own, as in {@code openrouter/anthropic/claude-sonnet-4-5}.
     */
    @Override
    protected Optional<Model> toModel(String line) {
        String id = line.split("\\s+", 2)[0];
        int slash = id.indexOf('/');
        if (slash <= 0 || slash == id.length() - 1) return Optional.empty();
        return super.toModel(id).map(m -> new Model(m.id(), id.substring(slash + 1) + " (" + id.substring(0, slash) + ")", false, m.local()));
    }

    @Override
    protected String resolveModel(String model) {
        if (model == null || model.isBlank()) return model;
        if (model.startsWith("openrouter/")) return model;
        Set<String> configured = configuredProviders();
        if (model.contains("/")) {
            String prefix = model.substring(0, model.indexOf('/'));
            if (configured.contains(prefix)) {
                return model;
            }
            if (configured.contains("openrouter") || !KNOWN_OPENCODE_PROVIDERS.contains(prefix)) {
                return "openrouter/" + model;
            }
        } else if (configured.contains("openrouter") && !configured.contains(model)) {
            return "openrouter/" + model;
        }
        return model;
    }

    /**
     * The providers this installation can actually reach: the ones with credentials, plus the ones declared in
     * opencode's own configuration. A local model server (Ollama, LM Studio, an MLX server behind the LM Studio
     * provider) has no credentials at all — {@code opencode.json} is the only place it exists, and its models must
     * not be re-routed to OpenRouter.
     */
    private Set<String> configuredProviders() {
        Set<String> providers = new HashSet<>();
        for (String name : CONFIG_FILES) {
            Path config = ctx.paths().agentState(descriptor.id()).resolve(name);
            if (!Files.isRegularFile(config)) continue;
            try {
                Jsonc.MAPPER.readTree(Files.readString(config)).path("provider").fieldNames().forEachRemaining(providers::add);
            } catch (Exception ignored) {
                // A configuration opencode itself would reject tells us nothing about providers.
            }
        }
        Path authFile = ctx.paths().agentState(descriptor.id()).resolve("data/opencode/auth.json");
        if (Files.isRegularFile(authFile)) {
            try {
                JsonNode n = new ObjectMapper().readTree(Files.readString(authFile));
                n.fieldNames().forEachRemaining(providers::add);
            } catch (Exception ignored) {
            }
        }
        Path envFile = ctx.paths().agentState(descriptor.id()).resolve("env");
        if (Files.isRegularFile(envFile)) {
            try {
                Map<String, String> env = DotEnv.read(envFile);
                if (!env.getOrDefault("OPENROUTER_API_KEY", "").isBlank()) providers.add("openrouter");
                if (!env.getOrDefault("ANTHROPIC_API_KEY", "").isBlank()) providers.add("anthropic");
                if (!env.getOrDefault("OPENAI_API_KEY", "").isBlank()) providers.add("openai");
                if (!env.getOrDefault("GEMINI_API_KEY", "").isBlank()) providers.add("google");
            } catch (Exception ignored) {
            }
        }
        return providers;
    }

    /** {@code auth.json} → the first configured provider. */
    @Override
    protected String accountFrom(Path file) {
        try {
            JsonNode n = new ObjectMapper().readTree(Files.readString(file));
            return n.fieldNames().hasNext() ? n.fieldNames().next() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected boolean fileHasCredentials(Path file) {
        try {
            return new ObjectMapper().readTree(Files.readString(file)).fieldNames().hasNext();
        } catch (Exception e) {
            return false;
        }
    }
}
