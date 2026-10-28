package no.syntakserror.lyngvi.gateway.plugins.jira;

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

/**
 * Jira (REST API v2 for Server/Data Center; {@code apiVersion: 3} for Cloud, where descriptions are ADF and
 * flattened to text). Routes: {@code GET /issue/{key}}, {@code GET /issue/{key}/comments},
 * {@code GET /project/{key}/issues?jql=}. Issues are allowed by key or by project.
 */
public final class JiraPlugin implements GatewayPlugin {

    /** {@code instance.config}: {@code apiVersion} 2 (default) or 3. */
    public record JiraConfig(Integer apiVersion) {}

    private static final Pattern BROWSE = Pattern.compile("/browse/([A-Za-z][A-Za-z0-9_]*-\\d+)");

    @Override
    public String type() {
        return "jira";
    }

    @Override
    public String label() {
        return "Jira";
    }

    @Override
    public String allowlistLabel() {
        return "issues/projects";
    }

    @Override
    public Class<?> configType() {
        return JiraConfig.class;
    }

    @Override
    public String restBase(Instance instance) {
        Object v = instance.config() == null ? null : instance.config().get("apiVersion");
        return "/rest/api/" + (v instanceof Number n && n.intValue() == 3 ? "3" : "2");
    }

    @Override
    public AllowlistResolver allowlist() {
        return new JiraResolver();
    }

    @Override
    public List<Route> routes() {
        return List.of(
                Route.get("/issue/{key}", r -> JiraResolver.issueKey(r.param("key")), this::issue),
                Route.get("/issue/{key}/comments", r -> JiraResolver.issueKey(r.param("key")), this::comments),
                Route.get("/project/{key}/issues", r -> JiraResolver.projectKey(r.param("key")), this::projectIssues));
    }

    @Override
    public Optional<RouteTarget> parseBrowserUrl(URI url) {
        Matcher m = BROWSE.matcher(url.getPath() == null ? "" : url.getPath());
        return m.find() ? Optional.of(new RouteTarget("/issue/" + m.group(1).toUpperCase(Locale.ROOT), Map.of())) : Optional.empty();
    }

    @Override
    public HealthProbe health() {
        return ctx -> {
            UpstreamResponse r = ctx.upstream().get("/myself", Map.of());
            String user = r.status() == 200 ? JsonPath.str(ctx.json().readObject(text(r)), "displayName") : null;
            return new Health(r.status() == 200, r.status(), r.status() == 200 ? null : "myself returned " + r.status(), user);
        };
    }

