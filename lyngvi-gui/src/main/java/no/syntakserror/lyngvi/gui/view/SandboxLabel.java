package no.syntakserror.lyngvi.gui.view;

import java.nio.file.Path;

/**
 * How a sandbox folder is written in the window: in the toolbar as a path with the home directory shortened to
 * {@code ~}, and in the window title as the folder's own name, which is what tells two windows apart in the task
 * switcher. Pure: no toolkit, no file system.
 */
public final class SandboxLabel {

    private SandboxLabel() {}

    /** The path as the user knows it — {@code ~/Projects/Sandbox1} inside the home directory, in full elsewhere. */
    public static String path(Path dir, Path home) {
        Path d = dir.toAbsolutePath().normalize();
        if (home == null) return d.toString();
        Path h = home.toAbsolutePath().normalize();
        if (d.equals(h)) return "~";
        if (!d.startsWith(h)) return d.toString();
        return "~" + d.getFileSystem().getSeparator() + h.relativize(d);
    }

    /** The folder's own name; the whole path for a root directory, which has no name of its own. */
    public static String name(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        Path name = d.getFileName();
        return name == null ? d.toString() : name.toString();
    }
}
