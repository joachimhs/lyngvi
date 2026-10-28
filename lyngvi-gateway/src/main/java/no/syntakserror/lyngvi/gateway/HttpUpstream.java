package no.syntakserror.lyngvi.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import no.syntakserror.lyngvi.gateway.api.GatewayException;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Upstream;
import no.syntakserror.lyngvi.gateway.api.UpstreamResponse;

/**
 * The real upstream (spec §8.4 point 3): every request goes to {@code baseUrl + restBase + path},
 * the secret is injected per scheme, and paths cannot escape the rest base.
 */
public final class HttpUpstream implements Upstream {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD");
    private final HttpClient client;
    private final String origin;
    private final String restBase;
    private final String secret;
    private final String scheme;
    private final Instance instance;
    private final Config config;
    private final long maxResponseBytes;
    private final long timeoutMs;
    private final Set<String> allowedMethods;

    public HttpUpstream(HttpClient client, Instance instance, String baseUrl, String restBase, String secret, Config config,
                        long maxResponseBytes, long timeoutMs, Set<String> allowedMethods) {
        this.client = client;
        this.instance = instance;
        this.origin = originOf(baseUrl);
        this.restBase = restBase == null ? "" : restBase;
        this.secret = secret;
        this.scheme = instance.secret() == null || instance.secret().scheme() == null ? "bearer" : instance.secret().scheme();
        this.config = config;
        this.maxResponseBytes = maxResponseBytes;
        this.timeoutMs = timeoutMs;
        this.allowedMethods = allowedMethods;
    }

    static String originOf(String baseUrl) {
        URI u = URI.create(baseUrl);
        if (u.getScheme() == null || u.getHost() == null) throw new IllegalArgumentException("Base URL must be absolute: " + baseUrl);
        String port = u.getPort() == -1 ? "" : ":" + u.getPort();
        String path = u.getPath() == null ? "" : u.getPath().replaceAll("/+$", "");
        return u.getScheme() + "://" + u.getHost() + port + path;
    }

    @Override
    public String baseUrl() {
        return origin;
    }

    @Override
    public String restBase() {
        return restBase;
    }

    /** {@code origin + restBase + path (+ query)}; rejects anything that would leave the rest base. */
    URI target(String path, Map<String, String> params) {
        if (path == null) path = "";
        if (path.contains("://") || path.startsWith("//")) {
            throw new GatewayException(400, "upstream path must be relative", Map.of("path", path));
        }
        String normalized = URI.create("http://x" + (path.startsWith("/") ? path : "/" + path)).normalize().getRawPath();
        if (normalized.contains("/../") || normalized.startsWith("/..") || (path.contains("..") && !normalized.startsWith("/"))) {
            throw new GatewayException(400, "upstream path may not escape the base path", Map.of("path", path));
        }
        StringBuilder sb = new StringBuilder(origin).append(restBase).append(normalized);
        if (params != null && !params.isEmpty()) {
            sb.append(normalized.contains("?") ? '&' : '?');
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() == null) continue;
                if (!first) sb.append('&');
                first = false;
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
        }
        if (scheme.startsWith("query:") && secret != null) {
            sb.append(sb.indexOf("?") >= 0 ? '&' : '?').append(URLEncoder.encode(scheme.substring(6), StandardCharsets.UTF_8))
              .append('=').append(URLEncoder.encode(secret, StandardCharsets.UTF_8));
        }
        URI target = URI.create(sb.toString());
        if (!originOf(target.toString()).startsWith(origin)) {
            throw new GatewayException(400, "upstream request would leave the instance origin", Map.of("path", path));
        }
        return target;
    }

    @Override
    public UpstreamResponse get(String path, Map<String, String> params) {
        return request("GET", path, params, null, Map.of());
    }

    @Override
    public UpstreamResponse request(String method, String path, Map<String, String> params, byte[] body, Map<String, String> headers) {
        String m = method.toUpperCase(Locale.ROOT);
        if (!SAFE_METHODS.contains(m) && !allowedMethods.contains(m)) {
            throw new GatewayException(405, "method " + m + " is not allowed for this instance", Map.of("method", m));
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(target(path, params)).timeout(Duration.ofMillis(timeoutMs));
        b.header("accept", "application/json");
        b.header("user-agent", "lyngvi-gateway/" + instance.id());
        if (headers != null) headers.forEach((k, v) -> { if (!k.equalsIgnoreCase("authorization") && !k.equalsIgnoreCase("host")) b.header(k, v); });
        inject(b);
        b.method(m, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<byte[]> r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] data = r.body();
            if (data.length > maxResponseBytes) {
                throw new GatewayException(502, "upstream response too large", Map.of("bytes", data.length, "limit", maxResponseBytes));
            }
            Map<String, String> h = new LinkedHashMap<>();
            // HTTP/2 pseudo-headers (":status") must never reach an HTTP/1.1 response; Squid rejects them as malformed.
            r.headers().map().forEach((k, v) -> { if (!v.isEmpty() && !k.startsWith(":")) h.put(k.toLowerCase(Locale.ROOT), v.getFirst()); });
            return new UpstreamResponse(r.statusCode(), h, data);
        } catch (HttpTimeoutException e) {
            throw new GatewayException(504, "upstream timeout", Map.of("detail", timeoutMs + " ms"));
        } catch (IOException e) {
            throw new GatewayException(502, "upstream request failed", Map.of("detail", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), "target", origin));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(502, "upstream request interrupted");
        }
    }

    private void inject(HttpRequest.Builder b) {
        if (secret == null) return;
        if (scheme.equals("bearer")) {
            b.header("authorization", "Bearer " + secret);
        } else if (scheme.equals("basic")) {
            b.header("authorization", "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
        } else if (scheme.startsWith("header:")) {
            b.header(scheme.substring(7), secret);
        }
        // query:<name> is appended in target()
    }

    @Override
    public String getRawLocation(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(origin + (path.startsWith("/") ? path : "/" + path)))
                .timeout(Duration.ofMillis(timeoutMs)).method("GET", HttpRequest.BodyPublishers.noBody());
        inject(b);
        try {
            HttpResponse<Void> r = client.send(b.build(), HttpResponse.BodyHandlers.discarding());
            return r.headers().firstValue("location").orElse(null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    Config config() {
        return config;
    }
}
