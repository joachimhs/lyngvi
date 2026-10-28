package no.syntakserror.lyngvi.gui.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.syntakserror.lyngvi.core.contract.FileChange;
import no.syntakserror.lyngvi.core.contract.ReviewResult;
import org.junit.jupiter.api.Test;

/** What «Deliver changes» lists, and how a row finds the diff of its own file. */
class DeliverFilesTest {

    private static FileChange file(String path, String bucket) {
        return file(path, bucket, null);
    }

    private static FileChange file(String path, String bucket, String reason) {
        return new FileChange(path, null, "modified", bucket, reason, 1, 0, false, null, false);
    }

    private static ReviewResult review(Map<String, List<FileChange>> repos) {
        Map<String, ReviewResult.RepoReview> out = new LinkedHashMap<>();
        repos.forEach((repo, files) -> out.put(repo, new ReviewResult.RepoReview("patch", "abc", null, files)));
        return new ReviewResult(new ReviewResult.From("start", null, "since the session started"), out);
    }

    @Test
    void bucketsKeepRepositoryAndPatchOrderAndTreatAnythingUnknownAsRejected() {
        Map<String, List<FileChange>> repos = new LinkedHashMap<>();
        repos.put("api", List.of(file("A.java", "apply"), file("ci.sh", "hold"), file("../evil", "reject")));
        repos.put("web", List.of(file("b.ts", "apply"), file("c.ts", "nonsense")));
        ReviewResult r = review(repos);

        assertThat(DeliverFiles.bucket(r, DeliverFiles.APPLY)).extracting(DeliverFiles.Entry::key)
                .containsExactly("api/A.java", "web/b.ts");
        assertThat(DeliverFiles.bucket(r, DeliverFiles.HOLD)).extracting(DeliverFiles.Entry::key)
                .containsExactly("api/ci.sh");
        assertThat(DeliverFiles.bucket(r, DeliverFiles.REJECT)).extracting(DeliverFiles.Entry::key)
                .containsExactly("api/../evil", "web/c.ts");
    }

    @Test
    void aRowShowsTheFileNameWithTheRepositoryAndDirectoryUnderIt() {
        DeliverFiles.Entry deep = new DeliverFiles.Entry("api", file("src/main/java/A.java", "apply"));
        DeliverFiles.Entry root = new DeliverFiles.Entry("api", file("README.md", "apply"));

        assertThat(deep.name()).isEqualTo("A.java");
        assertThat(deep.location()).isEqualTo("api/src/main/java");
        assertThat(deep.key()).isEqualTo("api/src/main/java/A.java");
        assertThat(root.name()).isEqualTo("README.md");
        assertThat(root.location()).isEqualTo("api");
    }

    @Test
    void everyEntryFindsItsOwnFileInTheParsedPatchOfItsRepository() {
        String patch = """
                diff --git a/src/A.java b/src/A.java
                index 1111111..2222222 100644
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1,3 +1,4 @@
                 one
                +two
                 three
                diff --git a/gone.txt b/gone.txt
                deleted file mode 100644
                --- a/gone.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -x
                -y
                """;

        Map<String, DiffPaneBuilder.FileDiff> index = DeliverFiles.index("api", DiffPaneBuilder.parse(patch));

        assertThat(index.keySet()).containsExactly("api/src/A.java", "api/gone.txt");
        assertThat(index.get(new DeliverFiles.Entry("api", file("src/A.java", "apply")).key()).additions()).isEqualTo(1);
        assertThat(index.get("api/gone.txt").deletions()).isEqualTo(2);
        // The same path in another repository is a different file, and keeps its own diff.
        assertThat(index.get(new DeliverFiles.Entry("web", file("src/A.java", "apply")).key())).isNull();
    }

    private static ReviewResult withHeldAndRejected() {
        Map<String, List<FileChange>> repos = new LinkedHashMap<>();
        repos.put("api", List.of(
                file("A.java", "apply"),
                file("tool.sh", "hold", "EXECUTABLE"),
                file("logo.png", "hold", "BINARY"),
                file("../evil", "reject", "PATH_TRAVERSAL")));
        return review(repos);
    }

