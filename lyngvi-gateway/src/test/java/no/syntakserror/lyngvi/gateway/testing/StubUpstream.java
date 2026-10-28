package no.syntakserror.lyngvi.gateway.testing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import no.syntakserror.lyngvi.gateway.api.GatewayException;
import no.syntakserror.lyngvi.gateway.api.Upstream;
import no.syntakserror.lyngvi.gateway.api.UpstreamResponse;

/** A scripted upstream: {@code path -> response}; records every call. */
public final class StubUpstream implements Upstream {

    public record Call(String method, String path, Map<String, String> params, byte[] body, Map<String, String> headers) {}

    private final String baseUrl;
    private final String restBase;
    private final Map<String, Function<Call, UpstreamResponse>> routes = new LinkedHashMap<>();
    private final Map<String, String> locations = new LinkedHashMap<>();
    public final List<Call> calls = new ArrayList<>();

    public StubUpstream(String baseUrl, String restBase) {
        this.baseUrl = baseUrl;
        this.restBase = restBase;
    }

    public StubUpstream json(String path, String body) {
        return on(path, c -> new UpstreamResponse(200, Map.of("content-type", "application/json"), body.getBytes(StandardCharsets.UTF_8)));
    }

    public StubUpstream status(String path, int status, String body) {
        return on(path, c -> new UpstreamResponse(status, Map.of("content-type", "application/json"), body.getBytes(StandardCharsets.UTF_8)));
    }

    public StubUpstream on(String path, Function<Call, UpstreamResponse> fn) {
        routes.put(path, fn);
        return this;
    }

    public StubUpstream location(String path, String location) {
        locations.put(path, location);
        return this;
    }

    @Override
    public UpstreamResponse get(String path, Map<String, String> params) {
        return request("GET", path, params, null, Map.of());
    }

    @Override
    public UpstreamResponse request(String method, String path, Map<String, String> params, byte[] body, Map<String, String> headers) {
        Call call = new Call(method, path, params == null ? Map.of() : params, body, headers == null ? Map.of() : headers);
        calls.add(call);
        Function<Call, UpstreamResponse> fn = routes.get(path);
        if (fn == null) {
            for (var e : routes.entrySet()) {
                if (e.getKey().endsWith("*") && path.startsWith(e.getKey().substring(0, e.getKey().length() - 1))) fn = e.getValue();
            }
        }
        if (fn == null) throw new GatewayException(502, "stub has no response for " + path);
        return fn.apply(call);
    }

    @Override
    public String getRawLocation(String path) {
        return locations.get(path);
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public String restBase() {
        return restBase;
    }
}
