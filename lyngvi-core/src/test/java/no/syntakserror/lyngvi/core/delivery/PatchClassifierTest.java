package no.syntakserror.lyngvi.core.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.contract.FileChange;
import org.junit.jupiter.api.Test;

class PatchClassifierTest {

    private static Map<String, FileChange> classify() throws Exception {
        String patch = Files.readString(Path.of("src/test/resources/golden/m5/mixed.patch"));
        List<FileChange> changes = PatchClassifier.classify(patch, 50L << 20);
        return changes.stream().collect(java.util.stream.Collectors.toMap(FileChange::path, c -> c, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    @Test
    void ordinaryChangesGoToApply() throws Exception {
        FileChange c = classify().get("src/auth.ts");

        assertThat(c.bucket()).isEqualTo("apply");
        assertThat(c.change()).isEqualTo("modified");
        assertThat(c.additions()).isEqualTo(2);
        assertThat(c.deletions()).isEqualTo(1);
        assertThat(c.reason()).isNull();
        assertThat(classify().get("gone.txt").change()).isEqualTo("deleted");
        assertThat(classify().get("new.txt").change()).isEqualTo("renamed");
        assertThat(classify().get("new.txt").renamedFrom()).isEqualTo("old.txt");
    }

    @Test
    void executablesSymlinksBinariesAndModeChangesAreHeld() throws Exception {
        Map<String, FileChange> c = classify();

        assertThat(c.get("bin/run.sh").bucket()).isEqualTo("hold");
        assertThat(c.get("bin/run.sh").reason()).isEqualTo("EXECUTABLE");
        assertThat(c.get("bin/run.sh").mode()).isEqualTo("100755");
        assertThat(c.get("link").reason()).isEqualTo("SYMLINK");
        assertThat(c.get("img.png").reason()).isEqualTo("BINARY");
        assertThat(c.get("img.png").binary()).isTrue();
        assertThat(c.get("tool").reason()).isEqualTo("EXECUTABLE");
        assertThat(c.get("tool").change()).isEqualTo("modified");
    }

    @Test
    void dangerousPathsAreRejected() throws Exception {
        Map<String, FileChange> c = classify();

        assertThat(c.get("../secrets").bucket()).isEqualTo("reject");
        assertThat(c.get("../secrets").reason()).isEqualTo("PATH_TRAVERSAL");
        assertThat(c.get(".git/hooks/pre-commit").reason()).isEqualTo("GIT_PATH");
        assertThat(c.get("/etc/motd").reason()).isEqualTo("ABSOLUTE_PATH");
    }

    @Test
    void oversizedChangesAreHeld() throws Exception {
        String patch = Files.readString(Path.of("src/test/resources/golden/m5/mixed.patch"));

        List<FileChange> tiny = PatchClassifier.classify(patch, 10);

        assertThat(tiny.stream().filter(f -> f.path().equals("src/auth.ts")).findFirst().orElseThrow().reason()).isEqualTo("TOO_LARGE");
    }

    @Test
    void emptyPatchHasNoChanges() {
        assertThat(PatchClassifier.classify("", 1)).isEmpty();
        assertThat(PatchClassifier.classify(null, 1)).isEmpty();
    }

    @Test
    void splitsThePatchPerFile() throws Exception {
        String patch = Files.readString(Path.of("src/test/resources/golden/m5/mixed.patch"));

        Map<String, String> parts = PatchClassifier.split(patch);

        assertThat(parts).containsKeys("src/auth.ts", "bin/run.sh", "new.txt", "tool");
        assertThat(parts.get("src/auth.ts")).startsWith("diff --git a/src/auth.ts b/src/auth.ts\n").contains("+  // fixed");
        assertThat(parts.get("tool")).isEqualTo("diff --git a/tool b/tool\nold mode 100644\nnew mode 100755\n");
    }
}
