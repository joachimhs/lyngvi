package no.syntakserror.lyngvi.gateway.plugins.confluence;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.syntakserror.lyngvi.gateway.api.AllowlistResolver;
import no.syntakserror.lyngvi.gateway.api.GatewayException;
import no.syntakserror.lyngvi.gateway.api.GatewayPlugin;
import no.syntakserror.lyngvi.gateway.api.Health;
import no.syntakserror.lyngvi.gateway.api.HealthProbe;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.PluginContext;
import no.syntakserror.lyngvi.gateway.api.Request;
import no.syntakserror.lyngvi.gateway.api.Response;
import no.syntakserror.lyngvi.gateway.api.Route;
import no.syntakserror.lyngvi.gateway.api.RouteTarget;
import no.syntakserror.lyngvi.gateway.api.UpstreamResponse;
import no.syntakserror.lyngvi.gateway.plugins.common.JsonPath;
import no.syntakserror.lyngvi.gateway.plugins.common.Urls;

/**
 * Confluence (Server/Data Center REST API v1, also served by Cloud under {@code /wiki}).
 * Routes: {@code GET /page/{id}}, {@code GET /page/{id}/children}, {@code GET /search?q=&space=}.
 * Pages are allowed by id or by space; page bodies are converted to text.
 */
public final class ConfluencePlugin implements GatewayPlugin {

    /** {@code instance.config}: {@code cloud} switches the REST base to {@code /wiki/rest/api}. */
    public record ConfluenceConfig(Boolean cloud) {}

    private static final Pattern SPACES_URL = Pattern.compile("/spaces/[A-Za-z0-9_~-]+/pages/(\\d+)");

    @Override
    public String type() {
        return "confluence";
    }

    @Override
    public String label() {
        return "Confluence";
    }

    @Override
    public String allowlistLabel() {
        return "pages/spaces";
    }

    @Override
    public Class<?> configType() {
        return ConfluenceConfig.class;
    }

    @Override
    public String restBase(Instance instance) {
        Object cloud = instance.config() == null ? null : instance.config().get("cloud");
        return Boolean.TRUE.equals(cloud) ? "/wiki/rest/api" : "/rest/api";
    }

    @Override
    public AllowlistResolver allowlist() {
        return new ConfluenceResolver();
    }

    @Override
    public List<Route> routes() {
        return List.of(
                Route.get("/page/{id}", this::page),
                Route.get("/page/{id}/children", this::children),
                Route.get("/search", r -> spaceOf(r), this::search));
    }

    private static String spaceOf(Request r) {
        String s = r.query("space");
        return s == null || s.isBlank() ? null : ConfluenceResolver.spaceKey(s);
    }

    @Override
    public Optional<RouteTarget> parseBrowserUrl(URI url) {
        Urls.Parsed p = Urls.parse(url.toString());
        if (p == null) return Optional.empty();
        String id = p.query().get("pageId");
        if (id != null && id.matches("\\d+")) return Optional.of(new RouteTarget("/page/" + id, Map.of()));
        Matcher m = SPACES_URL.matcher(p.path());
        if (m.find()) return Optional.of(new RouteTarget("/page/" + m.group(1), Map.of()));
        return Optional.empty();
    }

    @Override
    public HealthProbe health() {
        return ctx -> {
            UpstreamResponse r = ctx.upstream().get("/user/current", Map.of());
            String user = r.status() == 200 ? JsonPath.str(ctx.json().readObject(text(r)), "displayName") : null;
            return new Health(r.status() == 200, r.status(), r.status() == 200 ? null : "user/current returned " + r.status(), user);
        };
    }

