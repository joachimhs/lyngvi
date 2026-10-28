package no.syntakserror.lyngvi.gui.view;

import java.io.InputStream;
import java.util.Optional;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.version.Version;
import no.syntakserror.lyngvi.gui.viewmodel.Settings;

/**
 * The window that stands in for the main window while the application starts: the mark, the name, the tagline
 * and the version on a themed card, centred on the primary screen, with a close button in the top-right corner.
 * Its one line of prose is in the language of the sandbox that was open last, because the language is a sandbox
 * setting and none is open this early; a first-ever start is in English.
 *
 * <p>Start-up work has to be handed to {@link #whenPainted(Runnable)}. JavaFX paints on a pulse of the FX
 * thread, and {@code Application.start} holds that thread for as long as it runs: a splash shown at the top
 * of {@code start} and left there would stay an empty rectangle until the window it covers for is already
 * up. Posting the work from the second pulse instead puts the splash on screen first, and costs two frames.
 *
 * <p>{@link #dismiss(Runnable)} keeps it up for {@link #MIN_VISIBLE} at the least — a start that is quicker than
 * that waits out the rest of it — and then fades the stage out over the main window. Escape, the close button and
 * a click anywhere on the card are the ways past that wait, and they all go through the same hand-over: whatever
 * waited for the splash runs either way, once.
 */
public final class Splash {

    /** The style classes the splash carries; {@code app.css} has a rule for each (see {@code SplashTest}). */
    static final String ROOT_CLASS = "splash-root";
    static final String MARK_CLASS = "splash-mark";
    static final String TITLE_CLASS = "splash-title";
    static final String TAGLINE_CLASS = "splash-tagline";
    static final String VERSION_CLASS = "splash-version";
    static final String CLOSE_CLASS = "splash-close";

    /** The one line of prose on the splash, the same words the installer describes the product with. */
    static final String TAGLINE_KEY = "gui.splash.tagline";

    /** The mark the splash draws, and the size the file holds: a wolf in a ring, trimmed to the artwork. */
    static final String MARK_IMAGE = "/no/syntakserror/lyngvi/gui/icon/splash-mark-512.png";
    static final int MARK_NATIVE = 512;

    /** How long the splash stays up at the least, counted from the moment it is shown. */
    static final Duration MIN_VISIBLE = Duration.seconds(10);
    private static final Duration FADE = Duration.millis(220);

    private static final double WIDTH = 480;
    private static final double HEIGHT = 410;
    /** The side of the mark on screen, in points; the pixels behind it come from {@link #decodeSize(double)}. */
    static final double MARK_SIZE = 192;

    private final Stage stage;
    private final long shownAt;

    private Timeline fade;
    private Runnable whenGone;
    private boolean gone;

    private Splash(Settings.Theme theme, Messages messages) {
        VBox content = new VBox(14);
        content.setAlignment(Pos.CENTER);
        content.setFillWidth(false);   // the disc behind the mark is round only for as long as it stays square
        mark().ifPresent(content.getChildren()::add);
        content.getChildren().addAll(label("Lyngvi", TITLE_CLASS), tagline(messages),
                label(Version.current().toString(), VERSION_CLASS));

        // × rather than a nicer-looking ✕: this is the one window with no decoration of its own to borrow a glyph
        // from, and U+00D7 is in every font the platforms ship.
        Button close = new Button("×");
        close.getStyleClass().add(CLOSE_CLASS);
        close.setFocusTraversable(false);
        close.setOnAction(e -> handOver());

        StackPane root = new StackPane(content, close);
        root.getStyleClass().add(ROOT_CLASS);
        StackPane.setAlignment(close, Pos.TOP_RIGHT);
        // The button is 28 points square on a card of 480, and a desktop that focuses on click spends the first
        // press on focusing the window rather than on what was under the pointer — so the whole card takes the
        // press. Anyone who wants the splash gone is served by aiming anywhere on it.
        root.setOnMousePressed(e -> handOver());

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        dismissOn(scene);
        Theme.apply(scene, theme);
        stage = new Stage(StageStyle.UNDECORATED);
        stage.setTitle("Lyngvi");
        stage.setScene(scene);
        // Nothing else of ours is on screen yet, and the main window arrives under it for the length of the fade.
        stage.setAlwaysOnTop(true);
        Point2D corner = centreOn(Screen.getPrimary().getVisualBounds(), WIDTH, HEIGHT);
        stage.setX(corner.getX());
        stage.setY(corner.getY());
        stage.show();
        shownAt = System.nanoTime();
    }

    /**
     * Builds the splash in {@code theme} and shows it; call from the FX thread, before anything slow.
     * {@code messages} is the language the last sandbox was in — see {@link Settings#language()} for why the
     * splash cannot simply ask the one that is about to open.
     */
    public static Splash show(Settings.Theme theme, Messages messages) {
        return new Splash(theme, messages);
    }

    /** Runs {@code action} on the FX thread once the splash has been painted. */
    public void whenPainted(Runnable action) {
        new AnimationTimer() {
            private int pulses;

            @Override
            public void handle(long now) {
                // The first pulse lays the splash out and renders it; a second pulse means that work is behind us.
                if (++pulses < 2) return;
                stop();
                action.run();
            }
        }.start();
    }

