package no.syntakserror.lyngvi.gateway.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One configured system.
 *
 * @param config plugin-specific configuration as plain JSON values (maps, lists, strings, numbers)
 */
public record Instance(String id, String plugin, String label, String hostname, List<String> hostAliases,
                       String baseUrlEnv, SecretSpec secret, AllowlistSpec allowlist, List<String> capabilities,
                       Map<String, Object> config) {

    public Instance {
        hostAliases = hostAliases == null ? List.of() : List.copyOf(hostAliases);
        capabilities = capabilities == null ? List.of("read") : List.copyOf(capabilities);
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public boolean canWrite() {
        return capabilities.contains("write");
    }

    /** {@code <ID>_GATEWAY_URL}: the environment variable agents use to reach the instance. */
    public String urlEnvName() {
        return id.toUpperCase(Locale.ROOT).replace('-', '_') + "_GATEWAY_URL";
    }

    public static boolean isValidId(String id) {
        return id != null && id.matches("[a-z][a-z0-9-]*");
    }
}
