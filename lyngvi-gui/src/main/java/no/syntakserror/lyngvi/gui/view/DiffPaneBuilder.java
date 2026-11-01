package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a unified diff into files and numbered lines; the view renders them with a gutter. */
public final class DiffPaneBuilder {

    public enum Kind { HUNK, CONTEXT, ADDED, REMOVED, META }

    /** What happened to a file, as the diff headers tell it. */
    public enum Change {
        ADDED, DELETED, MODIFIED, RENAMED;

        /** The CSS class and badge text: {@code added}, {@code deleted}, {@code modified}, {@code renamed}. */
        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Line(Kind kind, Integer oldNumber, Integer newNumber, String code) {}

    public record FileDiff(String path, String oldPath, Change change, List<Line> lines, int additions, int deletions) {
        /** The file name without its directory, for compact lists. */
        public String name() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }

        /** The directory part of the path, empty for a file at the root. */
        public String directory() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? "" : path.substring(0, slash);
        }
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
    private static final Pattern GIT = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern NEW_FILE = Pattern.compile("^\\+\\+\\+ (?:b/)?(\\S+).*$");
    private static final Pattern OLD_FILE = Pattern.compile("^--- (?:a/)?(\\S+).*$");
    private static final String DEV_NULL = "/dev/null";

    private DiffPaneBuilder() {}

    /** One file being collected while the diff is read. */
    private static final class Current {
        String path;
        String oldPath;
        boolean added;
        boolean deleted;
        List<Line> lines = new ArrayList<>();
        int add;
        int del;

        boolean present() {
            return path != null || !lines.isEmpty();
        }

        FileDiff toFile() {
            String p = path == null ? (oldPath == null || DEV_NULL.equals(oldPath) ? "" : oldPath) : path;
            String o = DEV_NULL.equals(oldPath) ? null : oldPath;
            Change change = added || DEV_NULL.equals(oldPath) ? Change.ADDED
                    : deleted || DEV_NULL.equals(path) ? Change.DELETED
                    : o != null && !o.isBlank() && !o.equals(p) ? Change.RENAMED
                    : Change.MODIFIED;
            return new FileDiff(DEV_NULL.equals(p) ? (o == null ? "" : o) : p, o, change, List.copyOf(lines), add, del);
        }
    }

    public static List<FileDiff> parse(String diff) {
        List<FileDiff> out = new ArrayList<>();
        if (diff == null || diff.isBlank()) return out;
        Current cur = new Current();
        int oldN = 0;
        int newN = 0;
        int oldLeft = 0;
        int newLeft = 0;
        boolean inHunk = false;
        boolean gitHeader = false;
        for (String raw : diff.split("\n")) {
            Matcher g = GIT.matcher(raw);
            if (g.matches()) {
                if (cur.present()) out.add(cur.toFile());
                cur = new Current();
                cur.path = g.group(2);
                cur.oldPath = g.group(1);
                inHunk = false;
                gitHeader = true;
                continue;
            }
            Matcher h = HUNK.matcher(raw);
            if (h.matches()) {
                oldN = Integer.parseInt(h.group(1));
                oldLeft = h.group(2) == null ? 1 : Integer.parseInt(h.group(2));
                newN = Integer.parseInt(h.group(3));
                newLeft = h.group(4) == null ? 1 : Integer.parseInt(h.group(4));
                inHunk = oldLeft > 0 || newLeft > 0;
                cur.lines.add(new Line(Kind.HUNK, null, null, raw));
                continue;
            }
            if (!inHunk) {
                // index/---/+++ headers between hunks: with a git header the file entry already carries the paths;
                // a plain "diff -u" names its files here instead, and a new file starts at "---".
                if (raw.startsWith("new file mode")) cur.added = true;
                else if (raw.startsWith("deleted file mode")) cur.deleted = true;
                Matcher o = OLD_FILE.matcher(raw);
                Matcher n = NEW_FILE.matcher(raw);
                if (!gitHeader && o.matches()) {
                    if (!cur.lines.isEmpty()) {
                        out.add(cur.toFile());
                        cur = new Current();
                    }
                    cur.oldPath = o.group(1);
                } else if (!gitHeader && n.matches()) {
                    cur.path = n.group(1);
                }
                continue;
            }
            // The hunk header says how many lines follow, so a removed SQL comment ("--- x") is never taken for a header.
            if (raw.startsWith("+")) {
                cur.lines.add(new Line(Kind.ADDED, null, newN++, raw.substring(1)));
                cur.add++;
                newLeft--;
            } else if (raw.startsWith("-")) {
                cur.lines.add(new Line(Kind.REMOVED, oldN++, null, raw.substring(1)));
                cur.del++;
                oldLeft--;
            } else if (raw.startsWith("\\")) {
                cur.lines.add(new Line(Kind.META, null, null, raw));
            } else {
                cur.lines.add(new Line(Kind.CONTEXT, oldN++, newN++, raw.isEmpty() ? "" : raw.substring(1)));
                oldLeft--;
                newLeft--;
            }
            if (oldLeft <= 0 && newLeft <= 0) inHunk = false;
        }
        if (cur.present()) out.add(cur.toFile());
        return out;
    }

