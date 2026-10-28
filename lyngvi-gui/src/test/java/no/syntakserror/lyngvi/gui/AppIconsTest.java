package no.syntakserror.lyngvi.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * The window icons. {@code App} skips a size it cannot find rather than failing, so a renamed or missing
 * resource would cost the window its icon without anything saying so; these assertions are that signal.
 */
class AppIconsTest {

    private static final int[] SIZES = {16, 32, 64, 128, 256, 512};

    @Test
    void everyWindowIconIsOnTheClasspathAtItsOwnSize() throws IOException {
        for (int size : SIZES) {
            String name = "/no/syntakserror/lyngvi/gui/icon/icon-" + size + ".png";
            try (InputStream in = App.class.getResourceAsStream(name)) {
                assertThat(in).as(name).isNotNull();
                assertThat(readPngSize(in)).as(name).containsExactly(size, size);
            }
        }
    }

    /** Width and height from the PNG signature and IHDR, which are the first 24 bytes of any PNG. */
    private static int[] readPngSize(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] signature = new byte[8];
        data.readFully(signature);
        assertThat(new String(signature, 1, 3, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PNG");
        data.readInt(); // IHDR length
        data.readInt(); // "IHDR"
        return new int[] {data.readInt(), data.readInt()};
    }
}
