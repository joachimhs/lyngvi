package no.syntakserror.lyngvi.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.runtime.ProcessRunner;
import no.syntakserror.lyngvi.core.version.Version;
import no.syntakserror.lyngvi.gui.view.ChatController;
import no.syntakserror.lyngvi.gui.view.Dialogs;
import no.syntakserror.lyngvi.gui.view.Theme;
import no.syntakserror.lyngvi.gui.viewmodel.AppLog;
import no.syntakserror.lyngvi.gui.viewmodel.Async;
import no.syntakserror.lyngvi.gui.viewmodel.SandboxContext;
import no.syntakserror.lyngvi.gui.viewmodel.Settings;

/**
 * One Lyngvi window. Each has its own {@link SandboxContext}, viewmodels and console log, so several sandboxes can
 * be worked on at the same time: what runs in one window is untouched by what happens in another. The windows
 * share the GUI settings file, the FX toolkit and nothing else.
 *
 * <p>{@link App} builds the first window on the stage JavaFX hands it; the sandbox switcher in the toolbar builds
 * the rest with {@link #openAnother}. Only the first window's bounds are remembered — the ones opened beside it
 * are cascaded from the window they were opened from, which is where the user is looking.
 */
public final class MainWindow {

    /** The windows now on screen, in the order they were opened. Read and written on the FX thread only. */
    private static final List<MainWindow> OPEN = new ArrayList<>();
    /** How far a new window is placed below and to the right of the one it was opened from, per window so far. */
    private static final double CASCADE = 32;
    /** After this many windows the cascade starts over rather than walking off the screen. */
    private static final int CASCADE_STEPS = 8;
    /** The size a window gets when there is nothing to take it from: no saved bounds, no window to open beside. */
    private static final double DEFAULT_WIDTH = 1100;
    private static final double DEFAULT_HEIGHT = 740;

    private final Stage stage;
    private final Services services;
    private final MainController controller;
    private final Settings settings;
    private final boolean primary;
    /** A 1.1.2 sandbox the window was asked to open: offered for migration once the window is up. */
    private final Optional<Path> pendingMigration;
    private boolean closed;

    private MainWindow(Stage stage, Services services, MainController controller, Settings settings, boolean primary,
                       Optional<Path> pendingMigration) {
        this.stage = stage;
        this.services = services;
        this.controller = controller;
        this.settings = settings;
        this.primary = primary;
        this.pendingMigration = pendingMigration;
    }

    public Stage stage() {
        return stage;
    }

    public Services services() {
        return services;
    }

    MainController controller() {
        return controller;
    }

    /** The windows now on screen. */
    public static List<MainWindow> open() {
        return List.copyOf(OPEN);
    }

    /** The window showing this sandbox, if one does; the switcher raises it rather than opening a second one. */
    public static Optional<MainWindow> showing(Path dir) {
        Path wanted = dir.toAbsolutePath().normalize();
        return OPEN.stream()
                .filter(w -> w.services.ctx().dir().map(d -> d.toAbsolutePath().normalize().equals(wanted)).orElse(false))
                .findFirst();
    }

    /** Brings the window to the front of the desktop, out of the dock if the user had put it away. */
    public void focus() {
        if (stage.isIconified()) stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    /**
     * Builds a window and opens {@code sandboxDir} in it, without showing it. The sandbox is opened before the
     * FXML is loaded, so the window is built in the sandbox's own language and the first paint already has its
     * state on it.
     */
    static MainWindow create(Stage stage, no.syntakserror.lyngvi.core.runtime.Platform platform, Settings settings,
                             Optional<Path> sandboxDir, boolean primary) throws IOException {
        AppLog log = new AppLog();
        SandboxContext ctx = new SandboxContext(platform, new ProcessRunner(platform));
        Async async = Async.virtualThreads(Platform::runLater);
        Services services = Services.create(ctx, settings, log, async);
        log.info("Lyngvi GUI " + Version.current());
        // The splash has text on it before any sandbox is open, so the language of the one that opens is kept for
        // the next start. Every sandbox change comes through this listener, including a switch made from the menu.
        ctx.addListener(() -> {
            String language = ctx.messages().locale().getLanguage();
            if (!language.equals(settings.language().orElse(""))) {
                settings.setLanguage(language);
                settings.save();
            }
        });

        Optional<Path> pending = Optional.empty();
        if (sandboxDir.isPresent()) {
            if (ctx.needsMigration(sandboxDir.get())) pending = sandboxDir; // offered after the window is up
            else if (!ctx.open(sandboxDir.get())) {
                log.warn("sandbox " + sandboxDir.get() + " could not be opened: " + ctx.lastError().getMessage());
            }
        }

        // Before the controller runs: it names the window after the sandbox it opens, and would be overwritten here.
        stage.setTitle("Lyngvi");
        addIcons(stage);
        FXMLLoader loader = new FXMLLoader(App.class.getResource("/no/syntakserror/lyngvi/gui/fxml/main.fxml"), services.bundle());
        loader.setControllerFactory(type -> {
            if (type == MainController.class) return new MainController(services, stage);
            if (type == ChatController.class) return new ChatController(services, stage);
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create controller " + type, e);
            }
        });
        Parent root = loader.load();
        MainController controller = loader.getController();
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        Theme.apply(scene, settings.theme());
        stage.setScene(scene);
        if (primary) App.restoreBounds(stage, settings);

        MainWindow window = new MainWindow(stage, services, controller, settings, primary, pending);
        OPEN.add(window);
        stage.setOnCloseRequest(e -> {
            if (!window.confirmClose()) {
                e.consume();
                return;
            }
            window.close();
        });
        return window;
    }

