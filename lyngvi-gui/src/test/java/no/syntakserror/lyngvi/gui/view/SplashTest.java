package no.syntakserror.lyngvi.gui.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.css.CssParser;
import javafx.css.Declaration;
import javafx.css.ParsedValue;
import javafx.css.Rule;
import javafx.css.Size;
import javafx.css.Stylesheet;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.input.KeyCode;
import no.syntakserror.lyngvi.core.i18n.Messages;
import org.junit.jupiter.api.Test;

/**
 * What can be checked of the splash without a display: where it puts itself, how long it stays, and that the
 * style classes it builds in Java are styled in the stylesheet. The last one matters because the splash is the
 * one window with no FXML behind it — a renamed class takes no test with it, it just shows unstyled text on a
 * default-grey window at start-up, which is the first thing a user sees.
 */
class SplashTest {

    private static final Path APP_CSS = Path.of("src/main/resources/no/syntakserror/lyngvi/gui/css/app.css");
    private static final List<String> SIDES = List.of("-fx-min-width", "-fx-pref-width", "-fx-max-width",
            "-fx-min-height", "-fx-pref-height", "-fx-max-height");

    @Test
    void theSplashIsCentredOnTheScreenItIsGiven() {
        Point2D corner = Splash.centreOn(new Rectangle2D(0, 0, 1920, 1080), 420, 300);

        assertThat(corner.getX()).isEqualTo(750);
        assertThat(corner.getY()).isEqualTo(390);
    }

    @Test
    void aScreenThatDoesNotStartAtTheOriginIsCentredInItsOwnBounds() {
        // a second monitor to the right, or a primary screen whose visual bounds start under a menu bar
        Point2D corner = Splash.centreOn(new Rectangle2D(1920, 25, 1000, 800), 420, 300);

        assertThat(corner.getX()).isEqualTo(2210);
        assertThat(corner.getY()).isEqualTo(275);
    }

    @Test
    void aFastStartWaitsOutTheRestOfTheMinimum() {
        assertThat(Splash.MIN_VISIBLE.toSeconds()).as("spec §9.3: ten seconds at the least").isEqualTo(10);
        assertThat(Splash.remaining(2_500).toMillis()).isEqualTo(7_500);
    }

    @Test
    void aSlowStartDismissesTheSplashAtOnceRatherThanWaitingBackwards() {
        long slower = (long) Splash.MIN_VISIBLE.toMillis() + 5_000;

        assertThat(Splash.remaining(slower).toMillis()).isZero();
    }

    /**
     * Escape is the other way past the wait, and `App` installs the same filter on the main window's scene, since
     * that window is shown under the splash and holds the keyboard for part of the time the splash is up.
     */
    @Test
    void escapeIsTheKeyThatTakesTheSplashDown() {
        assertThat(Splash.dismisses(KeyCode.ESCAPE)).isTrue();
        assertThat(Splash.dismisses(KeyCode.ENTER)).as("nothing a user types at the main window behind it").isFalse();
        assertThat(Splash.dismisses(KeyCode.SPACE)).isFalse();
    }

    @Test
    void everyStyleClassTheSplashUsesIsStyledInAppCss() throws Exception {
        Stylesheet css = new CssParser().parse(APP_CSS.toUri().toURL());
        Set<String> selectors = css.getRules().stream().map(Rule::getSelectors).flatMap(List::stream)
                .map(Object::toString).collect(Collectors.toSet());

        for (String styleClass : List.of(Splash.ROOT_CLASS, Splash.MARK_CLASS, Splash.TITLE_CLASS,
                Splash.TAGLINE_CLASS, Splash.VERSION_CLASS, Splash.CLOSE_CLASS)) {
            assertThat(selectors).as(styleClass).contains("*." + styleClass);
        }
    }

