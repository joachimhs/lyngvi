package no.syntakserror.lyngvi.gateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.api.SecretSpec;

/** Reads secrets from the environment; {@code <ENV>_FILE} wins over the inline value. Values are trimmed. */
public final class SecretStore {

    private final Map<String, String> env;
    private final MaskedLog log;

    public SecretStore(Map<String, String> env, MaskedLog log) {
        this.env = env;
        this.log = log;
    }

    /** The secret, or null when not configured. */
    public String read(SecretSpec spec) {
        if (spec == null || spec.env() == null) return null;
        String file = env.get(spec.env() + "_FILE");
        if (file != null && !file.isBlank()) {
            try {
                String v = Files.readString(Path.of(file)).strip();
                if (!v.isEmpty()) {
                    log.registerSecret(v);
                    return v;
                }
            } catch (IOException e) {
                log.warn("cannot read " + spec.env() + " file " + file + ": " + e.getMessage());
            }
        }
        String v = env.get(spec.env());
        if (v == null || v.isBlank()) return null;
        v = v.strip();
        log.registerSecret(v);
        return v;
    }

    public String value(String name) {
        String v = env.get(name);
        return v == null || v.isBlank() ? null : v.strip();
    }
}
