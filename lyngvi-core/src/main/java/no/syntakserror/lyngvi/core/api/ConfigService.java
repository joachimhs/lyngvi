package no.syntakserror.lyngvi.core.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.RepoEntry;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.config.ResourcePreset;
import no.syntakserror.lyngvi.core.contract.ProfileInfo;
import no.syntakserror.lyngvi.core.stack.MachineInfo;
import no.syntakserror.lyngvi.core.stack.ResourceFit;

/** Repositories, resource limits and profile: the {@code repo}, {@code resources} and {@code profile} commands. */
public final class ConfigService {

    private final Sandbox sandbox;

    ConfigService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public List<RepoEntry> repos() {
        return sandbox.config().repos();
    }

    public RepoEntry addRepo(Path path, Optional<String> name, Optional<History> history) {
        List<RepoEntry> repos = new ArrayList<>(sandbox.config().repos());
        RepoEntry entry = Sandbox.newRepoEntry(path, name, history, repos);
        repos.add(entry);
        sandbox.updateConfig(sandbox.config().withRepos(repos));
        return entry;
    }

    /** Removes by mount name or by host path. */
    public RepoEntry removeRepo(String pathOrName) {
        Path asPath = Path.of(pathOrName).toAbsolutePath().normalize();
        List<RepoEntry> repos = new ArrayList<>(sandbox.config().repos());
        RepoEntry match = repos.stream()
                .filter(r -> r.name().equals(pathOrName) || r.path().equals(asPath))
                .findFirst()
                .orElseThrow(() -> new SandboxException(ErrorCode.VALIDATION_FAILED,
                        "No registered repository matches '" + pathOrName + "'", Map.of("repo", pathOrName)));
        repos.remove(match);
        sandbox.updateConfig(sandbox.config().withRepos(repos));
        return match;
    }

    public ResourceLimits resources() {
        return sandbox.config().resources();
    }

