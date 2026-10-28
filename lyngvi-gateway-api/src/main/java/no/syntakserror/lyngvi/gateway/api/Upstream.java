package no.syntakserror.lyngvi.gateway.api;

import java.util.Map;

/**
 * The instance's upstream, origin-locked to its base URL and rest base. The runtime injects the
 * secret; paths that try to escape ({@code ..}, absolute URLs, other hosts) are rejected.
 */
public interface Upstream {

    UpstreamResponse get(String path, Map<String, String> params);

    /** Only for methods the instance may use (GET always; others need the {@code write} capability). */
    UpstreamResponse request(String method, String path, Map<String, String> params, byte[] body, Map<String, String> headers);

    /** A HEAD-like probe without following redirects; returns the {@code Location} header, or null. Used for short links. */
    String getRawLocation(String path);

    String baseUrl();

    String restBase();
}
