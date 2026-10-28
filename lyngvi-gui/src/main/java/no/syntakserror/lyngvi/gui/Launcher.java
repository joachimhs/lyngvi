package no.syntakserror.lyngvi.gui;

/** Plain {@code main} that does not extend {@code Application}, so a shaded or jlinked launch works. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        App.main(args);
    }
}
