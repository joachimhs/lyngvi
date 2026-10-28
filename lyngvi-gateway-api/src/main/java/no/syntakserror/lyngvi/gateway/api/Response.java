package no.syntakserror.lyngvi.gateway.api;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** The response a handler returns. The runtime adds {@code cache-control: no-store}. */
public record Response(int status, Map<String, String> headers, byte[] body) {

    public static Response json(int status, String json) {
        return new Response(status, Map.of("content-type", "application/json; charset=utf-8"), json.getBytes(StandardCharsets.UTF_8));
    }

    public static Response text(int status, String text) {
        return new Response(status, Map.of("content-type", "text/plain; charset=utf-8"), text.getBytes(StandardCharsets.UTF_8));
    }

    /** Pass an upstream response through unchanged (hop-by-hop and cookie headers are stripped by the runtime). */
    public static Response passthrough(UpstreamResponse upstream) {
        return new Response(upstream.status(), upstream.headers(), upstream.body());
    }
}
