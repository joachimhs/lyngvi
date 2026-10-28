package no.syntakserror.lyngvi.core.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.api.StackService;
import no.syntakserror.lyngvi.core.ask.AgentContext;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.config.DotEnv;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.Runner;

/**
 * The models an agent may reach on the developer's own machine: the provider block in the agent's own configuration
 * file (named by {@code agent.json} {@code localModels}), and a reachability test that asks the server itself.
 *
 * <p>The test runs from a throwaway container on the internal network and through the proxy — the same path a
 * session takes — so it answers the question that matters: not «is the server up on my Mac», but «can the agent
 * reach it, and does it have the models I listed». Everything else in the configuration file is left untouched.
 */
public final class LocalModelConfig {

    /** The provider as the user configured it: where the server is and which of its models the agent may use. */
    public record Settings(String provider, String baseUrl, List<String> models) {

        public Settings {
            provider = provider == null ? "" : provider.strip();
            baseUrl = baseUrl == null ? "" : baseUrl.strip();
            models = models == null ? List.of() : models.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        }
    }

    /**
     * What the server answered.
     *
     * @param available the model ids it reports; empty when it could not be reached
     * @param detail    a short line for the user, whether it worked or not
     */
    public record Probe(boolean reachable, int status, String detail, List<String> available) {

        /** The configured models the server does not have; these are the ones an ask would fail on. */
        public List<String> missing(List<String> configured) {
            return reachable ? configured.stream().filter(m -> !available.contains(m)).toList() : List.of();
        }
    }

    /**
     * The provider as configured plus what is known about its key. The key itself is never handed out: it lives in
     * {@code state/agents/<id>/env}, is mounted only into that agent's containers, and is read from there by the
     * agent and by the test.
     *
     * @param apiKeyEnv the variable the agent reads the key from, empty when the agent has no such notion
     */
    public record State(Settings settings, boolean apiKeySet, String apiKeyEnv) {}

    /** Why a base URL cannot be used; {@link #NONE} when it can. */
    public enum UrlProblem { NONE, BLANK, NOT_HTTP, LOOPBACK }

    /**
     * What would let a request the proxy denied through. The proxy has two doors and they are not the same
     * decision: a port to the developer's own machine, or a host in the egress allowlist.
     */
    public enum WayOut {
        /** Nothing to offer: the address is unusable, or already allowed, or an address neither door covers. */
        NONE,
        /** The port to the model server on the developer's own machine ({@code profile.json localModels.ports}). */
        OPEN_PORT,
        /** The host in the egress allowlist, which is how a server out on the network is let out of the sandbox. */
        ALLOW_HOST
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Duration PROBE_TIMEOUT = Duration.ofMinutes(2);
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern PRIVATE_IPV4 = Pattern.compile(
            "(10|127)\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}"
            + "|169\\.254\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}");

    private final AgentContext ctx;

    public LocalModelConfig(AgentContext ctx) {
        this.ctx = ctx;
    }

    /**
     * A base URL is only useful from inside a container: it must be absolute http(s), and it must not point at
     * loopback — there, a container reaches itself and never the machine the model server runs on.
     */
    public static UrlProblem check(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return UrlProblem.BLANK;
        URI uri;
        try {
            uri = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            return UrlProblem.NOT_HTTP;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return UrlProblem.NOT_HTTP;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("0.0.0.0")) return UrlProblem.LOOPBACK;
        return UrlProblem.NONE;
    }

    /** The TCP port an address speaks to — the one the proxy has to allow — or -1 when the address is not usable. */
    public static int port(String baseUrl) {
        if (check(baseUrl) != UrlProblem.NONE) return -1;
        URI uri = URI.create(baseUrl.strip());
        return uri.getPort() > 0 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80;
    }

