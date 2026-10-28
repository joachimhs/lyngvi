package no.syntakserror.lyngvi.gui.view;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * What the sandbox switcher in the toolbar offers: the sandbox this window shows, then the ones the machine has
 * worked with before, each of them once and in the order they were last opened. Pure, so the content of the menu
 * can be checked without a toolkit; {@code MainController} turns each entry into a menu item.
 */
public final class SandboxChoices {

    private SandboxChoices() {}

    /**
     * One line of the switcher.
     *
     * @param dir             the sandbox folder
     * @param open            this window shows it — the item the menu marks as chosen
     * @param inAnotherWindow another window shows it, so clicking it raises that window rather than switching here
     */
    public record Entry(Path dir, boolean open, boolean inAnotherWindow) {

        /** A sandbox no window has: the one that can be opened in a window of its own. */
        public boolean free() {
            return !open && !inAnotherWindow;
        }
    }

    /**
     * The switcher's lines.
     *
     * @param open      the sandbox this window shows, or {@code null} when it has none
     * @param recent    the remembered sandboxes, most recently opened first
     * @param stillThere whether a remembered folder is still on disk; one that is gone is left out rather than
     *                  offered as something to click, since opening it can only fail
     * @param elsewhere whether another window shows the sandbox
     */
    public static List<Entry> of(Path open, List<Path> recent, Predicate<Path> stillThere, Predicate<Path> elsewhere) {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        if (open != null) dirs.add(normalise(open));
        for (Path dir : recent) {
            Path d = normalise(dir);
            if (!dirs.contains(d) && stillThere.test(d)) dirs.add(d);
        }
        List<Entry> entries = new ArrayList<>();
        for (Path dir : dirs) {
            boolean isOpen = open != null && dir.equals(normalise(open));
            entries.add(new Entry(dir, isOpen, !isOpen && elsewhere.test(dir)));
        }
        return List.copyOf(entries);
    }

    private static Path normalise(Path dir) {
        return dir.toAbsolutePath().normalize();
    }
}
