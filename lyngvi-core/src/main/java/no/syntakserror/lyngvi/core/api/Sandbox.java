package no.syntakserror.lyngvi.core.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.config.DotEnv;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.config.Profile;
import no.syntakserror.lyngvi.core.config.ProfileLoader;
import no.syntakserror.lyngvi.core.config.RepoEntry;
import no.syntakserror.lyngvi.core.config.SandboxConfig;
import no.syntakserror.lyngvi.core.config.SandboxConfigFile;
import no.syntakserror.lyngvi.core.contract.InitReport;
import no.syntakserror.lyngvi.core.contract.ProfileInfo;
import no.syntakserror.lyngvi.core.contract.RepoInfo;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.ProcessRunner;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.session.Locks;
import no.syntakserror.lyngvi.core.template.Templates;

/**
 * An opened sandbox directory: its paths, configuration and profile, plus the
 * services front-ends call. Both the CLI and the GUI start here.
 */
public final class Sandbox {

    private final Paths paths;
    private final Platform platform;
    private final Runner runner;
    private volatile SandboxConfig config;
    private volatile Profile profile;
    private volatile Messages messages;

    private Sandbox(Paths paths, Platform platform, Runner runner) {
        this.paths = paths;
        this.platform = platform;
        this.runner = runner;
        reload();
    }

    /** Opens an initialised sandbox with the real platform and process runner. */
    public static Sandbox open(Path dir) {
        Platform platform = Platform.current();
        return open(dir, platform, new ProcessRunner(platform));
    }

    public static Sandbox open(Path dir, Platform platform, Runner runner) {
        Paths paths = Paths.of(dir);
        if (SandboxConfigFile.needsMigration(paths.sandboxConfig())) {
            throw new SandboxException(ErrorCode.NOT_INITIALIZED,
                    paths.root() + " is a 1.1.2 sandbox; run 'setup.sh migrate' to convert it",
                    Map.of("dir", paths.root().toString(), "code", "MIGRATION_REQUIRED", "schemaVersion", 1));
        }
        if (!Files.isRegularFile(paths.sandboxConfig()) || !Files.isRegularFile(paths.profileJson())) {
            throw new SandboxException(ErrorCode.NOT_INITIALIZED,
                    "No sandbox in " + paths.root() + " (" + (Files.isRegularFile(paths.sandboxConfig()) ? "profile.json" : "sandbox.config")
                            + " is missing); run 'setup.sh init' there, or point at the sandbox with --sandbox <dir> or LYNGVI_HOME",
                    Map.of("dir", paths.root().toString(), "hint", "run the sandbox's own setup.sh, or pass --sandbox <dir>"));
        }
        return new Sandbox(paths, platform, runner);
    }

    /** Initialises a sandbox directory (spec §3, §4.3 {@code init}). */
    public static InitReport init(Path dir, InitOptions options) {
        Platform platform = Platform.current();
        return init(dir, options, platform, new ProcessRunner(platform));
    }

    public static InitReport init(Path dir, InitOptions options, Platform platform, Runner runner) {
        Paths paths = Paths.of(dir);
        if (Files.exists(paths.sandboxConfig()) && !options.force()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "A sandbox already exists in " + paths.root() + "; use --yes to overwrite its configuration",
                    Map.of("dir", paths.root().toString()));
        }
        try {
            Files.createDirectories(paths.root());
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot create " + paths.root() + ": " + e.getMessage(), e);
        }
        List<Path> written = Templates.writeAll(paths.root());
        try {
            Files.writeString(paths.root().resolve("VERSION"), no.syntakserror.lyngvi.core.version.Version.current() + "\n");
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot write VERSION: " + e.getMessage(), e);
        }

        Path pack = options.profilePack().map(p -> p.toAbsolutePath().normalize()).orElse(paths.defaultProfilePack());
        Profile profile = ProfileLoader.load(pack.resolve("profile.json"));
        copy(pack.resolve("profile.json"), paths.profileJson());

