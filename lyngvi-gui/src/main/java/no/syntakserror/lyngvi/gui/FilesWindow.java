package no.syntakserror.lyngvi.gui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import no.syntakserror.lyngvi.gui.view.FilesController;
import no.syntakserror.lyngvi.gui.view.Theme;
import no.syntakserror.lyngvi.gui.viewmodel.Settings;

/**
 * The files pane in a window of its own: the working copies and output of the conversation the main window has
 * open, read and edited by {@link FilesController} as before, but beside the window rather than inside it — so
 * it is the user who gives it its size and its place on the desktop. The size and position it is left at are
 * remembered, and it opens there the next time.
 *
 * <p>One window per main window, built with it so that the chat can hand it the conversation that is selected,
 * and shown when «Files» is pressed. It has no owner: an owned window is always kept in front of its owner,
 * which is the one thing a pane pulled out of the main window must not be. Nothing therefore closes it for us,
 * so the main window closes it on its way out — see {@link MainController#shutdown()}.
 */
final class FilesWindow extends Stage {

    /** The size it gets before the user has given it one: tall as a main window, and narrower. */
    private static final double DEFAULT_WIDTH = 900;
    private static final double DEFAULT_HEIGHT = 760;
    /** The gap left between it and the window it was opened beside. */
    private static final double GAP = 12;

    private final Stage main;
    private final Settings settings;
    private final FilesController controller;
    private final String title;

    FilesWindow(Stage main, Services services) {
        this.main = main;
        this.settings = services.settings();
        this.title = services.bundle().getString("gui.files.window.title");
        FXMLLoader loader = new FXMLLoader(App.class.getResource("/no/syntakserror/lyngvi/gui/fxml/files.fxml"), services.bundle());
        loader.setControllerFactory(type -> new FilesController(services));
        Parent pane;
        try {
            pane = loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load files.fxml", e);
        }
        this.controller = loader.getController();
        setTitle(title);
        MainWindow.addIcons(this);
        setMinWidth(480);
        setMinHeight(320);
        StackPane root = new StackPane(pane);
        root.getStyleClass().add("main-root");
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        Theme.apply(scene, settings.theme());
        setScene(scene);
        // Where the window was left is worth keeping whether it was closed by itself or with the main window.
        setOnHiding(e -> remember());
    }

    FilesController controller() {
        return controller;
    }

    /** Shows the window, or raises it when it is already on screen behind something else. */
    void open() {
        if (isShowing()) {
            if (isIconified()) setIconified(false);
            toFront();
            requestFocus();
            return;
        }
        place();
        show();
    }

    /** Names the sandbox the files belong to, so one of these windows can be told from another's. */
    void showSandbox(Optional<String> sandbox) {
        setTitle(sandbox.map(name -> title + " — " + name).orElse(title));
    }

    /** Where it was left, and the first time: beside the main window, on the same screen as it. */
    private void place() {
        if (App.restoreBounds(this, settings.filesWindowBounds())) return;
        Rectangle2D screen = MainWindow.screenOf(main);
        setWidth(Math.min(DEFAULT_WIDTH, screen.getWidth()));
        setHeight(Math.min(DEFAULT_HEIGHT, screen.getHeight()));
        if (Double.isNaN(main.getX()) || Double.isNaN(main.getY())) {
            centerOnScreen();
            return;
        }
        // To the right of the main window where the screen has room for it, and against the right-hand edge where
        // it has not: either way it comes up where the user is looking, to be dragged wherever they want it.
        double beside = main.getX() + main.getWidth() + GAP;
        double x = beside + getWidth() <= screen.getMaxX() ? beside : screen.getMaxX() - getWidth();
        setX(Math.max(screen.getMinX(), x));
        setY(Math.max(screen.getMinY(), Math.min(main.getY(), screen.getMaxY() - getHeight())));
    }

    private void remember() {
        if (Double.isNaN(getX()) || Double.isNaN(getY()) || getWidth() <= 0 || getHeight() <= 0) return;
        settings.setFilesWindowBounds(new Settings.WindowBounds(getX(), getY(), getWidth(), getHeight(), isMaximized()));
        settings.save();
    }
}
