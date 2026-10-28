package no.syntakserror.lyngvi.gui.view;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javax.imageio.ImageIO;

/**
 * An image pasted into the composer, written to disk as a PNG: attachments reach the container as files under
 * {@code /work/_attachments/}, so a clipboard image needs a file of its own. The pixels are copied row by row
 * because {@code SwingFXUtils} lives in {@code javafx.swing}, which this app is neither built nor packaged with.
 */
final class ClipboardImage {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ClipboardImage() {}

    /** {@code pasted-20260907-143012.png} in {@code dir}, with «-2», «-3» … when that name is taken. */
    static Path fileName(Path dir, Instant at) {
        String stamp = STAMP.format(at.atZone(ZoneId.systemDefault()));
        Path file = dir.resolve("pasted-" + stamp + ".png");
        for (int n = 2; Files.exists(file); n++) file = dir.resolve("pasted-" + stamp + "-" + n + ".png");
        return file;
    }

    /** Writes the image as a PNG in {@code dir} (created if need be) and returns the file. */
    static Path write(Image image, Path dir, Instant at) throws IOException {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        PixelReader pixels = image.getPixelReader();
        if (w <= 0 || h <= 0 || pixels == null) throw new IOException("the clipboard image is empty");
        BufferedImage buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            pixels.getPixels(0, y, w, 1, PixelFormat.getIntArgbInstance(), row, 0, w);
            buffer.setRGB(0, y, w, 1, row, 0, w);
        }
        Files.createDirectories(dir);
        Path file = fileName(dir, at);
        if (!ImageIO.write(buffer, "png", file.toFile())) throw new IOException("no PNG writer is available");
        return file;
    }
}
