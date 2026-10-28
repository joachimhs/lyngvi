package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import no.syntakserror.lyngvi.core.contract.FileChange;
import no.syntakserror.lyngvi.core.contract.ReviewResult;

/**
 * The files of a review as «Deliver changes» lists them: one entry per file, with the repository it came from,
 * sorted into the three buckets, and the key that finds the file's own diff among the parsed patches. Pure, so the
 * rules are tested without a toolkit.
 */
final class DeliverFiles {

    static final String APPLY = "apply";
    static final String HOLD = "hold";
    static final String REJECT = "reject";

    private DeliverFiles() {}

    /** One file of the review; {@link #key()} is what {@link #index} files its diff under. */
    record Entry(String repo, FileChange file) {

        String path() {
            return file.path();
        }

        String key() {
            return DeliverFiles.key(repo, file.path());
        }

        /** The file name alone, for the first line of a row. */
        String name() {
            int slash = file.path().lastIndexOf('/');
            return slash < 0 ? file.path() : file.path().substring(slash + 1);
        }

        /** The repository and the directory within it, muted under the name. */
        String location() {
            int slash = file.path().lastIndexOf('/');
            return slash < 0 ? repo : repo + "/" + file.path().substring(0, slash);
        }
    }

    static String key(String repo, String path) {
        return repo + "/" + path;
    }

    /** The bucket the core put the file in; anything that is neither apply nor hold counts as rejected. */
    static String bucketOf(FileChange f) {
        return APPLY.equals(f.bucket()) ? APPLY : HOLD.equals(f.bucket()) ? HOLD : REJECT;
    }

    /** The files of one bucket, repositories in review order and files in patch order. */
    static List<Entry> bucket(ReviewResult r, String bucket) {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, ReviewResult.RepoReview> e : r.repos().entrySet()) {
            for (FileChange f : e.getValue().files()) {
                if (bucketOf(f).equals(bucket)) out.add(new Entry(e.getKey(), f));
            }
        }
        return out;
    }

    /** One repository's parsed patch, keyed the way the entries are, so a row finds its own file diff. */
    static Map<String, DiffPaneBuilder.FileDiff> index(String repo, List<DiffPaneBuilder.FileDiff> files) {
        Map<String, DiffPaneBuilder.FileDiff> out = new LinkedHashMap<>();
        for (DiffPaneBuilder.FileDiff f : files) out.put(key(repo, f.path()), f);
        return out;
    }

    /**
     * What a delivery would leave behind: the held files that are not ticked and the rejected ones, which never go
     * at all. {@code heldIn} are the held files the developer has ticked, and so are delivered after all.
     */
    record Excluded(int total, List<Entry> heldOut, List<Entry> heldIn, List<Entry> rejected) {

        int count() {
            return heldOut.size() + rejected.size();
        }

        boolean any() {
            return count() > 0;
        }
    }

    /** @param included the {@link Entry#key()}s ticked in the held bucket */
    static Excluded excluded(ReviewResult r, Set<String> included) {
        List<Entry> heldOut = new ArrayList<>();
        List<Entry> heldIn = new ArrayList<>();
        for (Entry e : bucket(r, HOLD)) (included.contains(e.key()) ? heldIn : heldOut).add(e);
        return new Excluded(r.totalFiles(), heldOut, heldIn, bucket(r, REJECT));
    }

    /**
     * What {@code ApplyOptions.includes} must list once a held file is ticked: the apply bucket <em>and</em> the
     * ticks together. The core reads an explicit list as the whole delivery rather than as an addition to the
     * bucket ({@code DeliveryService.select}), so sending the ticks alone would deliver those files and silently
     * drop everything else. Empty while nothing is ticked, which leaves the core its own default: the apply bucket.
     *
     * @param multiRepo paths are prefixed with their repository when the session has more than one, which is how
     *                  the core tells two repositories with the same path apart; with one it wants the bare path
     */
    static List<String> includes(ReviewResult r, Set<String> included, boolean multiRepo) {
        if (included.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Entry e : bucket(r, APPLY)) out.add(multiRepo ? e.key() : e.path());
        for (Entry e : bucket(r, HOLD)) {
            if (included.contains(e.key())) out.add(multiRepo ? e.key() : e.path());
        }
        return out;
    }

    /** Why those files are out, as {@code reason -> how many}: the commonest first, ties by name, blanks dropped. */
    static Map<String, Integer> reasons(List<Entry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entry e : entries) {
            String reason = e.file().reason();
            if (reason != null && !reason.isBlank()) counts.merge(reason, 1, Integer::sum);
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /**
     * How a file that stays behind is named where it is listed one by one — «repo/path — why».
     *
     * @param reasonText turns the core's reason word into the sentence the user reads
     */
    static String line(Entry e, UnaryOperator<String> reasonText) {
        String why = reasonText.apply(e.file().reason());
        return why == null || why.isBlank() ? e.key() : e.key() + " — " + why;
    }
}
