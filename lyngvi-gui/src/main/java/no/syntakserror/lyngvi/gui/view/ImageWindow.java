package no.syntakserror.lyngvi.gui.view;

import java.nio.file.Path;
import java.util.ResourceBundle;
import javafx.beans.binding.Bindings;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import no.syntakserror.lyngvi.gui.viewmodel.Settings;

/**
 * One attachment at full size, opened by clicking its thumbnail in the transcript. The window opens at the
 * picture's own size, within what the screen holds, and the picture follows the window from there — scaled down
 * to fit it, never up. Esc closes it.
 */
final class ImageWindow extends Stage {

    /** The most of the screen the window takes when the picture is larger than that. */
    private static final double SCREEN_SHARE = 0.9;
    private static final double MIN_WIDTH = 320;
    private static final double MIN_HEIGHT = 240;

    ImageWindow(Window owner, Path file, Settings.Theme theme, ResourceBundle b) {
        initOwner(owner);
        String name = file.getFileName().toString();
        setTitle(name);
        // Decoded here and now: the thumbnail in the transcript has already read this file, and a window opened
        // by a click is expected to show something.
        Image image = new Image(file.toUri().toString());
        boolean failed = image.isError();
        ImageView view = failed ? null : new ImageView(image);
        StackPane root = new StackPane(failed
                ? new Label(b.getString("gui.chat.attachment.failed").replace("{0}", name))
                : view);
        root.getStyleClass().addAll("main-root", "image-window");
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = failed ? MIN_WIDTH : Math.max(MIN_WIDTH, Math.min(image.getWidth(), screen.getWidth() * SCREEN_SHARE));
        double height = failed ? MIN_HEIGHT : Math.max(MIN_HEIGHT, Math.min(image.getHeight(), screen.getHeight() * SCREEN_SHARE));
        Scene scene = new Scene(root, width, height);
        if (view != null) {
            view.setPreserveRatio(true);
            view.setSmooth(true);
            // measured from the window rather than from the pane the view sits in, which the fit would otherwise size
            view.fitWidthProperty().bind(Bindings.createDoubleBinding(() -> Math.min(image.getWidth(), scene.getWidth()), scene.widthProperty()));
            view.fitHeightProperty().bind(Bindings.createDoubleBinding(() -> Math.min(image.getHeight(), scene.getHeight()), scene.heightProperty()));
        }
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) close();
        });
        Theme.apply(scene, theme);
        setScene(scene);
    }
}
