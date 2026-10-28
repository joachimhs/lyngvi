package no.syntakserror.lyngvi.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The organisation profile ({@code profile.json}, spec §3.1).
 *
 * <p>Everything that makes an installation belong to one organisation lives
 * here; the code base itself carries no such values. Unknown fields are
 * rejected by {@link ProfileLoader}.
 */
public record Profile(
        int schemaVersion,
        String name,
        String label,
        String language,
        Agents agents,
        List<String> toolchains,
        Egress egress,
        LocalModels localModels,
        Mirrors mirrors,
        Ca ca,
        Gateway gateway,
        Git git,
        ResourceLimits resources,
        List<ResourcePreset> resourcePresets,
        Copy copy,
        Session session,
        Cache cache) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /**
     * A profile written before {@code localModels}, {@code resourcePresets} or {@code cache} existed keeps
     * the behaviour it had: their defaults. For {@code cache} that is «no shared caches», which is what
     * every sandbox did before M13.
     */
    public Profile {
        localModels = localModels == null ? LocalModels.DEFAULT : localModels;
        resourcePresets = resourcePresets == null ? ResourcePreset.BUILT_IN : List.copyOf(resourcePresets);
        cache = cache == null ? Cache.OFF : cache;
    }

    public record Agents(@JsonProperty("default") String defaultAgent, List<String> enabled) {}

    /**
     * The one host outside the allowlist a container may reach: a model server on the developer's own machine
     * (Ollama, LM Studio, an MLX server such as oMLX behind the LM Studio provider). Squid denies every private
     * address, so such a server is reachable only on the ports named here — an empty list closes it off.
     */
    public record LocalModels(String host, List<Integer> ports) {

        private static final String DEFAULT_HOST = "host.containers.internal";
        /** Ollama and LM Studio defaults; an MLX server on another port is added in the profile. */
        private static final List<Integer> DEFAULT_PORTS = List.of(11434, 1234);

        public static final LocalModels DEFAULT = new LocalModels(DEFAULT_HOST, DEFAULT_PORTS);

        public LocalModels {
            host = host == null || host.isBlank() ? DEFAULT_HOST : host.strip();
            ports = ports == null ? DEFAULT_PORTS : List.copyOf(ports);
        }

        public boolean enabled() {
            return !ports.isEmpty();
        }
    }

    /**
     * Egress policy. {@code block} is either the literal {@code "bundled"} (the
     * blocklist that ships with the product) or an explicit list that replaces it.
     */
    public record Egress(List<String> allow, Object block, List<String> blockExtra) {

        public boolean usesBundledBlocklist() {
            return "bundled".equals(block);
        }

        /** The explicit blocklist; empty when the bundled one is used. */
        @SuppressWarnings("unchecked")
        public List<String> blockList() {
            return block instanceof List<?> list ? (List<String>) list : List.of();
        }
    }

    public record Mirrors(String npm, String maven, String pypi) {}

    public record Ca(String certsDir, List<String> macKeychainNames) {}

    public record Gateway(String manifest, String plugins, List<String> allowWrite) {}

    public record Git(List<String> protectedBranches, String branchPrefix, Committer committer) {
        public record Committer(String name, String email) {}
    }

    public record Copy(History history, int depth, List<String> excludes, int maxFileMiB) {}

    /**
     * @param idleStopMinutes stop a session's container after this long without use; 0 never stops one
     * @param idleRemoveHours <em>remove</em> it after this long; 0 never removes one. Safe because a session
     *     container holds nothing that cannot be made again — the work is in the volume, the credentials in
     *     {@code state/agents/}, the conversation in its file — but it does throw away the tool caches the
     *     container accumulated, so it belongs together with {@code cache} below.
     */
    public record Session(int idleStopMinutes, int idleRemoveHours) {}

    /**
     * Tool caches shared between the sessions of this sandbox (spec §5.3). The ids are a closed set
     * ({@code CacheVolumes}); a profile names which of them to share, never a path of its own.
     *
     * @param mode {@code off} (every session downloads its own), {@code shared} (one read-write volume per
     *     cache) or {@code readonly} (the same volume behind an overlay: the session reads all of it and its
     *     own downloads are discarded when the container stops)
     */
    public record Cache(String mode, List<String> paths) {

        public static final String OFF_MODE = "off";
        public static final String SHARED = "shared";
        public static final String READONLY = "readonly";
        public static final List<String> MODES = List.of(OFF_MODE, SHARED, READONLY);

        public static final Cache OFF = new Cache(OFF_MODE, List.of());

        public Cache {
            mode = mode == null || mode.isBlank() ? OFF_MODE : mode.strip();
            paths = paths == null ? List.of() : List.copyOf(paths);
        }

        public boolean enabled() {
            return !mode.equals(OFF_MODE) && !paths.isEmpty();
        }
    }
}
