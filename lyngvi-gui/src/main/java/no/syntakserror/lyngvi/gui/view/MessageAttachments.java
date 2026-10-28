package no.syntakserror.lyngvi.gui.view;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.geometry.Dimension2D;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * What the human attached to a message, shown under its text: an image as the picture itself, everything else as a
 * chip with the file name. A thumbnail opens the file at full size in an {@link ImageWindow} when it is clicked.
 *
 * <p>A conversation records an attachment as the path the agent sees ({@code /work/_attachments/shot.png}) and a
 * turn still running as the bare file name; both name the same file on this machine, which is why only the last
 * segment is ever used here.
 */
final class MessageAttachments {

    /** The suffixes JavaFX decodes; anything else stays a chip, whatever it is. */
    private static final Set<String> IMAGE_SUFFIXES = Set.of(".png", ".jpg", ".jpeg", ".gif", ".bmp");
    /** How large a thumbnail may be in the transcript; the full size is one click away. */
    private static final double THUMB_WIDTH = 360;
    private static final double THUMB_HEIGHT = 260;

    private MessageAttachments() {}

    /** The file name of an attachment as the conversation records it, which may be a path. */
    static String name(String attachment) {
        String s = attachment.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        return slash < 0 ? s : s.substring(slash + 1);
    }

    static boolean isImage(String attachment) {
        String n = name(attachment).toLowerCase(Locale.ROOT);
        return IMAGE_SUFFIXES.stream().anyMatch(n::endsWith);
    }

    /**
     * The attachments of one message as a row that wraps. {@code file} maps a file name to the copy on this
     * machine (null when there is none, which leaves a chip), {@code open} is called with it on a click.
     */
    static Node node(List<String> attachments, Function<String, Path> file, Consumer<Path> open, ResourceBundle b) {
        FlowPane row = new FlowPane(8, 8);
        row.setRowValignment(VPos.TOP);
        row.getStyleClass().add("attachment-row");
        for (String attachment : attachments) {
            String name = name(attachment);
            Path path = isImage(name) ? file.apply(name) : null;
            row.getChildren().add(path != null && Files.isReadable(path) ? thumbnail(path, name, open, b) : chip(name));
        }
        return row;
    }

    private static Label chip(String name) {
        Label chip = new Label("📎 " + name);
        chip.getStyleClass().add("attachment-chip");
        return chip;
    }

    /**
     * One image, shrunk to fit the thumbnail and never blown up past its own size. The size is read from the
     * file's header first so that the picture can be decoded in the background at exactly the size it is shown:
     * a conversation full of screenshots then neither stalls the transcript nor holds a megabyte of pixels per
     * picture. A file nothing can read falls back to the chip in its place.
     */
    private static Node thumbnail(Path file, String name, Consumer<Path> open, ResourceBundle b) {
        Dimension2D size = size(file);
        if (size == null) return chip(name);
        double scale = Math.min(1, Math.min(THUMB_WIDTH / size.getWidth(), THUMB_HEIGHT / size.getHeight()));
        double width = Math.max(1, Math.round(size.getWidth() * scale));
        Image image = new Image(file.toUri().toString(), width, 0, true, true, true);
        ImageView view = new ImageView(image);
        view.setFitWidth(width);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        StackPane frame = new StackPane(view);
        // a pane is free to grow into its row, which here would frame the picture in empty space
        frame.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        frame.getStyleClass().add("attachment-image");
        frame.setCursor(Cursor.HAND);
        Tooltip.install(frame, new Tooltip(b.getString("gui.chat.attachment.open").replace("{0}", name)));
        frame.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
                open.accept(file);
                e.consume();
            }
        });
        image.errorProperty().addListener((o, was, failed) -> {
            if (failed) {
                frame.getChildren().setAll(chip(name));
                frame.getStyleClass().remove("attachment-image");
                frame.setCursor(Cursor.DEFAULT);
                frame.setOnMouseClicked(null);
            }
        });
        return frame;
    }

    /** What the file says it is, from its header alone; null when no reader recognises it. */
    private static Dimension2D size(Path file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new Dimension2D(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
