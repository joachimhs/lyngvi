package no.syntakserror.lyngvi.core.doctor;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * One {@code doctor} finding.
 *
 * @param id      stable identifier, e.g. {@code podman} or {@code repo:<name>}
 * @param level   ok, warn or fail
 * @param message what was found (English, not localised)
 * @param hint    what to do about it; empty when nothing is needed
 */
public record Check(String id, Level level, String message, String hint) {

    public enum Level {
        OK, WARN, FAIL;

        @JsonValue
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static Check ok(String id, String message) {
        return new Check(id, Level.OK, message, "");
    }

    public static Check warn(String id, String message, String hint) {
        return new Check(id, Level.WARN, message, hint);
    }

    public static Check fail(String id, String message, String hint) {
        return new Check(id, Level.FAIL, message, hint);
    }
}
