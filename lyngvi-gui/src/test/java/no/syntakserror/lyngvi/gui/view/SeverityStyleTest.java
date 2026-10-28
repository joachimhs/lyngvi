package no.syntakserror.lyngvi.gui.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Severity selectors exist in both themes, and warning and error colours differ (1.1.2 SeverityStyleTest, kept). */
class SeverityStyleTest {

    private static final Path CSS = Path.of("src/main/resources/no/syntakserror/lyngvi/gui/css");
    private static final List<String> SELECTORS = List.of(".msg-system.warning", ".msg-system.error", ".status-warn", ".status-error",
            ".cell-warn", ".cell-error", ".console-warning", ".console-error", ".work-notice.warning", ".work-notice.error",
            ".deliver-warning-title", ".deliver-warning-line");

    @Test
    void selectorsExistInBothThemesWithDistinctWarningAndErrorColours() throws Exception {
        for (String theme : List.of("app.css", "dark.css")) {
            String css = Files.readString(CSS.resolve(theme));
            for (String sel : SELECTORS) assertThat(css).as(sel + " in " + theme).contains(sel + " {");
            assertThat(fill(css, ".console-warning")).isNotEqualTo(fill(css, ".console-error"));
            assertThat(fill(css, ".status-warn")).isNotEqualTo(fill(css, ".status-error"));
            assertThat(fill(css, ".msg-system.warning")).isNotEqualTo(fill(css, ".msg-system.error"));
        }
    }

    private static String fill(String css, String selector) {
        Matcher m = Pattern.compile(Pattern.quote(selector) + " \\{[^}]*-fx-text-fill: (#[0-9a-fA-F]+)").matcher(css);
        assertThat(m.find()).as(selector + " has -fx-text-fill").isTrue();
        return m.group(1);
    }
}
