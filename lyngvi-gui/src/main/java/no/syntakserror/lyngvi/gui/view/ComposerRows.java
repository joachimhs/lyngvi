package no.syntakserror.lyngvi.gui.view;

/**
 * How tall the composer's text area is, in rows: {@value #MIN} while the prompt is short, growing with the text up to
 * {@value #MAX}, after which the area scrolls. Wrapped lines count as rows, so a long prompt pasted as one line grows
 * the area too.
 */
final class ComposerRows {

    /** The resting height, as the FXML declares it. */
    static final int MIN = 3;
    /** Three times the resting height: enough of a large prompt to read without crowding out the transcript. */
    static final int MAX = 9;

    private ComposerRows() {}

    /** The row count for wrapped text that laid out to {@code textHeight} pixels with rows {@code rowHeight} tall. */
    static int of(double textHeight, double rowHeight) {
        if (rowHeight <= 0 || textHeight <= 0) return MIN;
        // a row that is a hair taller than its measured height must not count as two
        int rows = (int) Math.ceil(textHeight / rowHeight - 0.05);
        return Math.max(MIN, Math.min(MAX, rows));
    }
}
