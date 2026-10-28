package no.syntakserror.lyngvi.gui.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.css.CssParser;
import javafx.css.Declaration;
import javafx.css.ParsedValue;
import javafx.css.Rule;
import javafx.css.Size;
import javafx.css.Stylesheet;
import org.junit.jupiter.api.Test;

/**
 * A nav-rail item is an icon over a label (spec §9.3), so it is two rows tall where every other button is one.
 * The shared {@code .button, .toggle-button} rule pins buttons to 32px, and {@code .rail-button} has the same
 * specificity: unless the rail rule overrides the three height properties, the clamp wins, JavaFX clips the label
 * to the nothing that is left under the icon, and the rail silently looks icon-only. Parsing the stylesheet needs
 * no toolkit.
 */
class NavRailStyleTest {

    private static final Path APP_CSS = Path.of("src/main/resources/no/syntakserror/lyngvi/gui/css/app.css");
    private static final List<String> HEIGHTS = List.of("-fx-min-height", "-fx-pref-height", "-fx-max-height");
    private static final double USE_COMPUTED_SIZE = -1;

    @Test
    void railItemsSizeToTheirContentSoTheLabelIsNotClipped() throws Exception {
        Stylesheet css = new CssParser().parse(APP_CSS.toUri().toURL());
        Map<String, Double> effective = new HashMap<>();
        for (Rule rule : css.getRules()) {
            boolean hitsARailItem = rule.getSelectors().stream().map(Object::toString)
                    .anyMatch(s -> s.equals("*.toggle-button") || s.equals("*.rail-button"));
            if (!hitsARailItem) continue;
            // one class each, so specificity is equal and the last declaration in the file is the one that wins
            for (Declaration d : rule.getDeclarations()) {
                if (HEIGHTS.contains(d.getProperty())) effective.put(d.getProperty(), size(d));
            }
        }
        for (String property : HEIGHTS) {
            assertThat(effective.getOrDefault(property, USE_COMPUTED_SIZE))
                    .as(property + " of a nav-rail item (a fixed height clips the label away)")
                    .isEqualTo(USE_COMPUTED_SIZE);
        }
    }

    /** The number out of a declared size, dropping the unit: {@code 32.0px} to {@code 32.0}. */
    private static double size(Declaration declaration) {
        Object value = declaration.getParsedValue();
        while (value instanceof ParsedValue<?, ?> parsed) value = parsed.getValue();
        return ((Size) value).getValue();
    }
}