    private static String text(UpstreamResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    private Map<String, Object> fetchPage(String id, PluginContext ctx) {
        if (!id.matches("\\d+")) throw new GatewayException(400, "page id must be numeric", Map.of("page", id));
        boolean byId = ctx.allowlist().allows(ConfluenceResolver.pageKey(id));
        boolean anySpace = !ctx.allowlist().enforced() || ctx.allowlist().describe().resolved().stream().anyMatch(k -> k.startsWith("space:"));
        if (!byId && !anySpace) {
            // No space entry could cover it: deny without touching the upstream (spec G7).
            throw new GatewayException(403, "page not on allowlist", Map.of("page", id,
                    "hint", "allow it on the host with: lyngvi gateway allow " + ctx.instance().id() + " add " + id));
        }
        UpstreamResponse r = ctx.upstream().get("/content/" + id, Map.of("expand", "body.storage,version,space"));
        if (r.status() == 404) throw new GatewayException(404, "page not found", Map.of("page", id));
        if (r.status() != 200) throw new GatewayException(502, "upstream returned " + r.status(), Map.of("page", id));
        Map<String, Object> page = ctx.json().readObject(text(r));
        String space = JsonPath.str(page, "space", "key");
        boolean allowed = ctx.allowlist().allows(ConfluenceResolver.pageKey(id))
                || (space != null && ctx.allowlist().allows(ConfluenceResolver.spaceKey(space)));
        if (!allowed) {
            throw new GatewayException(403, "page not on allowlist", Map.of("page", id, "space", space == null ? "" : ConfluenceResolver.spaceKey(space),
                    "hint", "allow it on the host with: lyngvi gateway allow " + ctx.instance().id() + " add " + id + " (or " + (space == null ? "space:KEY" : ConfluenceResolver.spaceKey(space)) + ")"));
        }
        return page;
    }

    private Response page(Request req, PluginContext ctx) {
        Map<String, Object> page = fetchPage(req.param("id"), ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", JsonPath.str(page, "id"));
        out.put("title", JsonPath.str(page, "title"));
        out.put("space", JsonPath.str(page, "space", "key"));
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("number", JsonPath.integer(page, "version", "number"));
        version.put("when", JsonPath.str(page, "version", "when"));
        version.put("by", JsonPath.str(page, "version", "by", "displayName"));
        out.put("version", version);
        out.put("text", ctx.text().htmlToText(JsonPath.str(page, "body", "storage", "value")));
        out.put("url", webUrl(ctx, JsonPath.str(page, "_links", "webui")));
        return Response.json(200, ctx.json().write(out));
    }

    private static String webUrl(PluginContext ctx, String webui) {
        if (webui == null) return null;
        String base = ctx.upstream().baseUrl();
        if (ctx.upstream().restBase().startsWith("/wiki")) base = base + "/wiki";
        return base + webui;
    }

    private Response children(Request req, PluginContext ctx) {
        String id = req.param("id");
        fetchPage(id, ctx);
        UpstreamResponse r = ctx.upstream().get("/content/" + id + "/child/page", Map.of("limit", String.valueOf(ctx.limit(req))));
        if (r.status() != 200) throw new GatewayException(502, "upstream returned " + r.status(), Map.of("page", id));
        Map<String, Object> body = ctx.json().readObject(text(r));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object o : JsonPath.list(body, "results")) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", JsonPath.str(o, "id"));
            c.put("title", JsonPath.str(o, "title"));
            c.put("url", webUrl(ctx, JsonPath.str(o, "_links", "webui")));
            results.add(c);
        }
        return Response.json(200, ctx.json().write(Map.of("page", id, "children", results)));
    }

    private Response search(Request req, PluginContext ctx) {
        String q = req.query("q");
        String space = req.query("space");
        if (q == null || q.isBlank() || space == null || space.isBlank()) {
            throw new GatewayException(400, "search needs q and space query parameters");
        }
        String cql = "space = \"" + space.toUpperCase(Locale.ROOT).replace("\"", "") + "\" AND type = page AND text ~ \"" + q.replace("\"", "\\\"") + "\"";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("cql", cql);
        params.put("limit", String.valueOf(ctx.limit(req)));
        UpstreamResponse r = ctx.upstream().get("/content/search", params);
        if (r.status() != 200) throw new GatewayException(502, "upstream returned " + r.status(), Map.of("cql", cql));
        Map<String, Object> body = ctx.json().readObject(text(r));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object o : JsonPath.list(body, "results")) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", JsonPath.str(o, "id"));
            c.put("title", JsonPath.str(o, "title"));
            c.put("space", JsonPath.str(o, "space", "key"));
            c.put("excerpt", ctx.text().stripTags(JsonPath.str(o, "excerpt")));
            results.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cql", cql);
        out.put("total", JsonPath.integer(body, "totalSize"));
        out.put("results", results);
        return Response.json(200, ctx.json().write(out));
    }
}