    /** The host an address speaks to, lowercased — what the allowlist is about — or empty when it is not usable. */
    public static String host(String baseUrl) {
        if (check(baseUrl) != UrlProblem.NONE) return "";
        String host = URI.create(baseUrl.strip()).getHost();
        return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * What the user could do about a denied request to this address, given the egress policy as it stands. The
     * proxy lets a model server through in one of two ways, and which one it is follows from the address: a server
     * on the developer's own machine answers on a private address, which the proxy denies outright unless the port
     * is in {@code localModels.ports}; a server out on the network is let out by its host being in the allowlist.
     *
     * <p>{@link WayOut#NONE} when there is nothing to offer — the address is unusable, the door it needs is open
     * already (so the denial was about something else), or it is a bare IP address, which an allowlist of domain
     * names cannot hold.
     *
     * @param localModelHost the host {@code profile.json localModels} names, {@code allowedDomains} the allowlist
     *                       as {@code domain list} reports it — a leading dot there covers subdomains, as in Squid
     */
    public static WayOut wayOut(String baseUrl, String localModelHost, Collection<Integer> openPorts, Collection<String> allowedDomains) {
        String host = host(baseUrl);
        if (host.isEmpty()) return WayOut.NONE;
        String own = localModelHost == null ? "" : localModelHost.strip().toLowerCase(java.util.Locale.ROOT);
        if (host.equals(own) || onThisMachine(host)) {
            return openPorts != null && openPorts.contains(port(baseUrl)) ? WayOut.NONE : WayOut.OPEN_PORT;
        }
        if (host.startsWith("[") || IPV4.matcher(host).matches()) return WayOut.NONE;
        return allowed(host, allowedDomains) ? WayOut.NONE : WayOut.ALLOW_HOST;
    }

    /**
     * Whether only the private route can reach this address: a name with no dot in it or one of the container
     * runtime's own names, and every private IPv4 or IPv6 literal. The proxy denies private destinations before it
     * ever reads the allowlist, so for these the allowlist is not the door — the port is.
     */
    private static boolean onThisMachine(String host) {
        return !host.contains(".") || host.endsWith(".internal") || host.endsWith(".local")
                || host.startsWith("[fd") || host.startsWith("[fe80") || PRIVATE_IPV4.matcher(host).matches();
    }

    /** Squid reads the allowlist as {@code dstdomain}: a plain entry is that host, a leading dot its subdomains. */
    private static boolean allowed(String host, Collection<String> allowedDomains) {
        if (allowedDomains == null) return false;
        for (String entry : allowedDomains) {
            if (entry == null || entry.isBlank()) continue;
            String domain = entry.strip().toLowerCase(java.util.Locale.ROOT);
            boolean match = domain.startsWith(".")
                    ? host.equals(domain.substring(1)) || host.endsWith(domain)
                    : host.equals(domain);
            if (match) return true;
        }
        return false;
    }

    /**
     * The models configured for the agent's own model server, as the ids the server knows them by — and nothing
     * when the agent has no such notion or its configuration file cannot be read. Unlike {@link #read}, this is a
     * question anyone may ask about any agent, so it answers «none» rather than throwing.
     */
    public List<String> configured(AgentDescriptor descriptor) {
        AgentDescriptor.LocalModels spec = descriptor.localModels();
        if (spec == null || spec.provider() == null || spec.provider().isBlank()
                || spec.configFile() == null || spec.configFile().isBlank()) {
            return List.of();
        }
        try {
            return read(descriptor).models();
        } catch (RuntimeException e) {
            // A configuration file the agent itself would reject says nothing about its models.
            return List.of();
        }
    }

    /** The provider block and whether a key is stored for it — everything the caller needs to show, and no secret. */
    public State state(AgentDescriptor descriptor) {
        String env = spec(descriptor).apiKeyEnv();
        return new State(read(descriptor), !apiKey(descriptor).isEmpty(), env == null ? "" : env);
    }

    /**
     * Stores the server's API key as the variable the agent reads it from, in the agent's own {@code env} file —
     * the same place its other credentials live, mounted into that agent's containers and nowhere else.
     * A blank key removes it.
     */
    public void setApiKey(AgentDescriptor descriptor, String key) {
        String env = spec(descriptor).apiKeyEnv();
        if (env == null || env.isBlank()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "Agent '" + descriptor.id() + "' has no place to keep a key for a local model server (agent.json localModels.apiKeyEnv)",
                    Map.of("agent", descriptor.id()));
        }
        DotEnv.set(envFile(descriptor), env, key);
    }

    /** The stored key, or empty; only the probe and {@link #state} use it, and neither hands it back to a caller. */
    private String apiKey(AgentDescriptor descriptor) {
        String env = spec(descriptor).apiKeyEnv();
        Path file = envFile(descriptor);
        if (env == null || env.isBlank() || !Files.isRegularFile(file)) return "";
        return DotEnv.read(file).getOrDefault(env, "");
    }

    private Path envFile(AgentDescriptor descriptor) {
        return ctx.paths().agentState(descriptor.id()).resolve("env");
    }

    /** A key to test with, in a file only its owner can read, in the directory the agent's credentials already live in. */
    private Path temporaryEnv(AgentDescriptor descriptor, String env, String apiKey) {
        try {
            Path dir = Files.createDirectories(ctx.paths().agentState(descriptor.id()));
            Path file = Files.createTempFile(dir, ".probe-", ".env");
            try {
                Files.setPosixFilePermissions(file, java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX file system: nothing to tighten.
            }
            Files.writeString(file, apiKey.isBlank() ? "" : env + "=" + apiKey.strip() + "\n");
            return file;
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot prepare the test: " + e.getMessage(), e);
        }
    }

    /** The provider block as it stands, or the descriptor's default address and no models when there is none yet. */
    public Settings read(AgentDescriptor descriptor) {
        AgentDescriptor.LocalModels spec = spec(descriptor);
        JsonNode provider = configFile(descriptor).flatMap(LocalModelConfig::readTree)
                .map(root -> root.path("provider").path(spec.provider())).orElse(null);
        if (provider == null || !provider.isObject()) {
            return new Settings(spec.provider(), spec.defaultBaseUrl(), List.of());
        }
        String baseUrl = provider.path("options").path("baseURL").asText(spec.defaultBaseUrl());
        List<String> models = new ArrayList<>();
        provider.path("models").fieldNames().forEachRemaining(models::add);
        return new Settings(spec.provider(), baseUrl, models);
    }

    /**
     * Writes the provider block, keeping everything else in the file — other providers, and the fields the user put
     * on a model that this dialog does not know about (a context limit, provider options). The model catalogue cached
     * for the agent is dropped, so the next listing sees the new models.
     */
    public void save(AgentDescriptor descriptor, Settings settings) {
        AgentDescriptor.LocalModels spec = spec(descriptor);
        UrlProblem problem = check(settings.baseUrl());
        if (problem != UrlProblem.NONE) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, switch (problem) {
                case BLANK -> "The address of the model server is required (for example " + spec.defaultBaseUrl() + ")";
                case LOOPBACK -> "localhost inside a container is the container itself; use the name the sandbox reaches "
                        + "your machine by, as in " + spec.defaultBaseUrl();
                default -> "Not an http(s) address: " + settings.baseUrl();
            }, Map.of("baseUrl", settings.baseUrl()));
        }
        Path file = ctx.paths().agentState(descriptor.id()).resolve(spec.configFile());
        ObjectNode root = readTree(file).filter(JsonNode::isObject).map(ObjectNode.class::cast).orElseGet(JSON::createObjectNode);
        if (!root.has("$schema")) root.put("$schema", "https://opencode.ai/config.json");
        ObjectNode provider = root.with("provider").with(spec.provider());
        provider.with("options").put("baseURL", settings.baseUrl());
        // Naming the variable makes the key work for a provider the agent's own catalogue does not know.
        if (spec.apiKeyEnv() != null && !spec.apiKeyEnv().isBlank() && !provider.has("env")) {
            provider.putArray("env").add(spec.apiKeyEnv());
        }
        ObjectNode models = provider.with("models");
        List<String> gone = new ArrayList<>();
        models.fieldNames().forEachRemaining(id -> {
            if (!settings.models().contains(id)) gone.add(id);
        });
        gone.forEach(models::remove);
        for (String id : settings.models()) {
            if (!models.has(id)) models.set(id, JSON.createObjectNode().put("name", id).put("tool_call", true));
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(root) + "\n");
            Files.deleteIfExists(ctx.paths().agentState(descriptor.id()).resolve("models.json"));
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Asks the server for its models the way the agent would: {@code GET <baseUrl>/models} from a throwaway container
     * on the internal network, through the proxy, with the stored key if there is one. A denied port and a server
     * that is not running look different — the proxy answers 403, a missing server answers nothing at all.
     *
     * <p>The key reaches the container as the agent's own {@code env} file ({@code --env-file}), so it is never an
     * argument on a command line the app logs; the script reads it from the environment and sends it as a bearer
     * token, exactly as the agent's SDK would.
     */
    public Probe probe(AgentDescriptor descriptor, String baseUrl) {
        return probe(descriptor, baseUrl, null);
    }

    /**
     * As {@link #probe(AgentDescriptor, String)}, but with a key that has not been saved yet — so a key can be
     * tested before it is stored. It is written to a private file for the run and removed again, never an argument.
     *
     * @param apiKey the key to test with, or null to use the stored one
     */
    public Probe probe(AgentDescriptor descriptor, String baseUrl, String apiKey) {
        UrlProblem problem = check(baseUrl);
        if (problem != UrlProblem.NONE) {
            return new Probe(false, 0, switch (problem) {
                case BLANK -> "No address to test";
                case LOOPBACK -> "localhost is the container itself; use the name the sandbox reaches your machine by";
                default -> "Not an http(s) address: " + baseUrl;
            }, List.of());
        }
        if (ctx.subnetPrefix() == null) {
            return new Probe(false, 0, "No network yet; run 'setup.sh rebuild-config' and 'setup.sh up'", List.of());
        }
        String env = spec(descriptor).apiKeyEnv();
        boolean named = env != null && !env.isBlank();
        Path envFile = apiKey == null || !named ? envFile(descriptor) : temporaryEnv(descriptor, env, apiKey);
        boolean withKey = named && Files.isRegularFile(envFile);
        try {
            return probe(descriptor, baseUrl, env, envFile, withKey);
        } finally {
            if (apiKey != null && named) {
                try {
                    Files.deleteIfExists(envFile);
                } catch (IOException ignored) {
                    // A key that outlives the test would be worse, but the file is private to the sandbox directory.
                }
            }
        }
    }

    private Probe probe(AgentDescriptor descriptor, String baseUrl, String env, Path envFile, boolean withKey) {
        String url = baseUrl.strip().replaceAll("/+$", "") + "/models";
        String script = (withKey ? "set --; [ -n \"$" + env + "\" ] && set -- -H \"Authorization: Bearer $" + env + "\"; " : "set --; ")
                + "code=$(curl -s -m 15 -x " + ctx.proxyUrl() + " \"$@\" -o /tmp/models.json -w '%{http_code}' " + url + " 2>/dev/null); "
                + "echo \"status=$code\"; cat /tmp/models.json 2>/dev/null";
        List<String> cmd = new ArrayList<>(List.of("podman", "run", "--rm",
                "--network", ctx.project() + "_internal"));
        if (ctx.platform().os() == Platform.Os.LINUX) {
            cmd.addAll(List.of("--add-host", "host.containers.internal:host-gateway"));
        }
        if (withKey) {
            cmd.addAll(List.of("--env-file", envFile.toString()));
        }
        cmd.addAll(List.of("--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--entrypoint", "sh", StackService.baseImage(), "-c", script));
        Runner.Result result;
        try {
            result = ctx.runner().run(cmd, ctx.paths().root(), PROBE_TIMEOUT);
        } catch (RuntimeException e) {
            return new Probe(false, 0, "The test container could not start: " + e.getMessage(), List.of());
        }
        return parse(result.stdout(), url);
    }

    /** {@code status=<code>} and then the body: an OpenAI-compatible {@code {"data":[{"id":...}]}} list. */
    static Probe parse(String stdout, String url) {
        String[] parts = stdout.split("\n", 2);
        int status = 0;
        if (parts[0].startsWith("status=")) {
            try {
                status = Integer.parseInt(parts[0].substring(7).strip());
            } catch (NumberFormatException ignored) {
                // no status line: treated as unreachable below
            }
        }
        String body = parts.length > 1 ? parts[1] : "";
        if (status == 0) return new Probe(false, 0, "No answer from " + url + " (is the server running, and does it accept connections from outside your machine?)", List.of());
        // Squid names itself in its error page; a 403 from the server itself is about the request, not the port.
        boolean fromProxy = body.isBlank() || body.toLowerCase(java.util.Locale.ROOT).contains("squid");
        if (status == 403 && fromProxy) {
            // Which door is missing depends on where the server is, and the caller knows that: see wayOut.
            return new Probe(false, 403, "The proxy denied the request: " + url + " is not allowed out of the sandbox yet. "
                    + "A server on your own machine needs its port in profile.json (localModels.ports); a server on the "
                    + "network needs its host in the egress allowlist ('domain add <host>')", List.of());
        }
        if (status == 401 || status == 403) {
            return new Probe(false, status, url + " refused the request (" + status + "); the server wants an API key, or the one stored is wrong", List.of());
        }
        if (status != 200) return new Probe(false, status, url + " answered " + status, List.of());
        List<String> ids = new ArrayList<>();
        try {
            JsonNode n = Jsonc.MAPPER.readTree(body);
            JsonNode list = n.isArray() ? n : n.has("data") ? n.path("data") : n.path("models");
            for (JsonNode m : list) {
                String id = m.isTextual() ? m.asText() : m.path("id").asText("");
                if (!id.isBlank()) ids.add(id);
            }
        } catch (Exception e) {
            return new Probe(false, status, url + " answered 200 but not a model list", List.of());
        }
        return new Probe(true, status, ids.size() + " model(s) on the server", List.copyOf(ids));
    }

    private static AgentDescriptor.LocalModels spec(AgentDescriptor descriptor) {
        AgentDescriptor.LocalModels spec = descriptor.localModels();
        if (spec == null || spec.configFile() == null || spec.configFile().isBlank()) {
            // The descriptor read is the sandbox's own copy: one written before localModels existed says nothing here.
            throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "Agent '" + descriptor.id() + "' has no local model configuration (agent.json localModels)",
                    Map.of("agent", descriptor.id(),
                            "hint", "agents/" + descriptor.id() + "/agent.json in this sandbox may predate the section; "
                                    + "'setup.sh upgrade' refreshes the bundled agent files"));
        }
        return spec;
    }

    private java.util.Optional<Path> configFile(AgentDescriptor descriptor) {
        Path file = ctx.paths().agentState(descriptor.id()).resolve(spec(descriptor).configFile());
        return Files.isRegularFile(file) ? java.util.Optional.of(file) : java.util.Optional.empty();
    }

    private static java.util.Optional<JsonNode> readTree(Path file) {
        try {
            return Files.isRegularFile(file) ? java.util.Optional.of(Jsonc.MAPPER.readTree(Files.readString(file))) : java.util.Optional.empty();
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Cannot read " + file + ": " + e.getMessage(),
                    Map.of("file", file.toString()));
        }
    }
}
