package no.syntakserror.lyngvi.core.api;

import java.util.Set;

/**
 * What {@code setup.sh clean} is allowed to remove.
 *
 * <p>Two defaults carry the safety of the whole command. {@code dryRun} is on unless the caller says
 * otherwise, so the first run of any invocation only ever prints. And without {@code olderThanDays}
 * nothing belonging to a conversation that still exists is touched at all — only strays, whose
 * conversation is gone and which therefore nothing can reach any more.
 *
 * @param kinds              which of {@code container}, {@code work}, {@code image}, {@code cache} to consider
 * @param olderThanDays      also take objects of conversations untouched for this long; 0 means strays only
 * @param includeUndelivered allow removing a working copy from a conversation that has never delivered
 *                           anything — that is the one case where unseen work can be lost, so it is never
 *                           implied by anything else
 * @param dryRun             report what would go, remove nothing
 */
public record CleanOptions(Set<String> kinds, int olderThanDays, boolean includeUndelivered, boolean dryRun) {

    public static final String CONTAINERS = "container";
    public static final String WORK = "work";
    public static final String IMAGES = "image";
    public static final String CACHE = "cache";
    public static final Set<String> ALL = Set.of(CONTAINERS, WORK, IMAGES, CACHE);

    /** Everything, reported only. */
    public static CleanOptions report() {
        return new CleanOptions(ALL, 0, false, true);
    }

    public CleanOptions {
        kinds = kinds == null || kinds.isEmpty() ? ALL : Set.copyOf(kinds);
        olderThanDays = Math.max(0, olderThanDays);
    }

    public boolean wants(String kind) {
        return kinds.contains(kind);
    }
}
