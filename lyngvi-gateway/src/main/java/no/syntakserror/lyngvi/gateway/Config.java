package no.syntakserror.lyngvi.gateway;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Runtime configuration from environment variables (spec §8.10). */
public record Config(int port, int altPort, int tlsPort, String tlsCertFile, String tlsKeyFile, String token,
                     long upstreamTimeoutMs, int maxLimit, String allowlistDir, boolean allowlistEnforced,
                     String manifestPath, String pluginDir, Set<String> allowWrite, String version) {

    public static Config fromEnv(Map<String, String> env, String version) {
        return new Config(
                intOf(env, "PORT", 8080),
                intOf(env, "HTTP_ALT_PORT", 0),
                intOf(env, "TLS_PORT", 0),
                env.get("TLS_CERT_FILE"),
                env.get("TLS_KEY_FILE"),
                blankToNull(env.get("GATEWAY_TOKEN")),
                Long.parseLong(env.getOrDefault("UPSTREAM_TIMEOUT_MS", "15000")),
                intOf(env, "MAX_LIMIT", 100),
                env.getOrDefault("ALLOWLIST_DIR", "/etc/gateway/allowlists"),
                !"false".equalsIgnoreCase(env.getOrDefault("ALLOWLIST_ENFORCED", "true")),
                env.getOrDefault("GATEWAY_MANIFEST", "/etc/gateway/gateway.manifest.json"),
                env.getOrDefault("PLUGIN_DIR", "/app/plugins"),
                Arrays.stream(env.getOrDefault("ALLOW_WRITE", "").split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet()),
                version);
    }

    private static int intOf(Map<String, String> env, String key, int fallback) {
        String v = env.get(key);
        return v == null || v.isBlank() ? fallback : Integer.parseInt(v.strip());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public List<String> hostnamesOf(no.syntakserror.lyngvi.gateway.api.Manifest m) {
        return m.hostnames();
    }
}