    /**
     * Opens another window on {@code dir}, beside the window it was asked for from. The two share nothing but the
     * settings file: the new window opens the sandbox itself, in a context of its own.
     */
    public static MainWindow openAnother(no.syntakserror.lyngvi.core.runtime.Platform platform, Settings settings,
                                         Path dir, Stage from) throws IOException {
        MainWindow window = create(new Stage(), platform, settings, Optional.of(dir), false);
        place(window.stage, from);
        window.stage.show();
        window.started(true);
        return window;
    }

    /** Starts the window's timers and, unless this is a smoke run, the dialogs that belong to a fresh window. */
    void started(boolean withDialogs) {
        controller.afterShow(withDialogs);
        if (withDialogs) pendingMigration.ifPresent(controller::openOrMigrate);
    }

    /**
     * Asks before work in this window is interrupted. The warning names what this window is running: another
     * window's conversations are not affected by closing this one.
     */
    private boolean confirmClose() {
        int running = services.sessions().runningCount();
        if (running == 0) return true;
        Messages m = services.messages();
        return new Dialogs(services.bundle(), stage).confirm(m.get("gui.close.title"),
                primary ? App.activeConversationWarning(running, m) : closeWindowWarning(running, m),
                m.get(primary ? "gui.close.quit" : "gui.close.window"));
    }

    /** The close-warning text for a window that is not the last one: the application goes on running. */
    public static String closeWindowWarning(int running, Messages m) {
        return running == 1 ? m.get("gui.close.window.warning.one") : m.get("gui.close.window.warning.many", running);
    }

    /** Stops what the window polls and follows, and takes it off the list of open windows. */
    void close() {
        if (closed) return;
        closed = true;
        if (primary) App.saveBounds(stage, settings);
        controller.shutdown();
        OPEN.remove(this);
    }

    /** Shuts every window down; the application is on its way out. */
    static void closeAll() {
        for (MainWindow w : List.copyOf(OPEN)) w.close();
    }

    /**
     * Puts a new window where it can be told apart from the one it came from: the same size, a little below and to
     * the right, and never beyond the screen that window is on.
     */
    private static void place(Stage stage, Stage from) {
        double width = size(from.getWidth(), DEFAULT_WIDTH);
        double height = size(from.getHeight(), DEFAULT_HEIGHT);
        // One step per window already on screen, so windows opened one after another march down and to the right.
        double step = CASCADE * (1 + (Math.max(0, OPEN.size() - 2) % CASCADE_STEPS));
        Rectangle2D screen = screenOf(from);
        stage.setWidth(Math.min(width, screen.getWidth()));
        stage.setHeight(Math.min(height, screen.getHeight()));
        double x = Double.isNaN(from.getX()) ? screen.getMinX() : from.getX();
        double y = Double.isNaN(from.getY()) ? screen.getMinY() : from.getY();
        stage.setX(clamp(x + step, screen.getMinX(), screen.getMaxX() - stage.getWidth()));
        stage.setY(clamp(y + step, screen.getMinY(), screen.getMaxY() - stage.getHeight()));
    }

    /** The screen a window is on, or the primary one while the window has no position yet. */
    static Rectangle2D screenOf(Stage stage) {
        if (Double.isNaN(stage.getX()) || Double.isNaN(stage.getY())) return Screen.getPrimary().getVisualBounds();
        return Screen.getScreensForRectangle(stage.getX(), stage.getY(), 1, 1).stream().findFirst()
                .orElse(Screen.getPrimary()).getVisualBounds();
    }

    private static double size(double of, double fallback) {
        return Double.isNaN(of) || of <= 0 ? fallback : of;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }

    /** The window icon in every size we ship, so the window manager and the task switcher can pick their own. */
    static void addIcons(Stage stage) {
        for (int size : new int[] {16, 32, 64, 128, 256, 512}) {
            java.io.InputStream in = App.class.getResourceAsStream("/no/syntakserror/lyngvi/gui/icon/icon-" + size + ".png");
            if (in != null) stage.getIcons().add(new javafx.scene.image.Image(in));
        }
    }
}
