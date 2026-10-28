package no.syntakserror.lyngvi.core.migrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.config.RepoEntry;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.config.SandboxConfig;
import no.syntakserror.lyngvi.core.config.SandboxConfigFile;
import no.syntakserror.lyngvi.core.contract.MigrateReport;
import no.syntakserror.lyngvi.core.gateway.ManifestIo;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.session.ConversationStore;
import no.syntakserror.lyngvi.core.template.Templates;
import no.syntakserror.lyngvi.core.version.Version;
import no.syntakserror.lyngvi.gateway.api.AllowlistSpec;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import no.syntakserror.lyngvi.gateway.api.SecretSpec;

/**
 * Converts a 1.1.2 sandbox to the 2.0 layout (spec §12), idempotently: the marker
 * {@code state/.migrated-from-1.1.2} ends a second run early. Everything organisation-specific that the old
 * installation carried (domains, gateway systems, enterprise URL) lands in the generated profile pack
 * {@code profiles/migrated/} and nowhere else.
 */
public final class Migrate112 {

    public static final String MARKER = ".migrated-from-1.1.2";
    static final String MIGRATED_AGENT = "copilot-cli";
    static final List<String> TOOLCHAINS = List.of("node22", "java17", "java21", "java25", "kotlin", "maven", "gradle");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path root;
    private final Platform platform;
    private final Runner runner;
    private final Paths paths;
    private final List<String> manual = new ArrayList<>();

    private Migrate112(Path root, Platform platform, Runner runner) {
        this.root = root.toAbsolutePath().normalize();
        this.platform = platform;
        this.runner = runner;
        this.paths = Paths.of(this.root);
    }

    /** True for a directory with a 1.1.2 {@code sandbox.config} (no {@code SCHEMA_VERSION}). */
    public static boolean needsMigration(Path dir) {
        return SandboxConfigFile.needsMigration(dir.resolve("sandbox.config"));
    }

    public static MigrateReport run(Path dir, Platform platform, Runner runner) {
        return new Migrate112(dir, platform, runner).run();
    }

