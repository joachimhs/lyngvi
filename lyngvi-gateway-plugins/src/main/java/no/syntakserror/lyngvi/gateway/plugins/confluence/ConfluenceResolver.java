package no.syntakserror.lyngvi.gateway.plugins.confluence;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.syntakserror.lyngvi.gateway.api.AllowlistResolver;
import no.syntakserror.lyngvi.gateway.api.Upstream;
import no.syntakserror.lyngvi.gateway.plugins.common.Urls;

/**
 * Allowlist entries: a page id ({@code 42}), a space ({@code space:OPS} or {@code OPS}), or a browser URL
 * ({@code …/pages/viewpage.action?pageId=42}, {@code …/wiki/spaces/OPS/pages/42/Title}, {@code …/display/OPS/Title}).
 * Keys are {@code page:<id>} and {@code space:<KEY>}. A {@code display} URL for a page title is resolved through the upstream.
 */
final class ConfluenceResolver implements AllowlistResolver {

    private static final Pattern SPACES_URL = Pattern.compile("/spaces/([A-Za-z0-9_~-]+)/pages/(\\d+)");
    private static final Pattern DISPLAY_URL = Pattern.compile("/display/([A-Za-z0-9_~-]+)/([^/]+)$");

    static String pageKey(String id) {
        return "page:" + id;
    }

    static String spaceKey(String key) {
        return "space:" + key.toUpperCase(Locale.ROOT);
    }

    @Override
    public String parse(String entry) {
        String e = entry.strip();
        if (e.matches("\\d+")) return pageKey(e);
        if (e.toLowerCase(Locale.ROOT).startsWith("page:")) return pageKey(e.substring(5));
        if (e.toLowerCase(Locale.ROOT).startsWith("space:")) return spaceKey(e.substring(6));
        if (e.matches("[A-Za-z][A-Za-z0-9_~-]*")) return spaceKey(e);
        Urls.Parsed p = Urls.parse(e);
        if (p == null) return null;
        String id = p.query().get("pageId");
        if (id != null && id.matches("\\d+")) return pageKey(id);
        Matcher m = SPACES_URL.matcher(p.path());
        if (m.find()) return pageKey(m.group(2));
        Matcher d = DISPLAY_URL.matcher(p.path());
        if (d.find() && d.group(2).isEmpty()) return spaceKey(d.group(1));
        return null;
    }

    @Override
    public List<String> resolve(String entry, Upstream upstream) {
        Urls.Parsed p = Urls.parse(entry);
        if (p == null || upstream == null) return List.of();
        Matcher d = DISPLAY_URL.matcher(p.path());
        if (!d.find()) return List.of();
        String space = d.group(1);
        String title = java.net.URLDecoder.decode(d.group(2), java.nio.charset.StandardCharsets.UTF_8).replace('+', ' ');
        var r = upstream.get("/content", java.util.Map.of("spaceKey", space, "title", title, "limit", "1"));
        if (r.status() != 200) return List.of();
        String body = new String(r.body(), java.nio.charset.StandardCharsets.UTF_8);
        Matcher id = Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"").matcher(body);
        return id.find() ? List.of(pageKey(id.group(1))) : List.of();
    }

    @Override
    public boolean matches(String key, Set<String> keys) {
        return keys.contains(key);
    }
}