    private static String text(UpstreamResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    private static String validKey(String key) {
        String k = key.toUpperCase(Locale.ROOT);
        if (!JiraResolver.ISSUE.matcher(k).matches()) throw new GatewayException(400, "not an issue key", Map.of("key", key));
        return k;
    }

    private String description(Object value, PluginContext ctx) {
        if (value == null) return "";
        if (value instanceof String s) return ctx.text().wikiMarkupToText(s);
        StringBuilder sb = new StringBuilder();
        adfText(value, sb);
        return sb.toString().strip();
    }

    /** Flattens an Atlassian Document Format tree to text. */
    private static void adfText(Object node, StringBuilder sb) {
        if (node instanceof Map<?, ?> m) {
            Object type = m.get("type");
            if ("text".equals(type)) sb.append(m.get("text"));
            if ("hardBreak".equals(type)) sb.append('\n');
            for (Object child : JsonPath.list(m, "content")) adfText(child, sb);
            if ("paragraph".equals(type) || "heading".equals(type) || "listItem".equals(type)) sb.append('\n');
        }
    }

    private Response issue(Request req, PluginContext ctx) {
        String key = validKey(req.param("key"));
        UpstreamResponse r = ctx.upstream().get("/issue/" + key, Map.of("fields", "summary,status,issuetype,priority,assignee,reporter,created,updated,labels,description,project"));
        if (r.status() == 404) throw new GatewayException(404, "issue not found", Map.of("key", key));
        if (r.status() != 200) throw new GatewayException(502, "upstream returned " + r.status(), Map.of("key", key));
        Map<String, Object> issue = ctx.json().readObject(text(r));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", JsonPath.str(issue, "key"));
        out.put("summary", JsonPath.str(issue, "fields", "summary"));
        out.put("status", JsonPath.str(issue, "fields", "status", "name"));
        out.put("type", JsonPath.str(issue, "fields", "issuetype", "name"));
        out.put("priority", JsonPath.str(issue, "fields", "priority", "name"));
        out.put("assignee", JsonPath.str(issue, "fields", "assignee", "displayName"));
        out.put("reporter", JsonPath.str(issue, "fields", "reporter", "displayName"));
        out.put("project", JsonPath.str(issue, "fields", "project", "key"));
        out.put("labels", JsonPath.list(issue, "fields", "labels"));
        out.put("created", JsonPath.str(issue, "fields", "created"));
        out.put("updated", JsonPath.str(issue, "fields", "updated"));
        out.put("description", description(JsonPath.get(issue, "fields", "description"), ctx));
        out.put("url", ctx.upstream().baseUrl() + "/browse/" + key);
        return Response.json(200, ctx.json().write(out));
    }

    private Response comments(Request req, PluginContext ctx) {
        String key = validKey(req.param("key"));
        UpstreamResponse r = ctx.upstream().get("/issue/" + key + "/comment", Map.of("maxResults", String.valueOf(ctx.limit(req)), "orderBy", "-created"));
        if (r.status() == 404) throw new GatewayException(404, "issue not found", Map.of("key", key));
        if (r.status() != 200) throw new GatewayException(502, "upstream returned " + r.status(), Map.of("key", key));
        Map<String, Object> body = ctx.json().readObject(text(r));
        List<Map<String, Object>> comments = new ArrayList<>();
        for (Object o : JsonPath.list(body, "comments")) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", JsonPath.str(o, "id"));
            c.put("author", JsonPath.str(o, "author", "displayName"));
            c.put("created", JsonPath.str(o, "created"));
            c.put("text", description(JsonPath.get(o, "body"), ctx));
            comments.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("total", JsonPath.integer(body, "total"));
        out.put("comments", comments);
        return Response.json(200, ctx.json().write(out));
    }

    private Response projectIssues(Request req, PluginContext ctx) {
        String project = req.param("key").toUpperCase(Locale.ROOT);
        if (!project.matches("[A-Z][A-Z0-9_]*")) throw new GatewayException(400, "not a project key", Map.of("key", project));
        String extra = req.query("jql");
        String jql = "project = \"" + project + "\"" + (extra == null || extra.isBlank() ? "" : " AND (" + extra.strip() + ")");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("jql", jql);
        params.put("maxResults", String.valueOf(ctx.limit(req)));
        params.put("fields", "summary,status,issuetype,assignee,updated");
        UpstreamResponse r = ctx.upstream().get("/search", params);
        if (r.status() == 400) throw new GatewayException(400, "upstream rejected the JQL", Map.of("jql", jql, "detail", text(r)));
        if (r.status() != 200) throw new GatewayException(502, "upstream returned " + r.status(), Map.of("jql", jql));
        Map<String, Object> body = ctx.json().readObject(text(r));
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Object o : JsonPath.list(body, "issues")) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("key", JsonPath.str(o, "key"));
            i.put("summary", JsonPath.str(o, "fields", "summary"));
            i.put("status", JsonPath.str(o, "fields", "status", "name"));
            i.put("type", JsonPath.str(o, "fields", "issuetype", "name"));
            i.put("assignee", JsonPath.str(o, "fields", "assignee", "displayName"));
            i.put("updated", JsonPath.str(o, "fields", "updated"));
            issues.add(i);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jql", jql);
        out.put("total", JsonPath.integer(body, "total"));
        out.put("issues", issues);
        return Response.json(200, ctx.json().write(out));
    }
}
