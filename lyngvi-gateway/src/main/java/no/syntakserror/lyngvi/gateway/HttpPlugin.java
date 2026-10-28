package no.syntakserror.lyngvi.gateway;

import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.api.AllowlistResolver;
import no.syntakserror.lyngvi.gateway.api.GatewayException;
import no.syntakserror.lyngvi.gateway.api.GatewayPlugin;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Request;
import no.syntakserror.lyngvi.gateway.api.Response;
import no.syntakserror.lyngvi.gateway.api.Route;
import no.syntakserror.lyngvi.gateway.api.UpstreamResponse;

/**
 * The built-in declarative plugin (spec §8.5): one REST API behind a static secret and a known set of paths.
 * Configuration lives in {@code instance.config}; the allowlist mode in {@code instance.allowlist.mode}.
 */
public final class HttpPlugin implements GatewayPlugin {

    public static final String TYPE = "http";

    /** {@code instance.config} for the http plugin. */
    public record HttpConfig(String restBase, List<String> methods, List<String> pathAllow, List<String> queryAllow,
                             List<String> forwardRequestHeaders, Rewrite rewrite, Long maxResponseBytes, Long timeoutMs) {
        public record Rewrite(String from, String to) {}

        public List<String> methodsOrDefault() { return methods == null || methods.isEmpty() ? List.of("GET") : methods; }
        public List<String> forwardOrDefault() { return forwardRequestHeaders == null ? List.of("accept") : forwardRequestHeaders; }
        public String restBaseOrDefault() { return restBase == null ? "" : restBase; }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String label() {
        return "Generic HTTP";
    }

    @Override
    public Class<?> configType() {
        return HttpConfig.class;
    }

    @Override
    public AllowlistResolver allowlist() {
        return AllowlistResolver.pathPrefix();
    }

    @Override
    public AllowlistResolver allowlistFor(Instance instance) {
        String mode = instance.allowlist() == null || instance.allowlist().mode() == null ? "path-prefix" : instance.allowlist().mode();
        if (mode.equals("path-exact") || mode.startsWith("query:")) return AllowlistResolver.exact();
        if (mode.equals("path-regex")) return AllowlistResolver.regex();
        return AllowlistResolver.pathPrefix();
    }

    /** {@code none} means only {@code pathAllow} guards the instance. */
    public static boolean allowlistEnforced(Instance instance) {
        return instance.allowlist() == null || !"none".equals(instance.allowlist().mode());
    }

    static String keyOf(Instance instance, Request req) {
        String mode = instance.allowlist() == null || instance.allowlist().mode() == null ? "path-prefix" : instance.allowlist().mode();
        if (mode.startsWith("query:")) return req.query(mode.substring(6));
        return req.path();
    }

    @Override
    public List<Route> routes() {
        return List.of(
                Route.get("/**", req -> null, this::forward),
                Route.write("POST", "/**", req -> null, this::forward),
                Route.write("PUT", "/**", req -> null, this::forward),
                Route.write("PATCH", "/**", req -> null, this::forward),
                Route.write("DELETE", "/**", req -> null, this::forward));
    }

    private Response forward(Request req, no.syntakserror.lyngvi.gateway.api.PluginContext ctx) {
        Instance instance = ctx.instance();
        HttpConfig cfg = ctx.config(HttpConfig.class);
        if (!cfg.methodsOrDefault().contains(req.method())) {
            throw new GatewayException(405, "method not allowed", Map.of("detail", "this gateway instance is read-only"));
        }
        String path = req.path();
        if (cfg.pathAllow() == null || cfg.pathAllow().stream().noneMatch(path::matches)) {
            throw new GatewayException(403, "path not allowed for this instance", Map.of("path", path));
        }
        String key = keyOf(instance, req);
        if (!ctx.allowlist().allows(key)) {
            throw new GatewayException(403, "not on allowlist", Map.of("adapter", instance.id(),
                    instance.allowlist() == null ? "entry" : instance.allowlist().label(), String.valueOf(key),
                    "hint", "add it to the instance allowlist on the host (lyngvi gateway allow " + instance.id() + " add <entry>)"));
        }
        if (cfg.rewrite() != null && cfg.rewrite().from() != null) {
            path = path.replaceAll(cfg.rewrite().from(), cfg.rewrite().to() == null ? "" : cfg.rewrite().to());
        }
        Map<String, String> params = new java.util.LinkedHashMap<>();
        req.query().forEach((k, v) -> {
            if ((cfg.queryAllow() == null || cfg.queryAllow().contains(k)) && !v.isEmpty()) params.put(k, v.getFirst());
        });
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (String h : cfg.forwardOrDefault()) {
            String v = req.headers().get(h.toLowerCase());
            if (v != null) headers.put(h, v);
        }
        UpstreamResponse up = req.method().equals("GET")
                ? ctx.upstream().get(path, params)
                : ctx.upstream().request(req.method(), path, params, req.body(), headers);
        return Response.passthrough(up);
    }
}