    private MigrateReport run() {
        Path marker = paths.root().resolve("state").resolve(MARKER);
        if (Files.isRegularFile(marker)) {
            return new MigrateReport(true, readMarker(marker), 0, 0, 0, List.of("Already migrated; nothing was changed."));
        }
        if (!needsMigration(root)) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, root + " is not a 1.1.2 sandbox (no sandbox.config without SCHEMA_VERSION)",
                    Map.of("dir", root.toString()));
        }
        try {
            Map<String, String> old = new LinkedHashMap<>();
            List<RepoEntry> repos = new ArrayList<>();
            readOldConfig(old, repos);
            Path pack = writeProfilePack(old);
            int instances = migrateGateway();
            Optional<String> prefix = migrateSubnets();
            SandboxConfig config = new SandboxConfig(SandboxConfig.CURRENT_SCHEMA_VERSION, parseInt(old, "UID", platform.uid()), parseInt(old, "GID", platform.gid()),
                    pack, prefix, new ResourceLimits(old.getOrDefault("MEM_LIMIT", "8g"), old.getOrDefault("MEMSWAP_LIMIT", old.getOrDefault("MEM_LIMIT", "8g")),
                            old.getOrDefault("CPUS", "4.0"), old.getOrDefault("PIDS_LIMIT", "2048")), "claude-code", "nb", repos);
            SandboxConfigFile.write(paths.sandboxConfig(), config);
            Files.copy(pack.resolve("profile.json"), paths.profileJson(), StandardCopyOption.REPLACE_EXISTING);
            Templates.writeAll(root);
            Files.writeString(root.resolve("VERSION"), Version.current() + "\n");
            for (String generated : List.of("Containerfile", "docker-compose.yml")) Files.deleteIfExists(root.resolve(generated));
            Files.createDirectories(paths.conversations());
            Files.createDirectories(paths.agentsState());
            Files.createDirectories(paths.locks());
            Files.createDirectories(paths.sessions());
            int conversations = migrateConversations(config);
            int sessions = migrateSessions();
            migrateCopilotConfig();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, pack + "\n");
            Sandbox sandbox = Sandbox.open(root, platform, runner);
            sandbox.sandboxService().rebuildConfig();
            manual.add("Build the images: setup.sh build");
            manual.add("Log in to the agents you will use: setup.sh agent login claude-code (the migrated conversations belong to the "
                    + MIGRATED_AGENT + " agent and are kept read-only unless a copilot adapter is installed)");
            if (instances > 0) manual.add("Check gateway/secrets/<id>.env: the old PAT variables were kept, a <ID>_BASE_URL was added; then setup.sh up");
            manual.add("Files from copilot-output-files/ were copied into every existing conversation's output/ (they could not be attributed)");
            return new MigrateReport(false, pack.toString(), conversations, sessions, instances, List.copyOf(manual));
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Migration failed: " + e.getMessage(), e);
        }
    }

    private static String readMarker(Path marker) {
        try {
            return Files.readString(marker).strip();
        } catch (IOException e) {
            return "";
        }
    }

    // ---- 1. sandbox.config ----

    private void readOldConfig(Map<String, String> old, List<RepoEntry> repos) throws IOException {
        for (String line : Files.readAllLines(paths.sandboxConfig())) {
            String l = line.strip();
            int eq = l.indexOf('=');
            if (l.isEmpty() || l.startsWith("#") || eq < 1) continue;
            String key = l.substring(0, eq).strip();
            String value = l.substring(eq + 1).strip();
            if (key.equals("REPO")) {
                String[] parts = value.split("\\|", 2);
                Path path = Path.of(parts[0].strip());
                String name = parts.length > 1 && !parts[1].isBlank() ? parts[1].strip() : path.getFileName().toString();
                repos.add(new RepoEntry(path, name, Optional.empty()));
            } else {
                old.put(key, value);
            }
        }
    }

    private static int parseInt(Map<String, String> m, String key, int fallback) {
        try {
            return Integer.parseInt(m.getOrDefault(key, String.valueOf(fallback)).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- 2. profile pack ----

    private Path writeProfilePack(Map<String, String> old) throws IOException {
        Path pack = Files.createDirectories(root.resolve("profiles").resolve("migrated"));
        ObjectNode profile;
        try (InputStream in = Migrate112.class.getResourceAsStream("/bundle/profiles/default/profile.json")) {
            if (in == null) throw new SandboxException(ErrorCode.INTERNAL, "Bundled default profile is missing");
            profile = (ObjectNode) JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        profile.put("name", "migrated");
        profile.put("label", "Migrated from 1.1.2");
        profile.put("language", "nb");
        ArrayNode toolchains = profile.putArray("toolchains");
        TOOLCHAINS.forEach(toolchains::add);
        ArrayNode allow = ((ObjectNode) profile.get("egress")).putArray("allow");
        Path domains = paths.allowedDomains();
        if (Files.isRegularFile(domains)) {
            for (String line : Files.readAllLines(domains)) {
                String d = line.strip();
                if (d.isEmpty() || d.startsWith("#")) continue;
                allow.add(d.split("\\s+")[0]);
            }
        }
        Files.writeString(pack.resolve("profile.json"), JSON.writeValueAsString(profile) + "\n");
        String enterprise = old.getOrDefault("ENTERPRISE_URL", "");
        Files.writeString(pack.resolve("copilot-cli.env"), "# Copilot CLI configuration carried over from the 1.1.2 installation.\n"
                + "COPILOT_ENTERPRISE_URL=" + enterprise + "\n");
        Files.writeString(pack.resolve("README.md"), "# Profile pack: migrated\n\nGenerated by `setup.sh migrate` from a 1.1.2 sandbox. It holds everything that was\n"
                + "specific to that installation: the egress allowlist (`egress.allow`), the toolchains 1.1.2 baked into its image,\n"
                + "the language, and the Copilot enterprise URL (`copilot-cli.env`). The gateway systems moved to\n"
                + "`gateway/gateway.manifest.json` (schema " + Manifest.CURRENT_SCHEMA_VERSION + ") with their allowlists and secrets.\n");
        return pack;
    }

    // ---- gateway manifest schema 1 -> 2 ----

    private int migrateGateway() throws IOException {
        Path manifestFile = paths.gatewayManifest();
        if (!Files.isRegularFile(manifestFile)) return 0;
        JsonNode old = JSON.readTree(manifestFile.toFile());
        if (!old.has("adapters")) return ManifestIo.read(manifestFile).instances().size();
        List<Instance> instances = new ArrayList<>();
        Files.createDirectories(paths.gatewayAllowlists());
        Files.createDirectories(paths.gatewaySecrets());
        for (JsonNode a : old.path("adapters")) {
            String id = a.path("id").asText();
            String envBase = id.toUpperCase(Locale.ROOT).replace('-', '_');
            List<String> aliases = new ArrayList<>();
            a.path("hostAliases").forEach(n -> aliases.add(n.asText()));
            String secretEnv = a.path("read").path("secret").asText(envBase + "_TOKEN");
            String oldAllowlist = a.path("read").path("allowlist").asText(null);
            String newAllowlist = id + ".txt";
            if (oldAllowlist != null && Files.isRegularFile(paths.gatewayAllowlists().resolve(oldAllowlist)) && !oldAllowlist.equals(newAllowlist)) {
                Files.move(paths.gatewayAllowlists().resolve(oldAllowlist), paths.gatewayAllowlists().resolve(newAllowlist), StandardCopyOption.REPLACE_EXISTING);
            }
            String hostname = a.path("hostname").asText(null);
            Path secret = paths.gatewaySecrets().resolve(id + ".env");
            if (Files.isRegularFile(secret) && hostname != null) {
                String content = Files.readString(secret);
                if (!content.contains(envBase + "_BASE_URL=")) {
                    Files.writeString(secret, (content.endsWith("\n") || content.isEmpty() ? content : content + "\n") + envBase + "_BASE_URL=https://" + hostname + "\n");
                }
                try {
                    Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // non-POSIX
                }
            }
            instances.add(new Instance(id, id, a.path("label").asText(id), hostname, aliases, envBase + "_BASE_URL",
                    new SecretSpec(secretEnv, "bearer"), new AllowlistSpec(newAllowlist, null, id.equals("confluence") ? "pages/spaces" : id.equals("jira") ? "issues/projects" : "entries"),
                    List.of("read"), Map.of()));
        }
        ManifestIo.write(manifestFile, new Manifest(Manifest.CURRENT_SCHEMA_VERSION, old.path("ip_suffix").asInt(10), instances));
        return instances.size();
    }

    // ---- subnet register ----

    private Optional<String> migrateSubnets() throws IOException {
        String xdgCache = platform.env().get("XDG_CACHE_HOME");
        Path oldRegistry = (xdgCache != null && !xdgCache.isBlank() ? Path.of(xdgCache) : platform.home().resolve(".cache")).resolve("cplt-sndbx").resolve("subnets.txt");
        if (!Files.isRegularFile(oldRegistry)) return Optional.empty();
        Path newRegistry = platform.stateDir().resolve("subnets.txt");
        Files.createDirectories(newRegistry.getParent());
        List<String> existing = Files.isRegularFile(newRegistry) ? new ArrayList<>(Files.readAllLines(newRegistry)) : new ArrayList<>();
        Optional<String> mine = Optional.empty();
        for (String line : Files.readAllLines(oldRegistry)) {
            String[] parts = line.split("\t", 2);
            if (parts.length != 2 || parts[0].isBlank()) continue;
            if (Path.of(parts[1].strip()).toAbsolutePath().normalize().equals(root)) mine = Optional.of(parts[0].strip());
            if (existing.stream().noneMatch(e -> e.startsWith(parts[0] + "\t"))) existing.add(parts[0].strip() + "\t" + parts[1].strip());
        }
        Files.write(newRegistry, existing);
        return mine;
    }

    // ---- 3. conversations ----

    private int migrateConversations(SandboxConfig config) throws IOException {
        ConversationStore store = new ConversationStore(paths);
        int count = 0;
        Path oldDir = root.resolve("copilot-config").resolve("conversations");
        if (Files.isDirectory(oldDir)) {
            try (Stream<Path> files = Files.list(oldDir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    JsonNode n = JSON.readTree(f.toFile());
                    if (n.has("id") && !store.exists(n.path("id").asText())) {
                        store.save(convert(n, config));
                        count++;
                    }
                }
            }
        }
        Path master = platform.home().resolve(".agentsandbox-conversations").resolve("conversations.json");
        if (Files.isRegularFile(master)) {
            JsonNode n = JSON.readTree(master.toFile());
            for (JsonNode c : n.path("conversations")) {
                if (c.has("id") && !store.exists(c.path("id").asText())) {
                    store.save(convert(c, config));
                    count++;
                }
            }
        }
        return count;
    }

    private Conversation convert(JsonNode n, SandboxConfig config) {
        String id = n.path("id").asText();
        long created = n.path("createdAt").asLong(System.currentTimeMillis());
        long updated = n.path("updatedAt").asLong(created);
        List<String> repos = new ArrayList<>();
        n.path("repos").forEach(r -> repos.add(r.asText()));
        Map<String, Conversation.RepoBase> base = new LinkedHashMap<>();
        for (String repo : repos) {
            String sha = n.path("baselineShas").path(repo).asText(null);
            base.put(repo, new Conversation.RepoBase(null, null, sha, false, History.NONE, null));
        }
        List<Conversation.Delivery> deliveries = new ArrayList<>();
        for (JsonNode d : n.path("deliveries")) {
            Map<String, Conversation.DeliveryRepo> dr = new LinkedHashMap<>();
            List<String> delivered = new ArrayList<>();
            d.path("repos").forEach(r -> delivered.add(r.asText()));
            int files = d.path("files").asInt(0);
            for (String repo : delivered.isEmpty() ? repos : delivered) {
                dr.put(repo, new Conversation.DeliveryRepo(files, d.path("shas").path(repo).asText(null), null));
            }
            deliveries.add(new Conversation.Delivery(d.path("at").asLong(updated), d.path("branch").asText(null), d.path("message").asText(null), "commit", dr));
        }
        List<Conversation.Message> messages = new ArrayList<>();
        for (JsonNode m : n.path("messages")) {
            String role = m.path("role").asText("assistant");
            messages.add(new Conversation.Message(m.path("id").asText(java.util.UUID.randomUUID().toString()), role, m.path("timestamp").asLong(created),
                    m.path("content").asText(""), List.of(), List.of(), null, role.equals("assistant") ? "end_turn" : null));
        }
        String container = "lyngvi-" + paths.projectName() + "-" + id.substring(0, Math.min(8, id.length()));
        return new Conversation(2, id, n.path("title").asText(id), created, updated, updated, MIGRATED_AGENT, n.path("copilotSessionId").asText(null),
                n.path("model").asText(null), repos, config.resources(), container, base, deliveries, messages);
    }

    // ---- 4. sessions ----

    private int migrateSessions() throws IOException {
        Path sessions = paths.sessions();
        if (!Files.isDirectory(sessions)) return 0;
        Path outputFiles = root.resolve("copilot-output-files");
        int count = 0;
        try (Stream<Path> dirs = Files.list(sessions)) {
            for (Path s : dirs.filter(Files::isDirectory).sorted().toList()) {
                count++;
                Path out = s.resolve("out");
                Path patches = s.resolve("patches");
                if (Files.isDirectory(out) && !Files.exists(patches)) Files.move(out, patches);
                // The 1.1.2 layout, on the host: output/ and work/ are moved into the session's volume
                // the first time the session is used again (SessionService.volume), which is where they
                // belong now. Migration itself needs no Podman.
                Files.createDirectories(s.resolve("output"));
                Files.createDirectories(s.resolve("attachments"));
                Files.createDirectories(s.resolve("work"));
                if (Files.isDirectory(outputFiles)) {
                    try (Stream<Path> files = Files.walk(outputFiles)) {
                        for (Path f : files.filter(Files::isRegularFile).toList()) {
                            Path target = s.resolve("output").resolve(outputFiles.relativize(f).toString());
                            Files.createDirectories(target.getParent());
                            Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        }
        return count;
    }

    // ---- 5. copilot-config -> state/agents/copilot-cli ----

    private void migrateCopilotConfig() throws IOException {
        Path oldConfig = root.resolve("copilot-config");
        if (!Files.isDirectory(oldConfig)) return;
        Path target = paths.agentState(MIGRATED_AGENT);
        Files.createDirectories(target);
        try (Stream<Path> files = Files.walk(oldConfig)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path rel = oldConfig.relativize(f);
                if (rel.startsWith("conversations")) continue;
                Path t = target.resolve(rel.toString());
                Files.createDirectories(t.getParent());
                Files.move(f, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try (Stream<Path> files = Files.walk(oldConfig)) {
            for (Path f : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
        }
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX
        }
    }
}
