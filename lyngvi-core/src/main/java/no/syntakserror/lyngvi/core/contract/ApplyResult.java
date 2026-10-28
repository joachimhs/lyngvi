package no.syntakserror.lyngvi.core.contract;

import java.util.List;
import java.util.Map;

/** Result of {@code session apply}. */
public record ApplyResult(int deliveryIndex, Map<String, RepoOutcome> repos, List<String> notices) {

    /**
     * @param hostCommit  the commit made on the host branch; null in {@code stage} mode or when conflicts were left to resolve
     * @param copyCommit  the commit in the working copy that records the delivery
     * @param conflicts   files left with conflict markers after a three-way apply
     */
    public record RepoOutcome(String branch, String hostCommit, String copyCommit, int files, boolean stashed,
                              boolean stashPopConflict, boolean threeWay, List<String> conflicts) {}
}
