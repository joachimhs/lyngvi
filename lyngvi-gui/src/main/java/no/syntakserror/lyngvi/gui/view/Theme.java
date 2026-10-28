package no.syntakserror.lyngvi.gui.view;

import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import no.syntakserror.lyngvi.gui.viewmodel.Settings;

/**
 * Applies the light or dark stylesheet set. The theme is process-wide: once applied, every window that opens
 * later (dialogs, alerts, the diff window) receives the same stylesheets, and switching re-styles the open ones.
 * Popups (menus, tooltips) inherit from their owner and are left alone.
 */
public final class Theme {

    private static final String APP = Theme.class.getResource("/no/syntakserror/lyngvi/gui/css/app.css").toExternalForm();
    private static final String DARK = Theme.class.getResource("/no/syntakserror/lyngvi/gui/css/dark.css").toExternalForm();

    private static Settings.Theme current = Settings.Theme.LIGHT;
    private static boolean installed;

    private Theme() {}

    /** Sets the theme for the whole application and styles {@code scene} plus every open window. */
    public static void apply(Scene scene, Settings.Theme theme) {
        current = theme == null ? Settings.Theme.LIGHT : theme;
        install();
        style(scene);
        for (Window w : Window.getWindows()) {
            if (!(w instanceof PopupWindow) && w.getScene() != null && w.getScene() != scene) style(w.getScene());
        }
    }

    /** The theme in effect. */
    public static Settings.Theme current() {
        return current;
    }

    private static void install() {
        if (installed) return;
        installed = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    if (!(w instanceof PopupWindow) && w.getScene() != null) style(w.getScene());
                }
            }
        });
    }

    private static void style(Scene scene) {
        if (scene == null) return;
        if (!scene.getStylesheets().contains(APP)) scene.getStylesheets().add(APP);
        scene.getStylesheets().remove(DARK);
        if (current == Settings.Theme.DARK) scene.getStylesheets().add(DARK);
    }
}
