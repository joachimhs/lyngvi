package no.syntakserror.lyngvi.core.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.gateway.api.AllowlistSpec;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import no.syntakserror.lyngvi.gateway.api.SecretSpec;

/**
 * Reads and writes {@code gateway/gateway.manifest.json} (spec §8.3) into the records shared with the
 * gateway runtime. The runtime has its own reader; the JSON shape is the contract.
 */
public final class ManifestIo {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ManifestIo() {}

    /** The manifest, or {@link Manifest#empty()} when the file does not exist. */
    public static Manifest read(Path file) {
        if (!Files.isRegularFile(file)) return Manifest.empty();
        try {
            return fromNode(JSON.readTree(file.toFile()));
        } catch (IOException | IllegalArgumentException e) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Cannot read " + file + ": " + e.getMessage(), Map.of("file", file.toString()));
        }
    }

    static Manifest fromNode(JsonNode n) {
        List<Instance> instances = new ArrayList<>();
        for (JsonNode i : n.path("instances")) {
            List<String> aliases = new ArrayList<>();
            i.path("hostAliases").forEach(a -> aliases.add(a.asText()));
            List<String> caps = new ArrayList<>();
            i.path("capabilities").forEach(c -> caps.add(c.asText()));
            Map<String, Object> config = i.has("config") ? JSON.convertValue(i.path("config"), new TypeReference<Map<String, Object>>() {}) : Map.of();
            String id = i.path("id").asText();
            instances.add(new Instance(id, i.path("plugin").asText("http"), i.path("label").asText(id),
                    i.path("hostname").asText(null), aliases, i.path("baseUrlEnv").asText(null),
                    new SecretSpec(i.path("secret").path("env").asText(null), i.path("secret").path("scheme").asText("bearer")),
                    new AllowlistSpec(i.path("allowlist").path("file").asText(id + ".txt"), i.path("allowlist").path("mode").asText(null),
                            i.path("allowlist").path("label").asText("entries")),
                    caps.isEmpty() ? List.of("read") : caps, config));
        }
        return new Manifest(n.path("schemaVersion").asInt(Manifest.CURRENT_SCHEMA_VERSION), n.path("ipSuffix").asInt(10), instances);
    }

    /** Writes the manifest atomically. */
    public static void write(Path file, Manifest m) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(toMap(m)) + "\n");
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> toMap(Manifest m) {
        List<Map<String, Object>> instances = new ArrayList<>();
        for (Instance i : m.instances()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", i.id());
            o.put("plugin", i.plugin());
            o.put("label", i.label());
            o.put("hostname", i.hostname());
            o.put("hostAliases", i.hostAliases());
            o.put("baseUrlEnv", i.baseUrlEnv());
            Map<String, Object> secret = new LinkedHashMap<>();
            secret.put("env", i.secret().env());
            secret.put("scheme", i.secret().scheme());
            o.put("secret", secret);
            Map<String, Object> al = new LinkedHashMap<>();
            al.put("file", i.allowlist().file());
            if (i.allowlist().mode() != null) al.put("mode", i.allowlist().mode());
            al.put("label", i.allowlist().label());
            o.put("allowlist", al);
            o.put("capabilities", i.capabilities());
            o.put("config", i.config());
            instances.add(o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", m.schemaVersion());
        out.put("ipSuffix", m.ipSuffix());
        out.put("instances", instances);
        return out;
    }
}
