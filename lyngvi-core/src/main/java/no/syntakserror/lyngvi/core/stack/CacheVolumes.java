package no.syntakserror.lyngvi.core.stack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.config.Profile;

/**
 * The tool caches sessions of one sandbox may share (spec §5.3).
 *
 * <p>Every session starts with an empty dependency directory — the copy filter leaves {@code node_modules},
 * {@code target} and the rest behind on purpose — so each one downloads the same artefacts again into its own
 * container. Measured on a real sandbox that is 60–330 MB per session. A named volume per cache directory
 * takes that cost once instead of once per session, and the number of volumes stays fixed however many
 * sessions there are.
 *
 * <p>The ids are a <strong>closed set</strong>: a profile picks from this table and can never name a path of
 * its own, so no configuration can mount something that is not a cache. Two rules are baked into the table
 * and must stay: the Maven entry is {@code ~/.m2/repository} and not {@code ~/.m2}, because the entrypoint
 * writes {@code settings.xml} there only when it is missing and a shared one would freeze a stale mirror for
 * every later session; and the Gradle entries are {@code caches} and {@code wrapper/dists}, never the whole
 * of {@code ~/.gradle}, which holds the daemon's state and its locks.
 *
 * <p>Sharing is not free of risk, and the ecosystems differ. Go and npm verify what they read (content
 * addressing, {@code go.sum}, integrity hashes) and are built for concurrent use; Maven does neither — a jar
 * already in the local repository is used as it is, and the default resolver takes no lock across processes.
 * That is why {@link #SAFE_DEFAULTS} holds only the first kind, and why {@code readonly} exists.
 */
public final class CacheVolumes {

    /** One shareable cache: an id a profile can name, the directory it is mounted at, and its toolchains. */
    public record Cache(String id, String path, List<String> toolchains) {}

    private static final List<Cache> TABLE = List.of(
            new Cache("npm", "/home/agent/.npm", List.of("node22")),
            new Cache("pip", "/home/agent/.cache/pip", List.of("python312")),
            new Cache("go-mod", "/home/agent/go/pkg/mod", List.of("go123")),
            new Cache("go-build", "/home/agent/.cache/go-build", List.of("go123")),
            new Cache("nuget", "/home/agent/.nuget/packages", List.of("dotnet8")),
            new Cache("m2", "/home/agent/.m2/repository", List.of("maven")),
            new Cache("gradle", "/home/agent/.gradle/caches", List.of("gradle")),
            new Cache("gradle-dists", "/home/agent/.gradle/wrapper/dists", List.of("gradle")));

    /**
     * What a new sandbox shares: the caches that verify what they read and tolerate several writers.
     * {@code m2}, {@code gradle} and {@code gradle-dists} are in the table but not here — turn them on
     * deliberately, and see the note above before you do.
     */
    public static final List<String> SAFE_DEFAULTS = List.of("npm", "pip", "go-mod", "go-build");

    private CacheVolumes() {}

    public static List<Cache> all() {
        return TABLE;
    }

    public static boolean isKnown(String id) {
        return TABLE.stream().anyMatch(c -> c.id().equals(id));
    }

    /** The volume name for one cache of one sandbox; shared by every session in it. */
    public static String volumeName(String project, String id) {
        return "lyngvi-" + project + "-cache-" + id;
    }

    /** True for a volume this class would have made, so cleanup can tell caches from session volumes. */
    public static boolean isCacheVolume(String project, String volume) {
        return volume.startsWith("lyngvi-" + project + "-cache-");
    }

    /**
     * The caches to mount: those the profile names, that this image actually has a toolchain for. A
     * Node-only sandbox gets one volume, not eight empty ones.
     */
    public static List<Cache> selected(Profile profile) {
        List<Cache> out = new ArrayList<>();
        if (!profile.cache().enabled()) return out;
        for (Cache cache : TABLE) {
            if (!profile.cache().paths().contains(cache.id())) continue;
            if (cache.toolchains().stream().noneMatch(t -> profile.toolchains().contains(t))) continue;
            out.add(cache);
        }
        return out;
    }

    /**
     * The {@code -v} arguments for a session container. {@code readonly} adds Podman's overlay flag, so the
     * session reads the whole cache while its own writes go to a layer that is discarded when the container
     * stops — nothing it downloads, and nothing it tampers with, outlives it.
     */
    public static List<String> mounts(Profile profile, String project) {
        List<String> args = new ArrayList<>();
        String suffix = profile.cache().mode().equals(Profile.Cache.READONLY) ? ":O" : ":rw";
        for (Cache cache : selected(profile)) {
            args.add("-v");
            args.add(volumeName(project, cache.id()) + ":" + cache.path() + suffix);
        }
        return args;
    }

    /** Volume name to cache id for everything this sandbox would use; cleanup and doctor list these. */
    public static Map<String, String> names(Profile profile, String project) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Cache cache : selected(profile)) out.put(volumeName(project, cache.id()), cache.id());
        return out;
    }
}
