package no.syntakserror.lyngvi.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Spec §9.2: the GUI never runs processes and never writes state; the core does (enforced over the sources). */
class ArchitectureTest {

    private static final Path SRC = Path.of("src/main/java");
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\bProcessBuilder\\b"),
            Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec"),
            Pattern.compile("\\.resolve\\(\"state\""),
            Pattern.compile("\\bconversations\\(\\)"),
            Pattern.compile("core\\.session\\.ConversationStore"),
            Pattern.compile("core\\.stack\\.Podman\\b"),
            Pattern.compile("core\\.stack\\.Compose\\b"),
            Pattern.compile("core\\.delivery\\."),
            Pattern.compile("core\\.render\\.Renderer\\b"));

    @Test
    void guiSourcesNeverStartProcessesOrTouchCoreInternals() throws Exception {
        List<String> violations = new ArrayList<>();
        int files = 0;
        try (Stream<Path> s = Files.walk(SRC)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                files++;
                String src = Files.readString(p);
                for (Pattern f : FORBIDDEN) {
                    if (f.matcher(src).find()) violations.add(SRC.relativize(p) + " uses " + f.pattern());
                }
            }
        }
        assertThat(files).isGreaterThan(20);
        assertThat(violations).isEmpty();
    }

    @Test
    void onlyTheCoreApiAndContractPackagesAreCalledForStateChanges() throws Exception {
        // Viewmodels are the only place that calls core services; views go through viewmodels.
        List<String> violations = new ArrayList<>();
        try (Stream<Path> s = Files.walk(SRC.resolve("no/syntakserror/lyngvi/gui/view"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                if (Pattern.compile("\\.(sessionService|deliveryService|stackService|domainService|configService)\\(\\)").matcher(src).find()) {
                    violations.add(SRC.relativize(p).toString());
                }
            }
        }
        assertThat(violations).as("views calling core services directly").isEmpty();
    }
}
