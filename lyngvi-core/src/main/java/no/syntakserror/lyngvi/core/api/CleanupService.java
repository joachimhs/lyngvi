package no.syntakserror.lyngvi.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import no.syntakserror.lyngvi.core.contract.DiskReport;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.session.ConversationStore;
import no.syntakserror.lyngvi.core.stack.CacheVolumes;
import no.syntakserror.lyngvi.core.stack.DiskSizes;
import no.syntakserror.lyngvi.core.stack.Podman;
import no.syntakserror.lyngvi.core.stack.SessionVolume;
import no.syntakserror.lyngvi.core.version.Version;

/**
 * What this sandbox has left lying about, and removing it ({@code setup.sh clean}, {@code doctor}'s
 * {@code disk} check).
 *
 * <p>Three rules hold the whole thing up:
 *
 * <ul>
 *   <li><b>Only our own.</b> Containers and volumes are matched on the {@code lyngvi.project} label this
 *       sandbox writes, images on {@code localhost/lyngvi-}. The machine is shared with everything else
 *       the user does with Podman, so nothing here resembles a {@code podman system prune}.
 *   <li><b>Nothing live.</b> A running or busy session is never touched, whatever the options say.
 *   <li><b>Work is not rubbish.</b> A working copy is the only place changes that were never delivered
 *       exist. It goes only when its conversation is gone, or when the conversation is old <em>and</em>
 *       has delivered something — unless the caller says {@code includeUndelivered} in so many words.
 * </ul>
 */
