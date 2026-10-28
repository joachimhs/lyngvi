package no.syntakserror.lyngvi.gateway;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.syntakserror.lyngvi.gateway.api.Allowlist;
import no.syntakserror.lyngvi.gateway.api.GatewayException;
import no.syntakserror.lyngvi.gateway.api.GatewayPlugin;
import no.syntakserror.lyngvi.gateway.api.Health;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Json;
import no.syntakserror.lyngvi.gateway.api.Log;
import no.syntakserror.lyngvi.gateway.api.PluginContext;
import no.syntakserror.lyngvi.gateway.api.Request;
import no.syntakserror.lyngvi.gateway.api.Route;
import no.syntakserror.lyngvi.gateway.api.TextUtil;
import no.syntakserror.lyngvi.gateway.api.Upstream;

/** One manifest instance wired to its plugin: config, upstream, allowlist and compiled routes. */
public final class InstanceRuntime implements PluginContext {

    /** A route with its path pattern compiled. {@code {name}} matches one segment; {@code /**} the rest into {@code path}. */
    public record CompiledRoute(Route route, Pattern pattern, List<String> names) {
        Map<String, String> match(String path) {
            Matcher m = pattern.matcher(path);
            if (!m.matches()) return null;
            Map<String, String> params = new java.util.LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                String v = m.group(i + 1);
                params.put(names.get(i), v == null ? "" : java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8));
            }
            return params;
        }
    }

    private final Instance instance;
    private final GatewayPlugin plugin;
    private final String source;
    private final Config config;
    private final Object pluginConfig;
    private final Upstream upstream;
    private final Allowlist allowlist;
    private final MaskedLog log;
    private final Json json = new JacksonJson();
    private final TextUtil text = new Text();
    private final List<CompiledRoute> routes;
    private final String missing;
    private final boolean secretPresent;

    InstanceRuntime(Instance instance, GatewayPlugin plugin, String source, Config config, SecretStore secrets,
                    UpstreamFactory upstreams, MaskedLog parentLog) {
        this.instance = instance;
        this.plugin = plugin;
        this.source = source;
        this.config = config;
        this.log = parentLog.child("[" + instance.id() + "]");
        this.pluginConfig = convertConfig(plugin, instance);
        String baseUrl = instance.baseUrlEnv() == null ? null : secrets.value(instance.baseUrlEnv());
        String secret = secrets.read(instance.secret());
        this.secretPresent = secret != null;
        List<String> missingVars = new ArrayList<>();
        if (baseUrl == null) missingVars.add(instance.baseUrlEnv());
        if (secret == null && instance.secret() != null && instance.secret().env() != null) missingVars.add(instance.secret().env());
        this.missing = missingVars.isEmpty() ? null : String.join(", ", missingVars);
        String restBase = pluginConfig instanceof HttpPlugin.HttpConfig h ? h.restBaseOrDefault() : plugin.restBase(instance);
        this.upstream = baseUrl == null ? null : upstreams.create(instance, baseUrl, restBase, secret, config, log);
        boolean enforced = config.allowlistEnforced() && (!(plugin instanceof HttpPlugin) || HttpPlugin.allowlistEnforced(instance));
        this.allowlist = new AllowlistStore(Path.of(config.allowlistDir()).resolve(instance.allowlist().file()),
                instance.allowlist().label(), enforced, plugin.allowlistFor(instance), upstream, log);
        this.routes = plugin.routes().stream().map(InstanceRuntime::compile).toList();
    }

    static Object convertConfig(GatewayPlugin plugin, Instance instance) {
        Class<?> type = plugin.configType();
        if (type == null || type == Void.class || type == Map.class) return instance.config() == null ? Map.of() : instance.config();
        try {
            return JacksonJson.MAPPER.convertValue(instance.config() == null ? Map.of() : instance.config(), type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("instance '" + instance.id() + "' has invalid config for plugin '" + plugin.type() + "': " + e.getMessage(), e);
        }
    }

    static CompiledRoute compile(Route route) {
        String p = route.pathPattern();
        List<String> names = new ArrayList<>();
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (p.startsWith("/**", i)) {
                re.append("(/.*)?");
                names.add("path");
                i += 3;
            } else if (c == '{') {
                int end = p.indexOf('}', i);
                names.add(p.substring(i + 1, end));
                re.append("([^/]+)");
                i = end + 1;
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        re.append("$");
        return new CompiledRoute(route, Pattern.compile(re.toString()), names);
    }

    public Instance instanceSpec() {
        return instance;
    }

    public GatewayPlugin plugin() {
        return plugin;
    }

    public String source() {
        return source;
    }

    public List<CompiledRoute> routes() {
        return routes;
    }

    public boolean configured() {
        return missing == null;
    }

    public String missing() {
        return missing;
    }

    public boolean secretPresent() {
        return secretPresent;
    }

    public boolean writeAllowed() {
        return instance.canWrite() && config.allowWrite().contains(instance.id());
    }

    /** Status without touching the upstream: {@code ok}, {@code unconfigured} or {@code no-allowlist}. */
    public String status() {
        if (!configured()) return "unconfigured";
        if (allowlist.enforced() && allowlist.describe().count() == 0) return "no-allowlist";
        return "ok";
    }

    /** Runs the plugin's upstream probe ({@code /healthz?deep=1}); null when the plugin has none or the instance is unconfigured. */
    public Health probe() {
        if (!configured()) return null;
        try {
            return plugin.health().probe(this);
        } catch (RuntimeException e) {
            return new Health(false, 0, e.getMessage(), null);
        }
    }

    @Override
    public Instance instance() {
        return instance;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T config(Class<T> type) {
        if (type.isInstance(pluginConfig)) return (T) pluginConfig;
        return JacksonJson.MAPPER.convertValue(pluginConfig, type);
    }

    @Override
    public Upstream upstream() {
        if (upstream == null) throw new GatewayException(503, "gateway not configured: missing " + missing, Map.of("adapter", instance.id()));
        return upstream;
    }

    @Override
    public Allowlist allowlist() {
        return allowlist;
    }

    @Override
    public TextUtil text() {
        return text;
    }

    @Override
    public Json json() {
        return json;
    }

    @Override
    public Log log() {
        return log;
    }

    @Override
    public int limit(Request req) {
        String v = req.query("limit");
        if (v == null || v.isBlank()) return Math.min(25, config.maxLimit());
        try {
            return Math.max(1, Math.min(Integer.parseInt(v.strip()), config.maxLimit()));
        } catch (NumberFormatException e) {
            throw new GatewayException(400, "limit must be an integer", Map.of("limit", v));
        }
    }

    static UpstreamFactory defaultUpstreams(HttpClient client) {
        return (instance, baseUrl, restBase, secret, config, log) -> {
            long max = 5_000_000L;
            long timeout = config.upstreamTimeoutMs();
            Set<String> methods = Set.of("GET", "HEAD");
            if (instance.config() != null) {
                Object m = instance.config().get("maxResponseBytes");
                if (m instanceof Number n) max = n.longValue();
                Object t = instance.config().get("timeoutMs");
                if (t instanceof Number n) timeout = n.longValue();
                Object ms = instance.config().get("methods");
                if (ms instanceof List<?> l) methods = l.stream().map(String::valueOf).map(String::toUpperCase).collect(java.util.stream.Collectors.toSet());
            }
            if (instance.canWrite() && config.allowWrite().contains(instance.id()) && !(instance.config() != null && instance.config().get("methods") instanceof List)) {
                methods = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
            }
            return new HttpUpstream(client, instance, baseUrl, restBase, secret, config, max, timeout, methods);
        };
    }
}
