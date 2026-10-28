package no.syntakserror.lyngvi.core.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/**
 * The parts of {@code agents/<id>/agent.json} the renderer needs (spec §6.1).
 * The full descriptor arrives with the agent-run increment; unknown fields are ignored here.
 *
 * @param egress    hosts the agent needs; optional (telemetry) hosts are excluded from the allowlist
 * @param configDir the agent's configuration directory inside the container, mounted from {@code state/agents/<id>}; null when none
 * @param skillsDir where inside {@code configDir} the agent reads skills from ({@code "skills"} for Claude Code);
 *                  null for an agent that has no notion of them, and then the sandbox's skills are not part of
 *                  how it works (spec §3.6)
 */
public record AgentDescriptor(String id, String label, List<String> egress, List<String> optionalEgress, Path dir, String configDir,
                              String skillsDir, String instructionFile, Map<String, String> env, List<String> authModes, boolean needsContainer,
                              List<String> requires, List<Model> models, Map<String, List<String>> commands,
                              Map<String, Map<String, List<String>>> loginCommands, Map<String, String> authFiles, String pinnedCli,
                              LocalModels localModels) {

    /**
     * Where the agent keeps a model server running on the developer's own machine, when it can use one:
     * the provider name in its own configuration, the file that configuration lives in (relative to
     * {@code state/agents/<id>/}), the address to offer when there is none yet, and the environment variable the
     * agent reads the server's API key from. Null when the agent has no such notion — it is what makes the GUI's
     * «Local models» offer sense for one agent and not for another.
     */
    public record LocalModels(String provider, String configFile, String defaultBaseUrl, String apiKeyEnv) {}

    /** A descriptor for a built-in adapter that has no {@code agents/<id>/} directory. */
    public static AgentDescriptor builtIn(String id, String label) {
        return new AgentDescriptor(id, label, List.of(), List.of(), null, null, null, null, Map.of(), List.of("none"), true,
                List.of(), List.of(), Map.of(), Map.of(), Map.of(), null, null);
    }

    /** True when {@code agents/<id>/image.Containerfile} exists, i.e. the agent gets its own image. */
    public boolean hasImage() {
        return dir != null && Files.isRegularFile(dir.resolve("image.Containerfile"));
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static AgentDescriptor read(Path agentJson) {
        try {
            JsonNode n = JSON.readTree(agentJson.toFile());
            List<String> egress = new ArrayList<>();
            List<String> optional = new ArrayList<>();
            for (JsonNode e : n.path("egress")) {
                if (e.isTextual()) {
                    egress.add(e.asText());
                } else if (e.path("optional").asBoolean(false)) {
                    optional.add(e.path("host").asText());
                } else {
                    egress.add(e.path("host").asText());
                }
            }
            String id = n.path("id").asText(agentJson.getParent().getFileName().toString());
            String configDir = n.hasNonNull("configDir") ? n.path("configDir").asText() : null;
            String skillsDir = n.hasNonNull("skillsDir") ? n.path("skillsDir").asText() : null;
            String instructionFile = n.hasNonNull("instructionFile") ? n.path("instructionFile").asText() : null;
            Map<String, String> env = new java.util.LinkedHashMap<>();
            n.path("env").fields().forEachRemaining(f -> env.put(f.getKey(), f.getValue().asText()));
            List<String> authModes = new ArrayList<>();
            n.path("auth").path("modes").forEach(m -> authModes.add(m.asText()));
            List<String> requires = new ArrayList<>();
            n.path("requires").forEach(r -> requires.add(r.asText()));
            List<Model> models = new ArrayList<>();
            for (JsonNode m : n.path("models")) {
                models.add(new Model(m.path("id").asText(), m.path("label").asText(m.path("id").asText()), m.path("default").asBoolean(false), false));
            }
            Map<String, List<String>> commands = new java.util.LinkedHashMap<>();
            Map<String, Map<String, List<String>>> loginCommands = new java.util.LinkedHashMap<>();
            n.path("commands").fields().forEachRemaining(f -> {
                if (f.getValue().isArray()) {
                    commands.put(f.getKey(), toList(f.getValue()));
                } else if (f.getValue().isObject()) {
                    Map<String, List<String>> byMode = new java.util.LinkedHashMap<>();
                    f.getValue().fields().forEachRemaining(m -> byMode.put(m.getKey(), toList(m.getValue())));
                    loginCommands.put(f.getKey(), byMode);
                }
            });
            Map<String, String> authFiles = new java.util.LinkedHashMap<>();
            n.path("authFiles").fields().forEachRemaining(f -> authFiles.put(f.getKey(), f.getValue().asText()));
            String pinned = n.hasNonNull("pinnedCli") ? n.path("pinnedCli").asText() : null;
            JsonNode local = n.path("localModels");
            LocalModels localModels = local.isObject() && !local.path("provider").asText("").isBlank()
                    ? new LocalModels(local.path("provider").asText(), local.path("configFile").asText(),
                            local.path("defaultBaseUrl").asText(""), local.path("apiKeyEnv").asText(""))
                    : null;
            return new AgentDescriptor(id, n.path("label").asText(id), egress, optional, agentJson.getParent(), configDir,
                    skillsDir, instructionFile, env, authModes.isEmpty() ? List.of("none") : authModes, n.path("needsContainer").asBoolean(true),
                    requires, models, commands, loginCommands, authFiles, pinned, localModels);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Cannot read " + agentJson + ": " + e.getMessage(),
                    Map.of("file", agentJson.toString()));
        }
    }

    private static List<String> toList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(x -> out.add(x.asText()));
        return out;
    }

    /** The agent with this id, when {@code agents/<id>/agent.json} exists. */
    public static java.util.Optional<AgentDescriptor> find(Path agentsDir, String id) {
        Path f = agentsDir.resolve(id).resolve("agent.json");
        return Files.isRegularFile(f) ? java.util.Optional.of(read(f)) : java.util.Optional.empty();
    }

    /** Every agent under {@code agents/} that has an {@code agent.json}, sorted by id. */
    public static List<AgentDescriptor> readAll(Path agentsDir) {
        List<AgentDescriptor> out = new ArrayList<>();
        if (!Files.isDirectory(agentsDir)) return out;
        try (var dirs = Files.list(agentsDir)) {
            dirs.filter(Files::isDirectory)
                    .map(d -> d.resolve("agent.json"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(f -> out.add(read(f)));
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot list " + agentsDir + ": " + e.getMessage(), e);
        }
        return out;
    }
}
