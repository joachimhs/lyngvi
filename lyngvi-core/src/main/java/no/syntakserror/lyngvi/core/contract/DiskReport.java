package no.syntakserror.lyngvi.core.contract;

import java.util.List;

/**
 * What this sandbox has on disk, and how much of it can be reclaimed ({@code setup.sh clean},
 * {@code doctor}'s {@code disk} check).
 *
 * <p>Sizes are best effort. They come from {@code podman system df}, whose output has changed shape
 * between versions, so an entry whose size could not be read reports {@link #UNKNOWN_SIZE} rather than
 * zero — a cleanup that cannot say how much it frees is still worth offering, but it must not claim
 * «0 B» for something that holds a gigabyte.
 */
public record DiskReport(List<Entry> entries) {

    /** The size of an entry Podman did not tell us the size of. */
    public static final long UNKNOWN_SIZE = -1;

    /**
     * One object this sandbox owns.
     *
     * @param kind        {@code container}, {@code volume}, {@code workdir}, {@code image} or {@code cache}
     * @param name        the container name, volume name, image reference or host path
     * @param sessionId   the conversation it belongs to; null for images, caches and strays
     * @param bytes       its size, or {@link #UNKNOWN_SIZE}
     * @param reclaimable whether {@code clean} would remove it with the options it was asked for
     * @param reason      why, as a code the front-ends translate: {@code orphan}, {@code idle},
     *                    {@code finished}, {@code old-version}, {@code cache}, or {@code live} when it
     *                    is in use and nothing will touch it
     */
    public record Entry(String kind, String name, String sessionId, long bytes, boolean reclaimable, String reason) {}

    public DiskReport {
        entries = List.copyOf(entries);
    }

    public List<Entry> reclaimable() {
        return entries.stream().filter(Entry::reclaimable).toList();
    }

    /** Total of the entries whose size is known; unknown ones are left out rather than counted as nothing. */
    public long totalBytes() {
        return entries.stream().mapToLong(Entry::bytes).filter(b -> b >= 0).sum();
    }

    public long reclaimableBytes() {
        return reclaimable().stream().mapToLong(Entry::bytes).filter(b -> b >= 0).sum();
    }

    /** True when at least one size could not be read, so a front-end can say the total is a lower bound. */
    public boolean sizesIncomplete() {
        return entries.stream().anyMatch(e -> e.bytes() < 0);
    }
}