    @Test
    void aHeldFileCountsAsLeftBehindUntilItIsTicked() {
        ReviewResult r = withHeldAndRejected();

        DeliverFiles.Excluded none = DeliverFiles.excluded(r, Set.of());
        assertThat(none.any()).isTrue();
        assertThat(none.count()).isEqualTo(3);
        assertThat(none.total()).isEqualTo(4);
        assertThat(none.heldOut()).extracting(DeliverFiles.Entry::path).containsExactly("tool.sh", "logo.png");
        assertThat(none.heldIn()).isEmpty();
        assertThat(none.rejected()).extracting(DeliverFiles.Entry::path).containsExactly("../evil");

        DeliverFiles.Excluded ticked = DeliverFiles.excluded(r, Set.of("api/tool.sh"));
        assertThat(ticked.heldOut()).extracting(DeliverFiles.Entry::path).containsExactly("logo.png");
        assertThat(ticked.heldIn()).extracting(DeliverFiles.Entry::path).containsExactly("tool.sh");
        assertThat(ticked.count()).isEqualTo(2);
    }

    @Test
    void aRejectedFileKeepsTheWarningUpEvenWhenEveryHeldFileIsTicked() {
        DeliverFiles.Excluded all = DeliverFiles.excluded(withHeldAndRejected(), Set.of("api/tool.sh", "api/logo.png"));

        assertThat(all.heldOut()).isEmpty();
        assertThat(all.any()).isTrue();
        assertThat(all.count()).isEqualTo(1);
    }

    @Test
    void nothingIsLeftBehindWhenEveryFileIsInTheApplyBucket() {
        ReviewResult r = review(Map.of("api", List.of(file("A.java", "apply"), file("B.java", "apply"))));

        assertThat(DeliverFiles.excluded(r, Set.of()).any()).isFalse();
    }

    @Test
    void tickingAHeldFileDeliversItAlongsideTheApplyBucketAndNotInsteadOfIt() {
        ReviewResult r = withHeldAndRejected();

        // Nothing ticked: no list at all, so the core applies its own default — the apply bucket.
        assertThat(DeliverFiles.includes(r, Set.of(), false)).isEmpty();
        // One tick: the whole delivery has to be spelled out, or the core would deliver the tick alone.
        assertThat(DeliverFiles.includes(r, Set.of("api/tool.sh"), false)).containsExactly("A.java", "tool.sh");
        assertThat(DeliverFiles.includes(r, Set.of("api/tool.sh"), true)).containsExactly("api/A.java", "api/tool.sh");
    }

    @Test
    void aTickInOneRepositoryLeavesTheSamePathInAnotherAlone() {
        Map<String, List<FileChange>> repos = new LinkedHashMap<>();
        repos.put("api", List.of(file("ci.sh", "hold", "EXECUTABLE")));
        repos.put("web", List.of(file("ci.sh", "hold", "EXECUTABLE")));
        ReviewResult r = review(repos);

        assertThat(DeliverFiles.includes(r, Set.of("web/ci.sh"), true)).containsExactly("web/ci.sh");
        assertThat(DeliverFiles.excluded(r, Set.of("web/ci.sh")).heldOut())
                .extracting(DeliverFiles.Entry::key).containsExactly("api/ci.sh");
    }

    @Test
    void reasonsAreCountedWithTheCommonestFirstAndBlanksDropped() {
        List<DeliverFiles.Entry> entries = List.of(
                new DeliverFiles.Entry("api", file("a.sh", "hold", "EXECUTABLE")),
                new DeliverFiles.Entry("api", file("b.png", "hold", "BINARY")),
                new DeliverFiles.Entry("api", file("c.sh", "hold", "EXECUTABLE")),
                new DeliverFiles.Entry("api", file("d", "hold", null)));

        assertThat(DeliverFiles.reasons(entries)).containsExactly(
                Map.entry("EXECUTABLE", 2), Map.entry("BINARY", 1));
    }

    @Test
    void aFileThatStaysBehindIsNamedWithTheReasonItWasGiven() {
        DeliverFiles.Excluded ex = DeliverFiles.excluded(withHeldAndRejected(), Set.of());

        assertThat(ex.heldOut()).extracting(e -> DeliverFiles.line(e, String::toLowerCase))
                .containsExactly("api/tool.sh — executable", "api/logo.png — binary");
        assertThat(ex.rejected()).extracting(e -> DeliverFiles.line(e, String::toLowerCase))
                .containsExactly("api/../evil — path_traversal");
        // A file the core gave no reason is still named, so it cannot go out of sight.
        assertThat(DeliverFiles.line(new DeliverFiles.Entry("api", file("x", "hold")), r -> r)).isEqualTo("api/x");
    }
}
