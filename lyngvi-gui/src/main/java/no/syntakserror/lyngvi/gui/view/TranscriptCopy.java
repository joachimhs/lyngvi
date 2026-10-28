package no.syntakserror.lyngvi.gui.view;

import java.util.List;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;

/**
 * The arithmetic behind selecting transcript text: which run the pointer is pointing at, and what a range over the
 * runs yields as text. A run is one piece of laid-out text — a paragraph, a label, a diff line — and the transcript
 * is those runs in reading order. This is kept apart from {@link TranscriptSelection} because it needs no toolkit
 * and no scene, so every rule here is unit-tested.
 */
final class TranscriptCopy {

    /** What a text flow counts a child that is not text as; it holds one position and says nothing. */
    private static final String EMBEDDED = "\uFFFC";

    private TranscriptCopy() {}

    /**
     * One run of text: what it says, the bubble it belongs to and the line it shares with its neighbours. Runs on
     * the same line (a bullet and its item, a diff line's sign and its code) have the same {@code line}.
     */
    record Run(String text, int bubble, int line) {}

    /** A range over the runs: where the drag started, and where it is now — in either order. */
    record Range(int fromRun, int fromChar, int toRun, int toChar) {

        static final Range NONE = new Range(-1, 0, -1, 0);

        /** The same range read top to bottom, so the rest of the class can ignore which way the drag went. */
        static Range of(int anchorRun, int anchorChar, int focusRun, int focusChar) {
            if (anchorRun < 0 || focusRun < 0) return NONE;
            boolean backwards = focusRun < anchorRun || (focusRun == anchorRun && focusChar < anchorChar);
            return backwards
                    ? new Range(focusRun, focusChar, anchorRun, anchorChar)
                    : new Range(anchorRun, anchorChar, focusRun, focusChar);
        }

        /** A range that holds no character at all: nothing is selected, and there is nothing to copy. */
        boolean isEmpty() {
            return fromRun < 0 || toRun < 0 || (fromRun == toRun && fromChar == toChar);
        }

        boolean covers(int run) {
            return !isEmpty() && run >= fromRun && run <= toRun;
        }

        /** Where the selection starts in a run of the given length, or −1 when the run is outside it. */
        int startIn(int run, int length) {
            if (!covers(run)) return -1;
            return run == fromRun ? Math.max(0, Math.min(fromChar, length)) : 0;
        }

        /** Where the selection ends in a run of the given length, or −1 when the run is outside it. */
        int endIn(int run, int length) {
            if (!covers(run)) return -1;
            return run == toRun ? Math.max(0, Math.min(toChar, length)) : length;
        }
    }

    /**
     * The selected text: runs on the same line follow each other as they are, a new line in the same bubble is a
     * newline and a new bubble a blank line, so what is copied reads the way the transcript reads.
     */
    static String text(List<Run> runs, Range range) {
        StringBuilder sb = new StringBuilder();
        Run previous = null;
        for (int i = Math.max(0, range.fromRun()); i < runs.size() && i <= range.toRun(); i++) {
            if (!range.covers(i)) continue;
            Run run = runs.get(i);
            String text = run.text() == null ? "" : run.text();
            String part = text.substring(range.startIn(i, text.length()), range.endIn(i, text.length()))
                    .replace(EMBEDDED, "");
            if (part.isEmpty()) continue;
            if (previous != null) {
                sb.append(previous.bubble() != run.bubble() ? "\n\n" : previous.line() != run.line() ? "\n" : "");
            }
            sb.append(part);
            previous = run;
        }
        return sb.toString();
    }

    /** Where a word begins and ends in a run. */
    record Word(int from, int to) {}

    /**
     * The word around a position, for a double click: letters, digits and underscores, and the dots, dashes and
     * slashes between them — a path, a file name or a model id is one word, a sentence's full stop is not part of
     * the word before it. A click just past a word takes that word; a click on anything else takes one character.
     */
    static Word word(String text, int at) {
        int length = text == null ? 0 : text.length();
        if (length == 0) return new Word(0, 0);
        int i = Math.max(0, Math.min(at, length - 1));
        if (!isWord(text.charAt(i)) && i > 0 && isWord(text.charAt(i - 1))) i--;
        if (!isWord(text.charAt(i))) return new Word(i, i + 1);
        int from = i;
        int to = i + 1;
        while (from > 0 && (isWord(text.charAt(from - 1))
                || (isJoiner(text.charAt(from - 1)) && from > 1 && isWord(text.charAt(from - 2))))) {
            from -= isWord(text.charAt(from - 1)) ? 1 : 2;
        }
        while (to < length && (isWord(text.charAt(to))
                || (isJoiner(text.charAt(to)) && to + 1 < length && isWord(text.charAt(to + 1))))) {
            to += isWord(text.charAt(to)) ? 1 : 2;
        }
        return new Word(from, to);
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isJoiner(char c) {
        return c == '.' || c == '-' || c == '/';
    }

    /**
     * The part of a run that can be seen inside the box that scrolls it. A code block scrolls its long lines in a
     * scroll pane of its own, so a run there reaches past the block: the pointer must be hit-tested, and the
     * highlight drawn, onto what is on the screen rather than onto the characters hidden behind the block's edge. A
     * run scrolled out of sight altogether becomes an empty box, which no point is inside.
     */
    static Bounds clamp(Bounds run, Bounds view) {
        double minX = Math.max(run.getMinX(), view.getMinX());
        double minY = Math.max(run.getMinY(), view.getMinY());
        return new BoundingBox(minX, minY,
                Math.max(0, Math.min(run.getMaxX(), view.getMaxX()) - minX),
                Math.max(0, Math.min(run.getMaxY(), view.getMaxY()) - minY));
    }

    /**
     * The run the given point picks out: the one under it; failing that the last run to its left on the same line,
     * or the first run on that line when the point is left of them all; and failing that — the point is in the gap
     * between lines or below the last one — the last run that begins above it.
     */
    static int nearest(List<Bounds> runs, double x, double y) {
        if (runs.isEmpty()) return -1;
        int onLine = -1;
        for (int i = 0; i < runs.size(); i++) {
            Bounds b = runs.get(i);
            if (y < b.getMinY() || y > b.getMaxY()) continue;
            if (x >= b.getMinX() && x <= b.getMaxX()) return i;
            if (onLine < 0 || b.getMaxX() <= x) onLine = i;
        }
        if (onLine >= 0) return onLine;
        int above = 0;
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).getMinY() <= y) above = i;
        }
        return above;
    }
}
