package no.syntakserror.lyngvi.gui.view;

/**
 * The arithmetic behind the transcript's scrolling: follow new content only while the user is at the bottom, and
 * keep the viewport's top line in place when something below it (an expanded tool call) changes height.
 */
final class TranscriptScroll {

    /** Below this fraction the user has scrolled up and new content must not pull the view down. */
    static final double FOLLOW_THRESHOLD = 0.98;

    private TranscriptScroll() {}

    static boolean follows(double vvalue) {
        return vvalue >= FOLLOW_THRESHOLD;
    }

    /** The pixel offset of the viewport's top edge for a scroll fraction. */
    static double offset(double vvalue, double contentHeight, double viewportHeight) {
        double range = contentHeight - viewportHeight;
        return range <= 0 ? 0 : Math.max(0, vvalue * range);
    }

    /** The scroll fraction that puts the viewport's top edge at the given pixel offset, clamped to [0, 1]. */
    static double vvalue(double offset, double contentHeight, double viewportHeight) {
        double range = contentHeight - viewportHeight;
        return range <= 0 ? 0 : Math.min(1.0, Math.max(0, offset / range));
    }
}
