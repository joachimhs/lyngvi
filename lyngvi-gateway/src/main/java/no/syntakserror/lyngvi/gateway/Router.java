package no.syntakserror.lyngvi.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import no.syntakserror.lyngvi.gateway.api.GatewayException;
import no.syntakserror.lyngvi.gateway.api.Request;
import no.syntakserror.lyngvi.gateway.api.Response;
import no.syntakserror.lyngvi.gateway.api.RouteTarget;

/** Request handling in the order of spec §8.4: health, token, instance, method, configured, route, allowlist, handler. */
public final class Router implements HttpHandler {

    private static final Set<String> SAFE = Set.of("GET", "HEAD");
    private static final Set<String> HOP = Set.of("connection", "keep-alive", "transfer-encoding", "content-length",
            "set-cookie", "www-authenticate", "proxy-authenticate", "upgrade", "te", "trailer");

    private static final JacksonJson JSON = new JacksonJson();
    private final Config config;
    private final Map<String, InstanceRuntime> byId;
    private final Map<String, InstanceRuntime> byHost;
    private final HealthReport health;
    private final MaskedLog log;

    public Router(Config config, List<InstanceRuntime> instances, HealthReport health, MaskedLog log) {
        this.config = config;
        this.health = health;
        this.log = log;
        this.byId = new LinkedHashMap<>();
        this.byHost = new LinkedHashMap<>();
        for (InstanceRuntime r : instances) {
            byId.put(r.instanceSpec().id(), r);
            if (r.instanceSpec().hostname() != null) byHost.put(r.instanceSpec().hostname().toLowerCase(Locale.ROOT), r);
            for (String a : r.instanceSpec().hostAliases()) byHost.put(a.toLowerCase(Locale.ROOT), r);
        }
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        Response resp;
        String route = "-";
        try {
            resp = dispatch(ex);
        } catch (GatewayException e) {
            resp = error(e.status(), e.getMessage(), e.extra());
        } catch (RuntimeException e) {
            log.error("unhandled error for " + ex.getRequestMethod() + " " + ex.getRequestURI().getPath(), e);
            resp = error(500, "internal gateway error", Map.of("detail", String.valueOf(e.getMessage())));
        }
        send(ex, resp);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info(ex.getRequestMethod() + " " + hostOf(ex) + " " + ex.getRequestURI().getPath() + " -> " + resp.status() + " " + ms + "ms" + (route.equals("-") ? "" : " " + route));
    }