    /**
     * The tagline is the splash's only prose, and it is read straight from the bundle rather than through
     * {@code LenientBundle}: a missing key would be drawn as {@code [gui.splash.tagline]} across the first
     * window of the session, in both languages.
     */
    @Test
    void theTaglineIsTranslatedInEveryLanguageTheProductSpeaks() {
        for (Locale locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("nb"))) {
            assertThat(Messages.of(locale).get(Splash.TAGLINE_KEY)).as(locale.toString())
                    .isNotBlank().doesNotStartWith("[");
        }
        assertThat(Messages.of(Locale.forLanguageTag("nb")).get(Splash.TAGLINE_KEY))
                .as("the Norwegian line is its own, not the English one copied")
                .isNotEqualTo(Messages.of(Locale.ENGLISH).get(Splash.TAGLINE_KEY));
    }

    /**
     * The mark is its own resource rather than one of the window icons: those carry the 8% padding an application
     * icon needs, which the splash would show as a mark a fifth smaller than its box. {@code Splash} leaves the
     * mark out rather than failing when the file is gone, so a rename would cost the splash its picture quietly.
     */
    @Test
    void theSplashMarkIsOnTheClasspathAtTheSizeTheCodeExpects() throws Exception {
        try (InputStream in = Splash.class.getResourceAsStream(Splash.MARK_IMAGE)) {
            assertThat(in).as(Splash.MARK_IMAGE).isNotNull();
            assertThat(readPngSize(in)).as(Splash.MARK_IMAGE).containsExactly(Splash.MARK_NATIVE, Splash.MARK_NATIVE);
        }
    }

    /**
     * One image pixel per device pixel: decoding at the size in points leaves a 2× screen half the detail it can
     * draw, and asking for more than the file holds would upscale it.
     */
    @Test
    void theMarkIsDecodedAtAsManyPixelsAsTheScreenCanDraw() {
        assertThat(Splash.decodeSize(1)).isEqualTo((int) Splash.MARK_SIZE);
        assertThat(Splash.decodeSize(2)).isEqualTo((int) Splash.MARK_SIZE * 2);
        assertThat(Splash.decodeSize(1.5)).isEqualTo((int) (Splash.MARK_SIZE * 1.5));
        assertThat(Splash.decodeSize(3)).as("capped at what the file holds").isEqualTo(Splash.MARK_NATIVE);
        assertThat(Splash.decodeSize(0)).as("a scale the toolkit could not work out").isEqualTo((int) Splash.MARK_SIZE);
    }

    /** Width and height from the PNG signature and IHDR, which are the first 24 bytes of any PNG. */
    private static int[] readPngSize(InputStream in) throws Exception {
        DataInputStream data = new DataInputStream(in);
        data.readFully(new byte[8]);   // signature
        data.readInt();                // IHDR length
        data.readInt();                // "IHDR"
        return new int[] {data.readInt(), data.readInt()};
    }

    /**
     * The close button is a {@code Button}, and the shared button rule pins every button to 32px and a pill shape.
     * Both selectors are one class deep, so the splash keeps its square corner button only for as long as its rule
     * comes last in the file — move the section up and the cascade silently turns it back into a pill.
     */
    @Test
    void theCloseButtonStaysSquareAgainstTheSharedButtonRule() throws Exception {
        Stylesheet css = new CssParser().parse(APP_CSS.toUri().toURL());
        Map<String, Double> effective = new HashMap<>();
        for (Rule rule : css.getRules()) {
            boolean hitsTheCloseButton = rule.getSelectors().stream().map(Object::toString)
                    .anyMatch(s -> s.equals("*.button") || s.equals("*." + Splash.CLOSE_CLASS));
            if (!hitsTheCloseButton) continue;
            for (Declaration d : rule.getDeclarations()) {
                if (SIDES.contains(d.getProperty())) effective.put(d.getProperty(), size(d));
            }
        }
        assertThat(effective).as("the width and height the cascade leaves the close button with")
                .containsOnlyKeys(SIDES.toArray(String[]::new))
                .allSatisfy((property, value) -> assertThat(value).as(property).isEqualTo(28.0));
    }

    /** The number out of a declared size, dropping the unit: {@code 28.0px} to {@code 28.0}. */
    private static double size(Declaration declaration) {
        Object value = declaration.getParsedValue();
        while (value instanceof ParsedValue<?, ?> parsed) value = parsed.getValue();
        return ((Size) value).getValue();
    }
}
