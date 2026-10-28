package no.syntakserror.lyngvi.gateway.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** An error with an HTTP status and a JSON body {@code {"error": message, ...extra}}. */
public class GatewayException extends RuntimeException {

    private final int status;
    private final Map<String, Object> extra;

    public GatewayException(int status, String message) {
        this(status, message, Map.of());
    }

    public GatewayException(int status, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.extra = new LinkedHashMap<>(extra);
    }

    public int status() {
        return status;
    }

    public Map<String, Object> extra() {
        return extra;
    }
}