    Response dispatch(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        if (path.equals("/healthz") || path.equals("/health")) {
            return Response.json(200, JSON.write(health.build(byId.values(), "1".equals(parseQuery(ex.getRequestURI()).getOrDefault("deep", List.of()).stream().findFirst().orElse("")))));
        }
        if (config.token() != null) {
            String h = ex.getRequestHeaders().getFirst("Authorization");
            String x = ex.getRequestHeaders().getFirst("X-Gateway-Token");
            boolean ok = (h != null && h.equals("Bearer " + config.token())) || (x != null && x.equals(config.token()));
            if (!ok) throw new GatewayException(401, "gateway token required", Map.of("hint", "send Authorization: Bearer <GATEWAY_TOKEN> or X-Gateway-Token"));
        }
        String host = hostOf(ex);
        InstanceRuntime inst = byHost.get(host);
        String localPath = path;
        if (inst == null) {
            int slash = path.indexOf('/', 1);
            String first = slash < 0 ? path.substring(1) : path.substring(1, slash);
            if (byId.containsKey(first)) {
                inst = byId.get(first);
                localPath = slash < 0 ? "/" : path.substring(slash);
            }
        }
        if (inst == null) {
            throw new GatewayException(404, "unknown gateway host", Map.of("host", host, "adapters", new ArrayList<>(byId.keySet()),
                    "hint", "use one of the configured hostnames or prefix the path with /<instance-id>/"));
        }
        boolean writeMethod = !SAFE.contains(method);
        if (writeMethod && !inst.writeAllowed()) {
            throw new GatewayException(405, "method not allowed", Map.of("detail", "this gateway instance is read-only", "adapter", inst.instanceSpec().id()));
        }
        if (!inst.configured()) {
            throw new GatewayException(503, "gateway not configured: missing " + inst.missing(),
                    Map.of("adapter", inst.instanceSpec().id(), "hint", "on the host: lyngvi gateway secret set " + inst.instanceSpec().id() + " --url <base-url> --token-stdin, then lyngvi gateway restart"));
        }
        Map<String, List<String>> query = parseQuery(ex.getRequestURI());
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> { if (!v.isEmpty()) headers.put(k.toLowerCase(Locale.ROOT), v.getFirst()); });
        byte[] body;
        try {
            body = ex.getRequestBody().readAllBytes();
        } catch (IOException e) {
            throw new GatewayException(400, "cannot read request body");
        }
        String routeMethod = method.equals("HEAD") ? "GET" : method;
        boolean pathMatched = false;
        for (InstanceRuntime.CompiledRoute cr : inst.routes()) {
            Map<String, String> params = cr.match(localPath);
            if (params == null) continue;
            pathMatched = true;
            if (!cr.route().method().equals(routeMethod)) continue;
            Request req = new Request(method, localPath, params, query, headers, body);
            String key = cr.route().allowlistKey() == null ? null : cr.route().allowlistKey().apply(req);
            if (key != null && !inst.allowlist().allows(key)) {
                throw new GatewayException(403, "not on allowlist", Map.of("adapter", inst.instanceSpec().id(),
                        inst.instanceSpec().allowlist().label(), key,
                        "hint", "add it to the instance allowlist on the host (lyngvi gateway allow " + inst.instanceSpec().id() + " add <entry>)"));
            }
            return invoke(cr, req, inst);
        }
        if (pathMatched) {
            throw new GatewayException(405, "method not allowed", Map.of("adapter", inst.instanceSpec().id(), "path", localPath));
        }
        if (routeMethod.equals("GET")) {
            RouteTarget target = inst.plugin().parseBrowserUrl(URI.create(localPath + rawQuery(ex.getRequestURI()))).orElse(null);
            if (target != null) {
                Map<String, List<String>> q = new LinkedHashMap<>();
                target.query().forEach((k, v) -> q.put(k, List.of(v)));
                Request req = new Request("GET", target.path(), Map.of(), q, headers, body);
                for (InstanceRuntime.CompiledRoute cr : inst.routes()) {
                    Map<String, String> params = cr.match(target.path());
                    if (params == null || !cr.route().method().equals("GET")) continue;
                    Request r2 = new Request("GET", target.path(), params, q, headers, body);
                    String key = cr.route().allowlistKey() == null ? null : cr.route().allowlistKey().apply(r2);
                    if (key != null && !inst.allowlist().allows(key)) {
                        throw new GatewayException(403, "not on allowlist", Map.of("adapter", inst.instanceSpec().id(), inst.instanceSpec().allowlist().label(), key));
                    }
                    return invoke(cr, r2, inst);
                }
                throw new GatewayException(404, "not found", Map.of("path", req.path()));
            }
        }
        List<String> routes = inst.routes().stream().map(cr -> cr.route().method() + " " + cr.route().pathPattern()).toList();
        throw new GatewayException(404, "not found", Map.of("version", config.version(), "adapter", inst.instanceSpec().id(),
                "routes", routes, "hint", "GET /healthz lists every instance and its routes"));
    }

    private static Response invoke(InstanceRuntime.CompiledRoute cr, Request req, InstanceRuntime inst) {
        try {
            return cr.route().handler().handle(req, inst);
        } catch (GatewayException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new GatewayException(504, "upstream timeout", Map.of("adapter", inst.instanceSpec().id()));
        } catch (IOException e) {
            throw new GatewayException(502, "upstream request failed", Map.of("adapter", inst.instanceSpec().id(), "detail", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            throw new GatewayException(500, "plugin error", Map.of("adapter", inst.instanceSpec().id(), "detail", String.valueOf(e.getMessage())));
        }
    }

    private static String rawQuery(URI uri) {
        return uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
    }

    static String hostOf(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Host");
        if (h == null) return "";
        int c = h.lastIndexOf(':');
        if (c > 0 && !h.endsWith("]")) h = h.substring(0, c);
        return h.toLowerCase(Locale.ROOT);
    }

    static Map<String, List<String>> parseQuery(URI uri) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty()) return out;
        for (String part : q.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return out;
    }

    static Response error(int status, String message, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        if (extra != null) body.putAll(extra);
        return Response.json(status, JSON.write(body));
    }

    private void send(HttpExchange ex, Response resp) throws IOException {
        byte[] body = resp.body() == null ? new byte[0] : resp.body();
        resp.headers().forEach((k, v) -> { if (!HOP.contains(k.toLowerCase(Locale.ROOT)) && !k.startsWith(":")) ex.getResponseHeaders().set(k, v); });
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        boolean head = ex.getRequestMethod().equalsIgnoreCase("HEAD");
        ex.sendResponseHeaders(resp.status(), head ? -1 : (body.length == 0 ? -1 : body.length));
        if (!head && body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } else {
            ex.close();
        }
    }
}
