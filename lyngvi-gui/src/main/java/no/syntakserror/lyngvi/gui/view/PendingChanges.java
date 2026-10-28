package no.syntakserror.lyngvi.gui.view;

import no.syntakserror.lyngvi.core.contract.FileChange;
import no.syntakserror.lyngvi.core.contract.ReviewResult;

/**
 * What a conversation has waiting for delivery, counted for the strip above the composer.
 *
 * <p>Delivery is the point of the product, but until now nothing on the screen said what would be delivered: the
 * button was there whether eleven files were waiting or none. The counts come from the same review the deliver
 * dialog runs, so the strip and the dialog can never disagree.
 */
public record PendingChanges(int apply, int held, int additions, int deletions) {

    public static final PendingChanges NONE = new PendingChanges(0, 0, 0, 0);

    public static PendingChanges of(ReviewResult review) {
        if (review == null) return NONE;
        int apply = 0;
        int held = 0;
        int additions = 0;
        int deletions = 0;
        for (ReviewResult.RepoReview repo : review.repos().values()) {
            for (FileChange f : repo.files()) {
                if ("apply".equals(f.bucket())) apply++;
                else held++;
                additions += f.additions();
                deletions += f.deletions();
            }
        }
        return new PendingChanges(apply, held, additions, deletions);
    }

    public int files() {
        return apply + held;
    }

    public boolean isEmpty() {
        return files() == 0;
    }
}
