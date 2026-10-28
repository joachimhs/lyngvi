package no.syntakserror.lyngvi.gateway;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.api.GatewayPlugin;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Manifest;

/** Assembles plugins, instances, router and listeners. {@link GatewayMain} and the test harness both use it. */
public final class Gateway {

    private final Config config;
    private final Manifest manifest;
    private final Map<String, String> env;
    private final List<GatewayPlugin> extraPlugins;
    private final UpstreamFactory upstreams;
    private final MaskedLog log;
    private final Listeners listeners = new Listeners();
    private Router router;
    private List<InstanceRuntime> instances = List.of();
    private final List<String> warnings = new ArrayList<>();

    public Gateway(Config config, Manifest manifest, Map<String, String> env, List<GatewayPlugin> extraPlugins,
                   UpstreamFactory upstreams, MaskedLog log) {
        this.config = config;
        this.manifest = manifest;
        this.env = env;
        this.extraPlugins = extraPlugins;
        this.upstreams = upstreams;
        this.log = log;
    }

    public static Gateway fromEnvironment(Map<String, String> env, String version) {
        MaskedLog log = new MaskedLog(System.out, "gateway");
        Config config = Config.fromEnv(env, version);
        Manifest manifest = ManifestReader.read(Path.of(config.manifestPath()));
        String caDir = env.get("CA_CERT_DIR");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.upstreamTimeoutMs()))
                .sslContext(TrustStores.sslContext(caDir == null || caDir.isBlank() ? null : Path.of(caDir), log))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new Gateway(config, manifest, env, List.of(), InstanceRuntime.defaultUpstreams(client), log);
    }

    /** Wires everything and starts listening. */
    public Gateway start() throws IOException {
        List<GatewayPlugin> builtIns = new ArrayList<>();
        builtIns.add(new HttpPlugin());
        builtIns.addAll(extraPlugins);
        Map<String, PluginLoader.Loaded> plugins = PluginLoader.load(Path.of(config.pluginDir()), builtIns, log);
        SecretStore secrets = new SecretStore(env, log);
        List<InstanceRuntime> list = new ArrayList<>();
        for (Instance i : manifest.instances()) {
            PluginLoader.Loaded p = plugins.get(i.plugin());
            if (p == null) {
                warn("instance '" + i.id() + "' uses unknown plugin '" + i.plugin() + "'; skipped (available: " + plugins.keySet() + ")");
                continue;
            }
            try {
                list.add(new InstanceRuntime(i, p.plugin(), p.source(), config, secrets, upstreams, log));
                log.info("instance '" + i.id() + "' (" + i.plugin() + ") at " + i.hostname());
            } catch (RuntimeException e) {
                warn("instance '" + i.id() + "' skipped: " + e.getMessage());
            }
        }
        instances = list;
        Map<String, String> pluginSources = new LinkedHashMap<>();
        plugins.forEach((t, p) -> pluginSources.put(t, p.source()));
        router = new Router(config, instances, new HealthReport(config, pluginSources, warnings), log);
        listeners.start(config, router, log);
        return this;
    }

    private void warn(String message) {
        warnings.add(message);
        log.warn(message);
    }

    public int port() {
        return Listeners.port(listeners.servers().getFirst());
    }

    public int tlsPort() {
        return listeners.servers().size() > 1 ? Listeners.port(listeners.servers().getLast()) : -1;
    }

    public List<InstanceRuntime> instances() {
        return instances;
    }

    public List<String> warnings() {
        return warnings;
    }

    public void stop() {
        listeners.stop();
    }
}