        String agent = options.agent().orElse(profile.agents().defaultAgent());
        if (!profile.agents().enabled().contains(agent)) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "Agent '" + agent + "' is not enabled in profile '" + profile.name() + "' (enabled: "
                            + String.join(", ", profile.agents().enabled()) + ")",
                    Map.of("agent", agent));
        }
        List<RepoEntry> repos = new ArrayList<>();
        for (Path repoPath : options.repos()) {
            repos.add(newRepoEntry(repoPath, Optional.empty(), Optional.empty(), repos));
        }
        SandboxConfig config = new SandboxConfig(
                SandboxConfig.CURRENT_SCHEMA_VERSION, platform.uid(), platform.gid(), pack, Optional.empty(),
                profile.resources(), agent, profile.language(), repos);
        SandboxConfigFile.write(paths.sandboxConfig(), config);
        DotEnv.ensure(paths.dotEnv(), paths.dotEnvExample());
        mkdirs(paths.conversations(), paths.agentsState(), paths.locks(), paths.sessions(), paths.skills());
        // The skills Lyngvi ships, laid down once; from here on the folder belongs to this sandbox (spec §3.6).
        new no.syntakserror.lyngvi.core.skills.SkillStore(paths).installStarters();
        if (!Files.exists(paths.gatewayManifest())) {
            no.syntakserror.lyngvi.core.gateway.ManifestIo.write(paths.gatewayManifest(), no.syntakserror.lyngvi.gateway.api.Manifest.empty());
        }

        return new InitReport(paths.root().toString(), profileInfo(profile, pack),
                repos.stream().map(RepoInfo::of).toList(), written.size());
    }

    /** Re-reads configuration and profile from disk. */
    public void reload() {
        this.config = SandboxConfigFile.read(paths.sandboxConfig());
        this.profile = ProfileLoader.load(paths.profileJson());
        this.messages = Messages.forLanguage(config.language());
    }

    public Paths paths() { return paths; }
    public Platform platform() { return platform; }
    public Runner runner() { return runner; }
    public SandboxConfig config() { return config; }
    public Profile profile() { return profile; }
    public Messages messages() { return messages; }

    /** Opens the volume a session's files live in; see {@link no.syntakserror.lyngvi.core.stack.SessionVolume}. */
    public no.syntakserror.lyngvi.core.stack.SessionVolumes sessionVolumes() {
        return new no.syntakserror.lyngvi.core.stack.SessionVolumes(runner, paths.root(), paths.temp(), paths.projectName(),
                StackService.baseImage(), config.uid(), config.gid());
    }

    public SandboxService sandboxService() { return new SandboxService(this); }
    public CleanupService cleanupService() { return new CleanupService(this); }
    public ConfigService configService() { return new ConfigService(this); }
    public StackService stackService() { return new StackService(this); }
    public DomainService domainService() { return new DomainService(this); }
    public SessionService sessionService() { return new SessionService(this); }
    public AgentService agentService() { return new AgentService(this); }
    public DeliveryService deliveryService() { return new DeliveryService(this); }
    public GatewayService gatewayService() { return new GatewayService(this); }
    public SkillService skillService() { return new SkillService(this); }

    /** Replaces {@code sandbox.config} under the sandbox lock and reloads. */
    void updateConfig(SandboxConfig updated) {
        try (Locks.Held ignored = Locks.sandbox(paths)) {
            SandboxConfigFile.write(paths.sandboxConfig(), updated);
        }
        reload();
    }

    /** As {@link #updateConfig} for callers that already hold the sandbox lock (locks are re-entrant). */
    public void updateConfigLocked(SandboxConfig updated) {
        updateConfig(updated);
    }

    ProfileInfo profileInfo() {
        return profileInfo(profile, config.profilePack());
    }

    static ProfileInfo profileInfo(Profile profile, Path pack) {
        return new ProfileInfo(profile.name(), profile.label(), pack.toString());
    }

    /** Validates and builds a repository entry that does not clash with {@code existing}. */
    static RepoEntry newRepoEntry(Path path, Optional<String> name, Optional<History> history,
                                  List<RepoEntry> existing) {
        Path abs = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Repository path is not a directory: " + abs,
                    Map.of("path", abs.toString()));
        }
        RepoEntry entry;
        try {
            entry = new RepoEntry(abs, name.orElse(abs.getFileName().toString()), history);
        } catch (IllegalArgumentException e) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, e.getMessage(), Map.of("path", abs.toString()));
        }
        for (RepoEntry other : existing) {
            if (other.path().equals(abs)) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, abs + " is already registered as '" + other.name() + "'",
                        Map.of("path", abs.toString(), "name", other.name()));
            }
            if (other.name().equals(entry.name())) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                        "Repository name '" + entry.name() + "' is already used by " + other.path(),
                        Map.of("name", entry.name()));
            }
        }
        return entry;
    }

    private static void copy(Path from, Path to) {
        try {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot copy " + from + " to " + to + ": " + e.getMessage(), e);
        }
    }

    private static void mkdirs(Path... dirs) {
        for (Path d : dirs) {
            try {
                Files.createDirectories(d);
            } catch (IOException e) {
                throw new SandboxException(ErrorCode.INTERNAL, "Cannot create " + d + ": " + e.getMessage(), e);
            }
        }
    }
}
