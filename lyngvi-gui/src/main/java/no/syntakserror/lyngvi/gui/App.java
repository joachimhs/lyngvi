package no.syntakserror.lyngvi.gui;

import java.nio.file.Path;
import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Screen;
import javafx.stage.Stage;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.gui.view.Dialogs;
import no.syntakserror.lyngvi.gui.view.Splash;
import no.syntakserror.lyngvi.gui.viewmodel.Settings;

/**
 * JavaFX entry point: the splash, the settings, the first {@link MainWindow} and the handlers that belong to the
 * application rather than to a window. Further windows — one per sandbox the user wants open at the same time —
 * are built by {@code MainWindow} itself. {@code -Dlyngvi.gui.smoke=<ms>} closes the window after a delay.
 */
public final class App extends Application {

    private Services services;
    private boolean showingError;

    /**
     * Shows the splash, then builds the application from the splash's second pulse: everything below holds the FX
     * thread for as long as it runs, and nothing is painted while it does, so work done here instead would leave
     * the splash an empty rectangle. See {@link Splash}.
     */
    @Override
    public void start(Stage stage) {
        no.syntakserror.lyngvi.core.runtime.Platform platform = no.syntakserror.lyngvi.core.runtime.Platform.current();
        Settings settings = Settings.inHome(platform.home());
        Splash splash = Splash.show(settings.theme(), Messages.forLanguage(settings.language().orElse(null)));
        splash.whenPainted(() -> {
            try {
                build(stage, platform, settings, splash);
            } catch (Exception e) {
                splash.closeNow(); // there is no window to hand over to; leaving it up would hide the failure
                throw new IllegalStateException("Lyngvi could not start", e);
            }
        });
    }

    private void build(Stage stage, no.syntakserror.lyngvi.core.runtime.Platform platform, Settings settings, Splash splash) throws Exception {
        Optional<Path> dir = getParameters().getNamed().containsKey("sandbox") ? Optional.of(Path.of(getParameters().getNamed().get("sandbox")))
                : Optional.ofNullable(System.getenv("LYNGVI_HOME")).filter(s -> !s.isBlank()).map(Path::of).or(settings::sandboxDir);
        // The window opens the sandbox, builds its own viewmodels and takes its own log: everything below is what
        // the first window needs on top of that — the splash it appears under, and the handlers for the whole app.
        MainWindow window = MainWindow.create(stage, platform, settings, dir, true);
        services = window.services();
        // This window is shown under the splash and takes the keyboard from it, so Escape pressed while the splash
        // is still up lands here. It means the same thing in both windows for as long as the splash is one of them.
        splash.dismissOn(stage.getScene());
        Thread.currentThread().setUncaughtExceptionHandler((t, ex) -> {
            services.log().error("uncaught on the FX thread", ex);
            // The dialog runs a nested event loop; an error raised inside it must not open another dialog on top.
            if (showingError) return;
            showingError = true;
            try {
                new Dialogs(services.bundle(), stage).error(no.syntakserror.lyngvi.gui.viewmodel.Errors.map(ex, services.messages()));
            } catch (RuntimeException ignored) {
                // the dialog itself failed; the log has the details
            } finally {
                showingError = false;
            }
        });
        stage.show();
        // The splash stays in front of the window that has just appeared under it, so give it the keyboard back:
        // an unfocused window spends the first press on becoming focused, which is a close button that needs two
        // clicks and an Escape that goes to the wrong window.
        splash.toFront();
        String smoke = System.getProperty("lyngvi.gui.smoke");
        // First-run dialogs are modal: run them once the splash is gone and the window is up, never in a smoke run.
        splash.dismiss(() -> window.started(smoke == null));
        if (smoke != null) {
            long ms = Long.parseLong(smoke);
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException ignored) {
                    return;
                }
                Platform.runLater(() -> {
                    services.log().info("smoke run finished");
                    System.out.println(services.log().text());
                    Platform.exit();
                });
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                    return;
                }
                System.exit(0);
            });
        }
    }

    /** The bounds the first window had when it was last closed; windows opened beside it are cascaded instead. */
    static void restoreBounds(Stage stage, Settings settings) {
        restoreBounds(stage, settings.windowBounds());
    }

    /**
     * Puts a window back where it was left. Bounds from a screen that is no longer there — a laptop undocked
     * since — are not used: the window is centred instead, where it can be seen.
     *
     * @return whether the window was placed; the caller decides where it goes when it was not.
     */
    static boolean restoreBounds(Stage stage, Optional<Settings.WindowBounds> saved) {
        saved.ifPresent(b -> {
            javafx.geometry.Rectangle2D r = new javafx.geometry.Rectangle2D(b.x(), b.y(), b.width(), b.height());
            boolean visible = Screen.getScreens().stream().anyMatch(s -> s.getVisualBounds().intersects(r));
            if (visible) {
                stage.setX(b.x());
                stage.setY(b.y());
                stage.setWidth(b.width());
                stage.setHeight(b.height());
            } else {
                stage.centerOnScreen();
            }
            stage.setMaximized(b.maximized());
        });
        return saved.isPresent();
    }

    static void saveBounds(Stage stage, Settings settings) {
        settings.setWindowBounds(new Settings.WindowBounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), stage.isMaximized()));
        settings.save();
    }

    /** The close-warning text when conversations are still running. */
    public static String activeConversationWarning(int running, Messages m) {
        return running == 1 ? m.get("gui.close.warning.one") : m.get("gui.close.warning.many", running);
    }

    @Override
    public void stop() {
        MainWindow.closeAll();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