public final class CleanupService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a {@code clean} did: the names it removed, and the bytes it freed where those were known. */
    public record CleanResult(List<String> removed, long freedBytes, boolean dryRun) {}

    private final Sandbox sandbox;
    private final ConversationStore store;
    private final Podman podman;

    CleanupService(Sandbox sandbox) {
        this.sandbox = sandbox;
        this.store = new ConversationStore(sandbox.paths());
        this.podman = new Podman(sandbox.runner(), sandbox.paths().root());
    }

    public DiskReport report(CleanOptions options) {
        DiskSizes sizes = DiskSizes.read(sandbox.runner(), sandbox.paths().root());
        Map<String, Conversation> byId = new HashMap<>();
        for (Conversation c : store.list()) byId.put(c.id(), c);
        List<DiskReport.Entry> entries = new ArrayList<>();
        containers(options, sizes, byId, entries);
        work(options, sizes, byId, entries);
        images(options, entries);
        caches(options, sizes, entries);
        return new DiskReport(entries);
    }

    /** Session containers: strays always, idle ones when an age is given, never the live. */
    private void containers(CleanOptions options, DiskSizes sizes, Map<String, Conversation> byId, List<DiskReport.Entry> out) {
        for (Podman.Container ct : podman.ps(prefix())) {
            if (!ct.name().startsWith(prefix())) continue;  // podman's name filter is a regex; be exact
            Conversation c = byId.values().stream().filter(x -> x.container().equals(ct.name())).findFirst().orElse(null);
            long bytes = sizes.container(ct.name());
            String reason = reasonFor(c, ct.state(), options);
            boolean reclaimable = options.wants(CleanOptions.CONTAINERS) && !reason.equals("live");
            out.add(new DiskReport.Entry("container", ct.name(), c == null ? null : c.id(), bytes, reclaimable, reason));
        }
    }

    private String reasonFor(Conversation c, String state, CleanOptions options) {
        if (c == null) return "orphan";
        if (state.equals("running") || sandbox.sessionService().isBusy(c.id())) return "live";
        return olderThan(c, options.olderThanDays()) ? "idle" : "live";
    }

    /**
     * Working copies in both layouts: the session volume, and the {@code .sessions/<id>/work} directory a
     * session made before the volumes existed still has on the host.
     */
    private void work(CleanOptions options, DiskSizes sizes, Map<String, Conversation> byId, List<DiskReport.Entry> out) {
        Map<String, String> volumes = sandbox.sessionVolumes().list();
        Set<String> seen = new HashSet<>();
        volumes.forEach((volume, sessionId) -> {
            seen.add(sessionId);
            Conversation c = byId.get(sessionId);
            String reason = workReason(c, options);
            out.add(new DiskReport.Entry("volume", volume, sessionId, sizes.volume(volume),
                    options.wants(CleanOptions.WORK) && !reason.equals("live"), reason));
        });
        for (Path dir : hostWorkDirs()) {
            String sessionId = dir.getParent().getFileName().toString();
            if (seen.contains(sessionId)) continue;  // already migrated; the volume above is the real one
            Conversation c = byId.get(sessionId);
            String reason = workReason(c, options);
            out.add(new DiskReport.Entry("workdir", dir.toString(), sessionId, bytesOf(dir),
                    options.wants(CleanOptions.WORK) && !reason.equals("live"), reason));
        }
    }

    /**
     * Whether a working copy may go. «Finished» means the conversation is old enough <em>and</em> has
     * delivered something, so the human has already had what they asked for; everything else stays unless
     * the caller takes responsibility with {@code includeUndelivered}.
     */
    private String workReason(Conversation c, CleanOptions options) {
        if (c == null) return "orphan";
        if (sandbox.sessionService().isBusy(c.id())) return "live";
        if (!olderThan(c, options.olderThanDays())) return "live";
        if (!c.deliveries().isEmpty()) return "finished";
        return options.includeUndelivered() ? "undelivered" : "live";
    }

    private boolean olderThan(Conversation c, int days) {
        if (days <= 0) return false;
        return c.lastActiveAt() < System.currentTimeMillis() - days * 86_400_000L;
    }

    /** Images of this product tagged with anything but the version in use, that no container runs. */
    private void images(CleanOptions options, List<DiskReport.Entry> out) {
        String current = ":" + Version.current();
        for (Map.Entry<String, Long> image : ourImages().entrySet()) {
            if (image.getKey().endsWith(current)) continue;
            out.add(new DiskReport.Entry("image", image.getKey(), null, image.getValue(),
                    options.wants(CleanOptions.IMAGES), "old-version"));
        }
    }

    private Map<String, Long> ourImages() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        Runner.Result r;
        try {
            r = sandbox.runner().run(List.of("podman", "images", "--filter", "reference=localhost/lyngvi-*",
                    "--format", "json"), sandbox.paths().root(), SessionVolume.SHORT);
        } catch (SandboxException e) {
            return out;
        }
        if (!r.success() || r.stdout().isBlank()) return out;
        try {
            for (JsonNode node : JSON.readTree(r.stdout())) {
                long size = node.path("Size").isNumber() ? node.path("Size").asLong() : DiskReport.UNKNOWN_SIZE;
                JsonNode names = node.path("Names");
                if (!names.isArray()) continue;
                for (JsonNode name : names) {
                    if (name.asText().startsWith("localhost/lyngvi-")) out.put(name.asText(), size);
                }
            }
        } catch (IOException ignored) {
            // unparseable: no images reported rather than a wrong list
        }
        return out;
    }

    /** The shared tool caches. Everything in them can be downloaded again, so they are always fair game. */
    private void caches(CleanOptions options, DiskSizes sizes, List<DiskReport.Entry> out) {
        for (String volume : cacheVolumes()) {
            out.add(new DiskReport.Entry("cache", volume, null, sizes.volume(volume),
                    options.wants(CleanOptions.CACHE), "cache"));
        }
    }

    private List<String> cacheVolumes() {
        List<String> out = new ArrayList<>();
        Runner.Result r;
        try {
            r = sandbox.runner().run(List.of("podman", "volume", "ls", "--filter",
                    "label=lyngvi.project=" + sandbox.paths().projectName(), "--format", "{{.Name}}"),
                    sandbox.paths().root(), SessionVolume.SHORT);
        } catch (SandboxException e) {
            return out;
        }
        if (!r.success()) return out;
        for (String line : r.stdout().split("\n")) {
            String name = line.strip();
            if (CacheVolumes.isCacheVolume(sandbox.paths().projectName(), name)) out.add(name);
        }
        return out;
    }

    /** Every {@code .sessions/<id>/work} still on the host; empty once every session has been migrated. */
    private List<Path> hostWorkDirs() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(sandbox.paths().sessions())) return out;
        try (Stream<Path> dirs = Files.list(sandbox.paths().sessions())) {
            for (Path session : dirs.sorted().toList()) {
                Path work = session.resolve("work");
                if (Files.isDirectory(work)) out.add(work);
            }
        } catch (IOException ignored) {
            // unreadable: report what we have
        }
        return out;
    }

    private static long bytesOf(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return DiskReport.UNKNOWN_SIZE;
        }
    }

    /**
     * Removes what {@link #report} marks reclaimable. The container of a session goes before its volume,
     * and a session that turns out to be busy by the time we get to it is skipped.
     */
    public CleanResult clean(CleanOptions options) {
        DiskReport report = report(options);
        List<String> removed = new ArrayList<>();
        long freed = 0;
        if (options.dryRun()) {
            return new CleanResult(report.reclaimable().stream().map(DiskReport.Entry::name).toList(),
                    report.reclaimableBytes(), true);
        }
        for (DiskReport.Entry entry : report.reclaimable()) {
            if (!entry.kind().equals("container")) continue;
            podman.removeForce(entry.name());
            removed.add(entry.name());
            freed += Math.max(0, entry.bytes());
        }
        for (DiskReport.Entry entry : report.reclaimable()) {
            switch (entry.kind()) {
                case "volume", "cache" -> {
                    sandbox.sessionVolumes().removeByName(entry.name());
                    removed.add(entry.name());
                    freed += Math.max(0, entry.bytes());
                }
                case "workdir" -> {
                    SessionVolume.deleteTemp(Path.of(entry.name()));
                    removed.add(entry.name());
                    freed += Math.max(0, entry.bytes());
                }
                case "image" -> {
                    removeImage(entry.name());
                    removed.add(entry.name());
                    freed += Math.max(0, entry.bytes());
                }
                default -> { }
            }
        }
        return new CleanResult(removed, freed, false);
    }

    private void removeImage(String reference) {
        if (!reference.startsWith("localhost/lyngvi-")) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Not an image of this product: " + reference,
                    Map.of("image", reference));
        }
        try {
            sandbox.runner().run(List.of("podman", "rmi", reference), sandbox.paths().root(), SessionVolume.SHORT);
        } catch (SandboxException ignored) {
            // an image another container still uses: left alone, and said so by the next report
        }
    }

    private String prefix() {
        return "lyngvi-" + sandbox.paths().projectName() + "-";
    }
}