    /** Applies the non-null fields of {@code partial}; at least one must be set. */
    public ResourceLimits setResources(ResourceLimits partial) {
        if (partial.mem() == null && partial.memswap() == null && partial.cpus() == null && partial.pids() == null) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Set at least one of --mem, --memswap, --cpus, --pids");
        }
        ResourceLimits merged = sandbox.config().resources().merge(partial);
        List<String> bad = merged.invalidFields();
        if (!bad.isEmpty()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "Invalid value for " + String.join(", ", bad) + " (memory like 8g or 512m, cpus like 4.0, pids an integer)",
                    Map.of("fields", bad));
        }
        sandbox.updateConfig(sandbox.config().withResources(merged));
        return merged;
    }

    /** The presets the profile offers, plus one sized to this machine when it can be inspected. */
    public List<ResourcePreset> resourcePresets() {
        return resourcePresets(sandbox.stackService().machine());
    }

    /** As {@link #resourcePresets()}, for callers that have already inspected the machine (it is a subprocess). */
    public List<ResourcePreset> resourcePresets(MachineInfo machine) {
        List<ResourcePreset> presets = new ArrayList<>(sandbox.profile().resourcePresets());
        ResourceFit.machineMax(machine)
                .filter(m -> presets.stream().noneMatch(p -> p.id().equals(m.id())))
                .ifPresent(presets::add);
        return List.copyOf(presets);
    }

    /** A preset by id, for {@code --preset} and the pickers. */
    public ResourcePreset resourcePreset(String id) {
        List<ResourcePreset> presets = resourcePresets();
        return ResourcePreset.find(presets, id).orElseThrow(() -> new SandboxException(ErrorCode.INVALID_ARGUMENT,
                "No resource preset '" + id + "' (available: " + presets.stream().map(ResourcePreset::id).collect(Collectors.joining(", ")) + ")",
                Map.of("preset", id)));
    }

    public ProfileInfo profile() {
        return sandbox.profileInfo();
    }

    /** The ports the proxy lets through to a model server on the developer's own machine ({@code localModels}). */
    public List<Integer> localModelPorts() {
        return sandbox.profile().localModels().ports();
    }

    /** The one private host those ports are open to — every other private address is denied ({@code localModels}). */
    public String localModelHost() {
        return sandbox.profile().localModels().host();
    }

    /**
     * Opens one more port to the local model host: the profile is the policy, so this is an explicit change to it.
     * The proxy configuration is re-rendered and the running proxy re-reads it — a port in {@code squid.conf} is an
     * ACL, not a file Squid watches, so nothing less than a render and a reload takes effect.
     *
     * @return the ports as they now stand
     */
    public List<Integer> openLocalModelPort(int port) {
        if (port <= 0 || port > 65535) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Not a TCP port: " + port, Map.of("port", port));
        }
        List<Integer> ports = new ArrayList<>(localModelPorts());
        if (!ports.contains(port)) {
            ports.add(port);
            String host = sandbox.profile().localModels().host();
            updateProfile(root -> {
                com.fasterxml.jackson.databind.node.ObjectNode local = root.with("localModels");
                local.put("host", host);
                com.fasterxml.jackson.databind.node.ArrayNode arr = local.putArray("ports");
                ports.forEach(arr::add);
            });
        }
        sandbox.sandboxService().rebuildConfig();
        sandbox.stackService().reloadProxy();
        return List.copyOf(ports);
    }

    /** Toolchain fragments bundled under {@code image/fragments/}, by name. */
    public List<String> availableToolchains() {
        Path dir = sandbox.paths().imageFragment("x").getParent();
        if (!java.nio.file.Files.isDirectory(dir)) return List.of();
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".Containerfile"))
                    .map(n -> n.substring(0, n.length() - ".Containerfile".length())).sorted().toList();
        } catch (java.io.IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot list " + dir + ": " + e.getMessage(), e);
        }
    }

    public List<String> toolchains() {
        return sandbox.profile().toolchains();
    }

    /**
     * What each bundled fragment is for, read from its first line — {@code # --- toolchain: <name> (<description>) ---};
     * a fragment whose header has no parenthesis, or cannot be read, is absent from the map. This feeds tooltips and
     * must never stop the toolchain list itself: a hand-edited fragment in another encoding is not an error here.
     */
    public Map<String, String> toolchainDescriptions() {
        Map<String, String> out = new java.util.TreeMap<>();
        for (String name : availableToolchains()) {
            String first;
            try {
                // new String(bytes, UTF_8) replaces malformed input instead of throwing, unlike Files.lines/readString.
                first = new String(java.nio.file.Files.readAllBytes(sandbox.paths().imageFragment(name)), java.nio.charset.StandardCharsets.UTF_8)
                        .lines().findFirst().orElse("");
            } catch (java.io.IOException | RuntimeException e) {
                continue;
            }
            java.util.regex.Matcher m = FRAGMENT_HEADER.matcher(first);
            if (m.matches() && m.group(1) != null) out.put(name, m.group(1).strip());
        }
        return out;
    }

    private static final java.util.regex.Pattern FRAGMENT_HEADER =
            java.util.regex.Pattern.compile("#\\s*---\\s*toolchain:\\s*\\S+\\s*(?:\\((.*)\\))?\\s*---\\s*");

    /** Rewrites {@code toolchains} in the sandbox's local {@code profile.json}; {@code rebuild-config} and {@code build} apply it. */
    public void setToolchains(List<String> toolchains) {
        List<String> available = availableToolchains();
        for (String t : toolchains) {
            if (!available.contains(t)) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Unknown toolchain '" + t + "' (available: " + String.join(", ", available) + ")",
                        Map.of("toolchain", t, "available", available));
            }
        }
        updateProfile(root -> {
            com.fasterxml.jackson.databind.node.ArrayNode arr = root.putArray("toolchains");
            toolchains.forEach(arr::add);
        });
    }

    /** Branches deliveries may never land on, as configured in this sandbox's {@code profile.json}. */
    public List<String> protectedBranches() {
        return sandbox.profile().git().protectedBranches();
    }

    /** Rewrites {@code git.protectedBranches} in the sandbox's local profile. */
    public void setProtectedBranches(List<String> branches) {
        for (String b : branches) {
            if (b == null || b.isBlank() || b.contains(" ")) {
                throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Not a branch name: '" + b + "'", Map.of("branch", String.valueOf(b)));
            }
        }
        updateProfile(root -> {
            com.fasterxml.jackson.databind.node.ObjectNode git = root.with("git");
            com.fasterxml.jackson.databind.node.ArrayNode arr = git.putArray("protectedBranches");
            branches.stream().map(String::strip).distinct().forEach(arr::add);
        });
    }

    private void updateProfile(java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> edit) {
        Path json = sandbox.paths().profileJson();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
            com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json.toFile());
            edit.accept(root);
            java.nio.file.Files.writeString(json, mapper.writeValueAsString(root) + "\n");
        } catch (java.io.IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot update " + json + ": " + e.getMessage(), e);
        }
        sandbox.reload();
    }
}