    /**
     * Lets Escape pressed in {@code scene} take the splash down. The splash does this for its own scene; {@code App}
     * does it for the main window's as well, because that window is shown under the splash while the wait is still
     * running and takes the keyboard with it — an Escape pressed then arrives in the main scene, not in this one.
     * The filter stands down once the splash is gone, so Escape goes back to meaning whatever the window it was
     * pressed in means by it.
     */
    public void dismissOn(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (gone || !dismisses(e.getCode())) return;
            e.consume();
            handOver();
        });
    }

    /** Escape, the key for «take this off my screen» on every desktop, and the only key the splash answers to. */
    static boolean dismisses(KeyCode code) {
        return code == KeyCode.ESCAPE;
    }

    /**
     * Puts the splash back in front of the window just shown under it, with the keyboard. Showing the main window
     * focuses it, and the splash — still the window the user sees, since it is always on top — is left without the
     * keyboard: Escape would go to the main window, and a press on the close button would be spent focusing the
     * splash instead of pressing the button. Does nothing once the splash is gone, so a late call cannot pull a
     * closed stage back up.
     */
    public void toFront() {
        if (gone) return;
        stage.toFront();
        stage.requestFocus();
    }

    /**
     * Fades the splash out and closes it, once it has been up for {@link #MIN_VISIBLE}, then runs {@code whenGone}
     * on the FX thread. That is where anything modal belongs: the splash is always on top, so a dialog opened
     * before it goes would come up behind it.
     */
    public void dismiss(Runnable whenGone) {
        this.whenGone = whenGone;
        // The start-up holds the FX thread, so a click on the close button lands the moment it lets go — which can
        // be before this call. The splash is already gone then, and all that is left is what waited for it.
        if (gone) {
            whenGone.run();
            return;
        }
        Duration hold = remaining(elapsedMs());
        // The stage fades, not its content: fading the root would show the scene's own background through it.
        fade = new Timeline(new KeyFrame(hold, new KeyValue(stage.opacityProperty(), 1.0)),
                new KeyFrame(hold.add(FADE), new KeyValue(stage.opacityProperty(), 0.0)));
        fade.setOnFinished(e -> handOver());
        fade.play();
    }

    /** Takes the splash off the screen at once, for a start-up that failed and has nothing to hand over to. */
    public void closeNow() {
        gone = true;
        if (fade != null) fade.stop();
        stage.close();
    }

    /** The end of the wait and of the close button alike: the splash goes, and what waited for it runs, once. */
    private void handOver() {
        if (gone) return;
        gone = true;
        if (fade != null) fade.stop();
        stage.close();
        if (whenGone != null) whenGone.run();
    }

    /** The wait left of {@link #MIN_VISIBLE} after {@code elapsedMs} on screen; never negative. */
    static Duration remaining(long elapsedMs) {
        return Duration.millis(Math.max(0, MIN_VISIBLE.toMillis() - elapsedMs));
    }

    /** The top-left corner that centres a {@code width} × {@code height} window on {@code screen}. */
    static Point2D centreOn(Rectangle2D screen, double width, double height) {
        return new Point2D(screen.getMinX() + (screen.getWidth() - width) / 2,
                screen.getMinY() + (screen.getHeight() - height) / 2);
    }

    private long elapsedMs() {
        return (System.nanoTime() - shownAt) / 1_000_000;
    }

    /**
     * The pixels to decode the mark at: one per device pixel of the box it is drawn in, and never more than the
     * file holds. Decoding at the size in points leaves a HiDPI screen nothing to fill its second device pixel
     * from, and decoding the whole 512 and letting the view shrink it leans on a draw-time reduction that
     * samples four pixels at a time and loses the thin lines in the artwork. The decoder does the better job,
     * so it is told the size that needs no scaling afterwards.
     */
    static int decodeSize(double outputScale) {
        return (int) Math.min(MARK_NATIVE, Math.ceil(MARK_SIZE * Math.max(1, outputScale)));
    }

    /**
     * The mark on a pale disc. The artwork is a navy wolf with a cyan ring and carries no background of its own,
     * which is legible on paper but very nearly invisible on the dark theme, where only the ring survives — so
     * it is given the light surface it was drawn for, in both themes.
     *
     * <p>Absent rather than fatal if the resource is gone: the splash is worth having without it.
     */
    private static Optional<StackPane> mark() {
        InputStream in = Splash.class.getResourceAsStream(MARK_IMAGE);
        if (in == null) return Optional.empty();
        int pixels = decodeSize(Screen.getPrimary().getOutputScaleX());
        ImageView view = new ImageView(new Image(in, pixels, pixels, true, true));
        view.setFitWidth(MARK_SIZE);
        view.setFitHeight(MARK_SIZE);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        StackPane disc = new StackPane(view);
        disc.getStyleClass().add(MARK_CLASS);
        return Optional.of(disc);
    }

    /** The tagline, wrapped and kept inside the card: a translation is free to be longer than the English line. */
    private static Label tagline(Messages messages) {
        Label label = label(messages.get(TAGLINE_KEY), TAGLINE_CLASS);
        label.setWrapText(true);
        label.setMaxWidth(WIDTH - 80);
        label.setTextAlignment(TextAlignment.CENTER);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
