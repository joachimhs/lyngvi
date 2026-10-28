package no.syntakserror.lyngvi.core.api;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.contract.GatewayInstanceInfo;
import no.syntakserror.lyngvi.core.contract.GatewayPluginInfo;
import no.syntakserror.lyngvi.core.contract.JsonOutput;
import no.syntakserror.lyngvi.core.contract.SecretStatus;
import no.syntakserror.lyngvi.core.gateway.ManifestIo;
import no.syntakserror.lyngvi.core.gateway.PluginScanner;
import no.syntakserror.lyngvi.core.render.CertGenerator;
import no.syntakserror.lyngvi.core.render.Renderer;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.session.Locks;
import no.syntakserror.lyngvi.core.stack.Compose;
import no.syntakserror.lyngvi.core.stack.Podman;
import no.syntakserror.lyngvi.gateway.api.AllowlistSpec;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import no.syntakserror.lyngvi.gateway.api.SecretSpec;

/**
 * {@code gateway list|status|logs|restart|rebuild|plugin|instance|secret|allow|cert} (spec §8.7).
 * Instances live in the manifest; secrets in {@code gateway/secrets/<id>.env} (mode 0600); allowlists in
 * {@code gateway/allowlists/<file>} (hot-reloaded by the running gateway). Manifest changes re-render the
 * generated files so Squid, the certificate and compose follow.
 */
public final class GatewayService {

    static final Set<String> SCHEMES = Set.of("bearer", "basic");
    static final Set<String> MODES = Set.of("path-prefix", "path-exact", "path-regex", "none");
    static final List<String> HEALTH_COMMAND = List.of("/opt/jre/bin/java", "-jar", "/app/gateway.jar", "--health");

    private final Sandbox sandbox;
    private final Podman podman;
    private final Compose compose;

    GatewayService(Sandbox sandbox) {
        this.sandbox = sandbox;
        this.podman = new Podman(sandbox.runner(), sandbox.paths().root());
        this.compose = new Compose(sandbox.runner(), sandbox.paths());
    }

    // ---- instances ----

    public Manifest manifest() {
        return ManifestIo.read(sandbox.paths().gatewayManifest());
    }

    public List<GatewayInstanceInfo> list() {
        List<String> types = plugins().stream().map(GatewayPluginInfo::type).filter(t -> t != null).toList();
        return manifest().instances().stream().map(i -> info(i, types)).toList();
    }

    public GatewayInstanceInfo show(String id) {
        return info(instance(id), plugins().stream().map(GatewayPluginInfo::type).toList());
    }

    private GatewayInstanceInfo info(Instance i, List<String> pluginTypes) {
        boolean secret = secretStatus(i).present();
        int entries = allowEntries(i).size();
        String status = !pluginTypes.contains(i.plugin()) ? "no-plugin" : !secret ? "no-secret" : entries == 0 && !"none".equals(i.allowlist().mode()) ? "no-allowlist" : "ready";
        return new GatewayInstanceInfo(i.id(), i.plugin(), i.label(), i.hostname(), i.hostAliases(), i.baseUrlEnv(),
                i.secret().env(), i.secret().scheme(), i.allowlist().file(), i.allowlist().label(), i.allowlist().mode(), entries, secret,
                i.capabilities(), i.urlEnvName(), status);
    }

