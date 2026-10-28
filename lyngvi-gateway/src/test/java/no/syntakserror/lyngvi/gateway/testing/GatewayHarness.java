package no.syntakserror.lyngvi.gateway.testing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.Config;
import no.syntakserror.lyngvi.gateway.Gateway;
import no.syntakserror.lyngvi.gateway.MaskedLog;
import no.syntakserror.lyngvi.gateway.ManifestReader;
import no.syntakserror.lyngvi.gateway.api.GatewayPlugin;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import no.syntakserror.lyngvi.gateway.api.Upstream;

/**
 * Starts a gateway on an ephemeral port with an in-memory manifest, environment, allowlist files and
 * stub upstreams. Plugin authors test against this (spec §8.9).
 */
public final class GatewayHarness implements AutoCloseable {

    private final Path dir;
    private final Map<String, String> env = new HashMap<>();
    private final Map<String, Upstream> upstreams = new HashMap<>();
    private final List<GatewayPlugin> plugins = new ArrayList<>();
    private final StringBuilderStream logs = new StringBuilderStream();
    private String manifestJson = "{\"schemaVersion\":1,\"instances\":[]}";
    private Gateway gateway;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    public GatewayHarness(Path dir) {
        this.dir = dir;
        env.put("PORT", "0");
        env.put("ALLOWLIST_DIR", dir.resolve("allowlists").toString());
        env.put("PLUGIN_DIR", dir.resolve("plugins").toString());
        env.put("GATEWAY_MANIFEST", dir.resolve("gateway.manifest.json").toString());
    }

    public GatewayHarness manifest(String json) {
        this.manifestJson = json;
        return this;
    }

    public GatewayHarness env(String key, String value) {
        env.put(key, value);
        return this;
    }

    public GatewayHarness allowlist(String file, String content) throws IOException {
        Path f = dir.resolve("allowlists").resolve(file);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return this;
    }

    public GatewayHarness upstream(String instanceId, Upstream upstream) {
        upstreams.put(instanceId, upstream);
        return this;
    }

    public GatewayHarness plugin(GatewayPlugin plugin) {
        plugins.add(plugin);
        return this;
    }

    public Path pluginDir() throws IOException {
        return Files.createDirectories(dir.resolve("plugins"));
    }

    public GatewayHarness start() throws IOException {
        Files.createDirectories(dir.resolve("allowlists"));
        Files.writeString(dir.resolve("gateway.manifest.json"), manifestJson);
        Manifest manifest = ManifestReader.read(manifestJson);
        Config config = Config.fromEnv(env, "test");
        MaskedLog log = new MaskedLog(new java.io.PrintStream(logs, true), "gateway");
        gateway = new Gateway(config, manifest, env, plugins, (instance, baseUrl, restBase, secret, cfg, l) -> {
            Upstream u = upstreams.get(instance.id());
            if (u == null) u = new StubUpstream(baseUrl, restBase);
            return u;
        }, log).start();
        return this;
    }

    public int port() {
        return gateway.port();
    }

    public Gateway gateway() {
        return gateway;
    }

    public String logs() {
        return logs.toString();
    }

    public HttpResponse<String> get(String host, String path) throws IOException, InterruptedException {
        return send("GET", host, path, null, Map.of());
    }

    public HttpResponse<String> get(String host, String path, Map<String, String> headers) throws IOException, InterruptedException {
        return send("GET", host, path, null, headers);
    }

    public HttpResponse<String> send(String method, String host, String path, String body, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + path));
        if (host != null) b.setHeader("Host", host);
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        if (gateway != null) gateway.stop();
    }

    private static final class StringBuilderStream extends java.io.OutputStream {
        private final StringBuilder sb = new StringBuilder();
        @Override public synchronized void write(int b) { sb.append((char) b); }
        @Override public synchronized void write(byte[] b, int off, int len) { sb.append(new String(b, off, len, java.nio.charset.StandardCharsets.UTF_8)); }
        @Override public synchronized String toString() { return sb.toString(); }
    }

    /** Builds a manifest JSON for a single http instance. */
    public static String httpManifest(String id, String hostname, String mode, Map<String, Object> config) {
        Map<String, Object> inst = new LinkedHashMap<>();
        inst.put("id", id);
        inst.put("plugin", "http");
        inst.put("label", id);
        inst.put("hostname", hostname);
        inst.put("baseUrlEnv", id.toUpperCase() + "_BASE_URL");
        inst.put("secret", Map.of("env", id.toUpperCase() + "_TOKEN", "scheme", "bearer"));
        Map<String, Object> al = new LinkedHashMap<>();
        al.put("file", id + ".txt");
        if (mode != null) al.put("mode", mode);
        inst.put("allowlist", al);
        inst.put("config", config);
        return new no.syntakserror.lyngvi.gateway.JacksonJson().write(Map.of("schemaVersion", 1, "instances", List.of(inst)));
    }
}
