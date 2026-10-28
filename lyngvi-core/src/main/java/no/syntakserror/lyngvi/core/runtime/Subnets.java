package no.syntakserror.lyngvi.core.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;

/**
 * Allocates one /24 per sandbox from a pool and records it in a host-wide registry
 * (spec §7.5), so two sandboxes on the same machine never collide on Podman networks.
 *
 * <p>Registry format: one line per sandbox, {@code <prefix>\t<sandbox path>}.
 */
public final class Subnets {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path registry;
    private final Runner runner;

    public Subnets(Path registry, Runner runner) {
        this.registry = registry;
        this.runner = runner;
    }

    /** {@code 172.31.240–254}, {@code 172.30.240–254}, then {@code 10.200.0–15}. */
    public static List<String> candidates() {
        List<String> out = new ArrayList<>();
        for (int i = 240; i <= 254; i++) out.add("172.31." + i);
        for (int i = 240; i <= 254; i++) out.add("172.30." + i);
        for (int i = 0; i <= 15; i++) out.add("10.200." + i);
        return out;
    }

    /** Returns the sandbox's prefix, allocating and recording one if it has none. */
    public synchronized String allocate(Path sandboxRoot) {
        return allocate(sandboxRoot, null);
    }

    /**
     * Returns the sandbox's prefix. {@code inUse} is the prefix the sandbox is already configured for:
     * it is reclaimed whenever no other sandbox holds it, so a lost registry entry never renumbers a
     * sandbox whose network, containers and configuration already run on that prefix.
     */
    public synchronized String allocate(Path sandboxRoot, String inUse) {
        Path key = sandboxRoot.toAbsolutePath().normalize();
        Map<String, Path> reg = registered();
        for (Map.Entry<String, Path> e : reg.entrySet()) {
            if (e.getValue().equals(key)) return e.getKey();
        }
        // A sandbox directory that is gone can never release its prefix itself; drop it so the pool does not leak.
        reg.values().removeIf(path -> !Files.isDirectory(path));
        if (inUse != null && !inUse.isBlank() && !reg.containsKey(inUse)) {
            reg.put(inUse, key);
            write(reg);
            return inUse;
        }
        Set<String> taken = new HashSet<>(reg.keySet());
        taken.addAll(podmanUsedPrefixes());
        for (String candidate : candidates()) {
            if (!taken.contains(candidate)) {
                reg.put(candidate, key);
                write(reg);
                return candidate;
            }
        }
        throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                "No free subnet prefix left; release one with 'setup.sh down' in an unused sandbox or edit " + registry,
                Map.of("registry", registry.toString()));
    }

    public synchronized void release(Path sandboxRoot) {
        Path key = sandboxRoot.toAbsolutePath().normalize();
        Map<String, Path> reg = registered();
        reg.values().removeIf(key::equals);
        write(reg);
    }

    /** Prefix → sandbox path, in file order. */
    public Map<String, Path> registered() {
        Map<String, Path> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(registry)) return out;
        try {
            for (String line : Files.readAllLines(registry, StandardCharsets.UTF_8)) {
                String l = line.strip();
                if (l.isEmpty() || l.startsWith("#")) continue;
                String[] parts = l.split("\t", 2);
                if (parts.length == 2) out.put(parts[0].strip(), Path.of(parts[1].strip()));
            }
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + registry + ": " + e.getMessage(), e);
        }
        return out;
    }

    /** A Podman network and the /24 prefixes of its IPv4 subnets. */
    public record Network(String name, Set<String> prefixes) {}

    /** Podman's networks; empty when Podman is unavailable. */
    public List<Network> podmanNetworks() {
        List<Network> out = new ArrayList<>();
        Runner.Result r;
        try {
            r = runner.run(List.of("podman", "network", "ls", "--format", "json"), null, Duration.ofSeconds(20));
        } catch (SandboxException e) {
            return out;
        }
        if (!r.success() || r.stdout().isBlank()) return out;
        try {
            JsonNode networks = JSON.readTree(r.stdout());
            for (JsonNode n : networks) {
                Set<String> prefixes = new HashSet<>();
                for (JsonNode s : n.path("subnets")) {
                    String subnet = s.path("subnet").asText("");
                    String[] octets = subnet.split("/")[0].split("\\.");
                    if (octets.length == 4) prefixes.add(octets[0] + "." + octets[1] + "." + octets[2]);
                }
                out.add(new Network(n.path("name").asText(""), prefixes));
            }
        } catch (IOException ignored) {
            // unparseable output: treat as no information
        }
        return out;
    }

    /** First three octets of every IPv4 subnet Podman currently has a network for. */
    public Set<String> podmanUsedPrefixes() {
        Set<String> out = new HashSet<>();
        for (Network n : podmanNetworks()) out.addAll(n.prefixes());
        return out;
    }

    private void write(Map<String, Path> reg) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Path> e : reg.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        try {
            Files.createDirectories(registry.toAbsolutePath().getParent());
            Files.writeString(registry, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + registry + ": " + e.getMessage(), e);
        }
    }
}