    /** True when the text is a unified diff: a hunk header followed by at least one added or removed line. */
    public static boolean looksLikeDiff(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hunk = false;
        for (String raw : text.split("\n")) {
            if (raw.startsWith("diff --git ") || HUNK.matcher(raw).matches()) hunk = true;
            else if (hunk && (raw.startsWith("+") || raw.startsWith("-")) && !raw.startsWith("+++ ") && !raw.startsWith("--- ")) return true;
        }
        return false;
    }

    /** One line of a diff shown without numbering (markdown code blocks, tool output): classified by prefix only. */
    public static Kind kindOf(String raw) {
        if (raw.startsWith("@@")) return Kind.HUNK;
        if (raw.startsWith("+++ ") || raw.startsWith("--- ") || raw.startsWith("diff ") || raw.startsWith("index ") || raw.startsWith("\\")) return Kind.META;
        if (raw.startsWith("+")) return Kind.ADDED;
        if (raw.startsWith("-")) return Kind.REMOVED;
        return Kind.CONTEXT;
    }

    /** What copying selected lines yields: code only, no gutter and no +/- prefix. */
    public static String copyText(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            if (l.kind() == Kind.HUNK || l.kind() == Kind.META) continue;
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(l.code());
        }
        return sb.toString();
    }

    /**
     * The characters a selection covers, as the clipboard should have them: the code only, no gutter and no +/−,
     * lines joined by newlines and a blank line kept as the blank line it is. The range is
     * {@link TranscriptCopy.Range} over line numbers, the same arithmetic the transcript selects with.
     */
    public static String selectedText(List<Line> lines, TranscriptCopy.Range range) {
        return TranscriptCopy.selected(lines.stream().map(Line::code).toList(), range);
    }

    /**
     * The file as it stands after the change: every line the new side has, removed lines left out. A whole-file
     * diff (hunks with unlimited context, {@code DeliveryService.diff(.., wholeFiles)}) yields the complete file;
     * over ordinary hunks it yields only what the hunks show, so the caller should ask for the whole file first.
     */
    public static String newFileText(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        Kind previous = null;
        boolean endsWithoutNewline = false;
        for (Line l : lines) {
            if (l.kind() == Kind.META && l.code().startsWith("\\")) {
                // «\ No newline at end of file» belongs to the side of the line above it.
                if (previous == Kind.ADDED || previous == Kind.CONTEXT) endsWithoutNewline = true;
                continue;
            }
            if (l.kind() == Kind.ADDED || l.kind() == Kind.CONTEXT) sb.append(l.code()).append('\n');
            previous = l.kind();
        }
        if (endsWithoutNewline && !sb.isEmpty()) sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
