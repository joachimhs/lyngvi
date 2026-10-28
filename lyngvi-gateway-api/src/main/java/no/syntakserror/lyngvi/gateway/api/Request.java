package no.syntakserror.lyngvi.gateway.api;

import java.util.List;
import java.util.Map;

/**
 * An inbound request as the plugin sees it: the path is relative to the instance (host or {@code /<id>} prefix stripped).
 *
 * @param params path parameters from the route pattern
 * @param query  query parameters; repeated keys keep all values
 */
public record Request(String method, String path, Map<String, String> params, Map<String, List<String>> query,
                      Map<String, String> headers, byte[] body) {

    public String param(String name) {
        return params.get(name);
    }

    /** First value of a query parameter, or null. */
    public String query(String name) {
        List<String> v = query.get(name);
        return v == null || v.isEmpty() ? null : v.getFirst();
    }

    public String query(String name, String fallback) {
        String v = query(name);
        return v == null ? fallback : v;
    }
}
