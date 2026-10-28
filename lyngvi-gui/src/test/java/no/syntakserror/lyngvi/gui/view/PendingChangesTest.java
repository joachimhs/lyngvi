package no.syntakserror.lyngvi.gui.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.contract.FileChange;
import no.syntakserror.lyngvi.core.contract.ReviewResult;
import org.junit.jupiter.api.Test;

/** What the strip above the composer counts: everything the deliver dialog would show, summed per bucket. */
class PendingChangesTest {

    private static FileChange file(String path, String bucket, int additions, int deletions) {
        return new FileChange(path, null, "modified", bucket, "hold".equals(bucket) ? "EXECUTABLE" : null,
                additions, deletions, false, null, false);
    }

    private static ReviewResult review(Map<String, List<FileChange>> repos) {
        return new ReviewResult(new ReviewResult.From("base", null, "base"),
                repos.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> new ReviewResult.RepoReview("patch", "abc", null, e.getValue()))));
    }

    @Test
    void everythingNotInApplyCountsAsHeld() {
        PendingChanges p = PendingChanges.of(review(Map.of("api", List.of(
                file("A.java", "apply", 10, 2),
                file("B.java", "apply", 5, 0),
                file("ci.yml", "hold", 1, 1),
                file("evil", "reject", 0, 9)))));

        assertThat(p.apply()).isEqualTo(2);
        assertThat(p.held()).isEqualTo(2);
        assertThat(p.files()).isEqualTo(4);
        assertThat(p.additions()).isEqualTo(16);
        assertThat(p.deletions()).isEqualTo(12);
        assertThat(p.isEmpty()).isFalse();
    }

    @Test
    void severalRepositoriesAreSummedTogether() {
        PendingChanges p = PendingChanges.of(review(Map.of(
                "api", List.of(file("A.java", "apply", 3, 1)),
                "web", List.of(file("b.ts", "apply", 4, 2)))));

        assertThat(p.files()).isEqualTo(2);
        assertThat(p.additions()).isEqualTo(7);
        assertThat(p.deletions()).isEqualTo(3);
    }

    @Test
    void nothingToDeliverIsEmpty() {
        assertThat(PendingChanges.of(null)).isEqualTo(PendingChanges.NONE);
        assertThat(PendingChanges.of(review(Map.of())).isEmpty()).isTrue();
        assertThat(PendingChanges.of(review(Map.of("api", List.of()))).isEmpty()).isTrue();
        assertThat(PendingChanges.NONE.files()).isZero();
    }
}
