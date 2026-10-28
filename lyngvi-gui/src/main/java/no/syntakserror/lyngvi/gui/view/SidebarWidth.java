package no.syntakserror.lyngvi.gui.view;

/**
 * How wide the chat's conversation list may be: between {@value #MIN} and {@value #MAX} pixels, {@value #DEFAULT} until
 * the user drags the handle. The lower bound keeps a grouped row readable rather than ellipsized to nothing; the upper
 * one keeps the transcript the larger half of any window the app asks for.
 */
final class SidebarWidth {

    /** The resting width, as the FXML declares it. */
    static final double DEFAULT = 240;
    static final double MIN = 160;
    static final double MAX = 560;

    private SidebarWidth() {}

    /** The nearest width the chat will allow; a width that is not a number (a hand-edited setting) gives the default. */
    static double clamp(double width) {
        if (Double.isNaN(width)) return DEFAULT;
        return Math.max(MIN, Math.min(MAX, width));
    }
}
