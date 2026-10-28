package no.syntakserror.lyngvi.gateway.plugins.common;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** URL helpers shared by the reference plugins. */
public final class Urls {

    private Urls() {}

    /** Parses a full URL or a path with query into path + first query values. Returns null when unparsable. */
    public static Parsed parse(String s) {
        try {
            URI u = URI.create(s.strip());
            Map<String, String> q = new LinkedHashMap<>();
            if (u.getRawQuery() != null) {
                for (String part : u.getRawQuery().split("&")) {
                    int eq = part.indexOf('=');
                    String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
                    String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                    q.putIfAbsent(k, v);
                }
            }
            return new Parsed(u.getPath() == null ? "" : u.getPath(), q);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record Parsed(String path, Map<String, String> query) {}
}
