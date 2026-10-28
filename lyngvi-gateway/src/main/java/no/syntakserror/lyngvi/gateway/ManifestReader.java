package no.syntakserror.lyngvi.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.api.AllowlistSpec;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import no.syntakserror.lyngvi.gateway.api.SecretSpec;

/** Reads and writes {@code gateway.manifest.json} into the shared API records. Used by the runtime and the host core. */
public final class ManifestReader {

    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    private ManifestReader() {}

    public static Manifest read(String json) {
        try {
            return fromNode(JSON.readTree(json));
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed manifest: " + e.getMessage(), e);
        }
    }

    public static Manifest read(Path file) {
        if (!Files.isRegularFile(file)) return Manifest.empty();
        try {
            return read(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public static Manifest fromNode(JsonNode n) {
        List<Instance> instances = new ArrayList<>();
        for (JsonNode i : n.path("instances")) {
            List<String> aliases = new ArrayList<>();
            i.path("hostAliases").forEach(a -> aliases.add(a.asText()));
            List<String> caps = new ArrayList<>();
            i.path("capabilities").forEach(c -> caps.add(c.asText()));
            Map<String, Object> config = i.has("config") ? JSON.convertValue(i.path("config"), new TypeReference<Map<String, Object>>() {}) : Map.of();
            instances.add(new Instance(
                    i.path("id").asText(), i.path("plugin").asText("http"), i.path("label").asText(i.path("id").asText()),
                    i.path("hostname").asText(null), aliases, i.path("baseUrlEnv").asText(null),
                    new SecretSpec(i.path("secret").path("env").asText(null), i.path("secret").path("scheme").asText("bearer")),
                    new AllowlistSpec(i.path("allowlist").path("file").asText(i.path("id").asText() + ".txt"),
                            i.path("allowlist").path("mode").asText(null), i.path("allowlist").path("label").asText("entries")),
                    caps.isEmpty() ? List.of("read") : caps, config));
        }
        return new Manifest(n.path("schemaVersion").asInt(Manifest.CURRENT_SCHEMA_VERSION), n.path("ipSuffix").asInt(10), instances);
    }

    public static String write(Manifest m) {
        try {
            return JSON.writeValueAsString(toMap(m)) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> toMap(Manifest m) {
        List<Map<String, Object>> instances = new ArrayList<>();
        for (Instance i : m.instances()) {
            Map<String, Object> o = new java.util.LinkedHashMap<>();
            o.put("id", i.id());
            o.put("plugin", i.plugin());
            o.put("label", i.label());
            o.put("hostname", i.hostname());
            o.put("hostAliases", i.hostAliases());
            o.put("baseUrlEnv", i.baseUrlEnv());
            o.put("secret", Map.of("env", i.secret().env(), "scheme", i.secret().scheme()));
            Map<String, Object> al = new java.util.LinkedHashMap<>();
            al.put("file", i.allowlist().file());
            if (i.allowlist().mode() != null) al.put("mode", i.allowlist().mode());
            al.put("label", i.allowlist().label());
            o.put("allowlist", al);
            o.put("capabilities", i.capabilities());
            o.put("config", i.config());
            instances.add(o);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("schemaVersion", m.schemaVersion());
        out.put("ipSuffix", m.ipSuffix());
        out.put("instances", instances);
        return out;
    }
}