    private Instance instance(String id) {
        Instance i = manifest().instance(id);
        if (i == null) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "No gateway instance '" + id + "'",
                    Map.of("id", id, "instances", manifest().instances().stream().map(Instance::id).toList()));
        }
        return i;
    }

    /**
     * Adds an instance and re-renders. {@code scheme} defaults to {@code bearer}; {@code mode} (http plugin only) to
     * {@code path-prefix}; {@code config} is the plugin's configuration. Environment variable names derive from the id.
     */
    public GatewayInstanceInfo instanceAdd(String id, String plugin, String label, String hostname, List<String> aliases,
                                           String scheme, String mode, Map<String, Object> config) {
        if (id == null || !Instance.isValidId(id)) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Instance id must match ^[a-z][a-z0-9-]*$: '" + id + "'", Map.of("id", String.valueOf(id)));
        }
        if (hostname == null || !hostname.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Hostname must be a lower-case DNS name: '" + hostname + "'", Map.of("hostname", String.valueOf(hostname)));
        }
        String type = plugin == null || plugin.isBlank() ? "http" : plugin;
        List<GatewayPluginInfo> known = plugins();
        GatewayPluginInfo p = known.stream().filter(k -> type.equals(k.type())).findFirst().orElseThrow(() -> new SandboxException(
                ErrorCode.INVALID_ARGUMENT, "Unknown plugin type '" + type + "' (available: " + String.join(", ", known.stream().map(GatewayPluginInfo::type).filter(t -> t != null).toList()) + ")",
                Map.of("plugin", type)));
        String sch = scheme == null || scheme.isBlank() ? "bearer" : scheme.toLowerCase(Locale.ROOT);
        if (!SCHEMES.contains(sch) && !sch.startsWith("header:") && !sch.startsWith("query:")) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Secret scheme must be bearer, basic, header:<name> or query:<name>", Map.of("scheme", sch));
        }
        String m = mode == null || mode.isBlank() ? null : mode;
        if (m != null && !MODES.contains(m) && !m.startsWith("query:")) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Allowlist mode must be path-prefix, path-exact, path-regex, query:<param> or none", Map.of("mode", m));
        }
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            Manifest manifest = manifest();
            if (manifest.instance(id) != null) {
                throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Gateway instance '" + id + "' already exists", Map.of("id", id));
            }
            List<String> hosts = new ArrayList<>(List.of(hostname));
            hosts.addAll(aliases == null ? List.of() : aliases);
            for (String h : hosts) {
                if (manifest.hostnames().contains(h)) {
                    throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "The hostname '" + h + "' is already used by another instance", Map.of("hostname", h));
                }
            }
            String envBase = id.toUpperCase(Locale.ROOT).replace('-', '_');
            Instance i = new Instance(id, type, label == null || label.isBlank() ? id : label, hostname,
                    aliases == null ? List.of() : List.copyOf(aliases), envBase + "_BASE_URL", new SecretSpec(envBase + "_TOKEN", sch),
                    new AllowlistSpec(id + ".txt", type.equals("http") ? (m == null ? "path-prefix" : m) : m, p.allowlistLabel()),
                    List.of("read"), config == null ? Map.of() : config);
            List<Instance> instances = new ArrayList<>(manifest.instances());
            instances.add(i);
            ManifestIo.write(sandbox.paths().gatewayManifest(), new Manifest(manifest.schemaVersion(), manifest.ipSuffix(), instances));
            new Renderer(sandbox).render();
            reloadProxyQuietly();
            return info(i, known.stream().map(GatewayPluginInfo::type).toList());
        }
    }

    /** Removes the instance from the manifest and re-renders. Allowlist and secret files are kept. */
    public void instanceRemove(String id) {
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            Manifest manifest = manifest();
            instance(id);
            List<Instance> rest = manifest.instances().stream().filter(i -> !i.id().equals(id)).toList();
            ManifestIo.write(sandbox.paths().gatewayManifest(), new Manifest(manifest.schemaVersion(), manifest.ipSuffix(), rest));
            new Renderer(sandbox).render();
            reloadProxyQuietly();
        }
    }

    /** Squid reads {@code gateway-hosts.txt} at start and on reload; a failed reload is not fatal for a manifest edit. */
    private void reloadProxyQuietly() {
        try {
            sandbox.stackService().reloadProxy();
        } catch (RuntimeException ignored) {
            // the change applies when the proxy next starts
        }
    }

    // ---- plugins ----

    public List<GatewayPluginInfo> plugins() {
        return PluginScanner.scan(sandbox.paths().gatewayPlugins());
    }

    /** Copies a validated plugin jar into {@code gateway/plugins/}. The type must not already be provided. */
    public GatewayPluginInfo pluginInstall(Path jar) {
        List<GatewayPluginInfo> declared = PluginScanner.inspect(jar);
        List<GatewayPluginInfo> existing = plugins();
        for (GatewayPluginInfo d : declared) {
            existing.stream().filter(e -> d.type().equals(e.type())).findFirst().ifPresent(e -> {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Plugin type '" + d.type() + "' is already provided by " + e.source(),
                        Map.of("type", d.type(), "source", e.source()));
            });
            if (d.apiVersion() != no.syntakserror.lyngvi.gateway.api.GatewayPlugin.API_VERSION) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Plugin '" + d.type() + "' uses API version " + d.apiVersion()
                        + "; this gateway supports " + no.syntakserror.lyngvi.gateway.api.GatewayPlugin.API_VERSION, Map.of("type", d.type()));
            }
        }
        try {
            Files.createDirectories(sandbox.paths().gatewayPlugins());
            Files.copy(jar, sandbox.paths().gatewayPlugins().resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot copy " + jar + ": " + e.getMessage(), e);
        }
        return new GatewayPluginInfo(declared.getFirst().type(), declared.getFirst().label(), declared.getFirst().allowlistLabel(),
                declared.getFirst().apiVersion(), jar.getFileName().toString(), null);
    }

    public void pluginRemove(String jarName) {
        Path jar = sandbox.paths().gatewayPlugins().resolve(jarName);
        if (jarName.contains("/") || !Files.isRegularFile(jar)) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "No plugin jar '" + jarName + "' in gateway/plugins", Map.of("jar", jarName));
        }
        try {
            Files.delete(jar);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot delete " + jar + ": " + e.getMessage(), e);
        }
    }

    // ---- secrets ----

    private Path secretFile(Instance i) {
        return sandbox.paths().gatewaySecrets().resolve(i.id() + ".env");
    }

    public SecretStatus secretStatus(String id) {
        return secretStatus(instance(id));
    }

    private SecretStatus secretStatus(Instance i) {
        Path f = secretFile(i);
        Map<String, String> values = readEnv(f);
        String token = values.get(i.secret().env());
        boolean present = token != null && !token.isBlank();
        String masked = !present ? null : token.length() <= 4 ? "****" : "****" + token.substring(token.length() - 4);
        return new SecretStatus(i.id(), present, sandbox.paths().root().relativize(f).toString(), values.get(i.baseUrlEnv()), masked);
    }

    /** Writes the base URL and/or the secret; a null argument keeps the current value. Never logs the secret. */
    public SecretStatus secretSet(String id, String baseUrl, String token) {
        Instance i = instance(id);
        Path f = secretFile(i);
        Map<String, String> values = new LinkedHashMap<>(readEnv(f));
        if (baseUrl != null) {
            String url = baseUrl.strip().replaceAll("/+$", "");
            try {
                URI u = URI.create(url);
                if (u.getScheme() == null || u.getHost() == null || !(u.getScheme().equals("https") || u.getScheme().equals("http"))) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Base URL must be an absolute http(s) URL: '" + baseUrl + "'", Map.of("url", baseUrl));
            }
            values.put(i.baseUrlEnv(), url);
        }
        if (token != null) {
            if (token.isBlank() || token.contains("\n")) {
                throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "The secret must be a single non-empty line", Map.of("id", id));
            }
            values.put(i.secret().env(), token.strip());
        }
        StringBuilder sb = new StringBuilder();
        if (values.containsKey(i.baseUrlEnv())) sb.append(i.baseUrlEnv()).append('=').append(values.get(i.baseUrlEnv())).append('\n');
        if (values.containsKey(i.secret().env())) sb.append(i.secret().env()).append('=').append(values.get(i.secret().env())).append('\n');
        values.forEach((k, v) -> { if (!k.equals(i.baseUrlEnv()) && !k.equals(i.secret().env())) sb.append(k).append('=').append(v).append('\n'); });
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, sb.toString());
            try {
                Files.setPosixFilePermissions(f, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX file system: nothing to tighten.
            }
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + f + ": " + e.getMessage(), e);
        }
        return secretStatus(i);
    }

    /** Empties the secret file (it stays listed in compose, so the stack keeps starting). */
    public void secretClear(String id) {
        Path f = secretFile(instance(id));
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, "");
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot delete " + f + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, String> readEnv(Path f) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) return out;
        try {
            for (String line : Files.readAllLines(f)) {
                String l = line.strip();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq < 1) continue;
                out.put(l.substring(0, eq).strip(), l.substring(eq + 1).strip());
            }
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + f + ": " + e.getMessage(), e);
        }
        return out;
    }

    // ---- allowlists ----

    private Path allowFile(Instance i) {
        return sandbox.paths().gatewayAllowlists().resolve(i.allowlist().file());
    }

    private List<String> allowEntries(Instance i) {
        Path f = allowFile(i);
        if (!Files.isRegularFile(f)) return List.of();
        try {
            return Files.readAllLines(f).stream().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + f + ": " + e.getMessage(), e);
        }
    }

    public List<String> allowList(String id) {
        return allowEntries(instance(id));
    }

    /** Appends a normalised entry; false when it was already present. The running gateway picks the file up by itself. */
    public boolean allowAdd(String id, String entry) {
        Instance i = instance(id);
        String e = normaliseEntry(entry);
        if (allowEntries(i).contains(e)) return false;
        Path f = allowFile(i);
        try {
            Files.createDirectories(f.getParent());
            String existing = Files.isRegularFile(f) ? Files.readString(f) : "# Allowlisted " + i.allowlist().label() + " for gateway instance '" + i.id() + "'. One entry per line.\n";
            if (!existing.isEmpty() && !existing.endsWith("\n")) existing += "\n";
            Files.writeString(f, existing + e + "\n");
        } catch (IOException ex) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + f + ": " + ex.getMessage(), ex);
        }
        return true;
    }

    public boolean allowRemove(String id, String entry) {
        Instance i = instance(id);
        String e = normaliseEntry(entry);
        Path f = allowFile(i);
        if (!Files.isRegularFile(f)) return false;
        try {
            List<String> lines = Files.readAllLines(f);
            List<String> kept = lines.stream().filter(l -> !l.strip().equals(e)).toList();
            if (kept.size() == lines.size()) return false;
            Files.writeString(f, String.join("\n", kept) + (kept.isEmpty() ? "" : "\n"));
        } catch (IOException ex) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + f + ": " + ex.getMessage(), ex);
        }
        return true;
    }

    private static String normaliseEntry(String entry) {
        String e = entry == null ? "" : entry.strip();
        if (e.isEmpty() || e.startsWith("#") || e.contains("\n")) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "An allowlist entry is one non-empty line that does not start with '#'", Map.of("entry", String.valueOf(entry)));
        }
        return e;
    }

    // ---- the running gateway ----

    private String container() {
        return sandbox.paths().projectName() + "-gateway";
    }

    /** The gateway's own {@code /healthz} document, fetched inside the container. */
    public Map<String, Object> status(boolean deep) {
        String name = container();
        boolean running = podman.ps(name).stream().anyMatch(c -> c.name().equals(name) && c.state().equals("running"));
        if (!running) {
            throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, "The gateway container '" + name + "' is not running",
                    Map.of("container", name, "hint", "run 'setup.sh up' (and 'setup.sh build' first if the image is missing)"));
        }
        List<String> cmd = new ArrayList<>(HEALTH_COMMAND);
        if (deep) cmd.add("--deep");
        Runner.Result r = podman.exec(name, cmd);
        String body = r.stdout().strip();
        try {
            return JsonOutput.mapper().readValue(body.isEmpty() ? "{}" : body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.EXTERNAL_COMMAND_FAILED, "The gateway health command returned no JSON: " + (body.isEmpty() ? r.stderr().strip() : body), Map.of("container", name));
        }
    }

    public void logs(boolean follow, int tail, Consumer<String> onLine) {
        sandbox.stackService().logs("gateway", follow, tail, onLine);
    }

    /**
     * Recreates the container through compose so it picks up the manifest, secret files (compose reads {@code env_file}
     * only at creation), plugins and certificate. Allowlist edits do not need this.
     */
    public void restart() {
        sandbox.stackService().requireRendered();
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            compose.upService("gateway");
        }
    }

    /** Rebuilds the gateway image (after a new gateway.jar or Containerfile) and recreates the container. */
    public void rebuild(boolean noCache, Consumer<String> onLine) {
        sandbox.stackService().requireRendered();
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            compose.build(List.of("gateway"), noCache, onLine);
            compose.upService("gateway");
        }
    }

    // ---- certificate ----

    public CertGenerator certGenerator() {
        return new CertGenerator(sandbox.runner(), sandbox.paths().gatewayCerts());
    }

    /** Issues a new self-signed certificate covering every gateway hostname; restart the gateway afterwards. */
    public Path certRenew() {
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            Manifest m = manifest();
            String ip = sandbox.config().subnetPrefix().orElse("0.0.0") + "." + m.ipSuffix();
            CertGenerator certs = certGenerator();
            certs.ensure(m.hostnames(), ip, true);
            return certs.cert();
        }
    }
}
