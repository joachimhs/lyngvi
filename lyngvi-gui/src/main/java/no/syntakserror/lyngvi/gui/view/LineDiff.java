package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A line-level diff of two texts (an edit's old and new string) as {@link DiffPaneBuilder.Line}s without numbers:
 * unchanged lines are context, the rest removed or added. Longest-common-subsequence over lines; very large inputs
 * fall back to "everything removed, everything added" so a pasted file never stalls the UI thread.
 */
public final class LineDiff {

    /** Above this many line pairs the quadratic LCS table is skipped. */
    private static final long MAX_CELLS = 4_000_000L;

    private LineDiff() {}

    public static List<DiffPaneBuilder.Line> of(String oldText, String newText) {
        List<String> a = lines(oldText);
        List<String> b = lines(newText);
        List<DiffPaneBuilder.Line> out = new ArrayList<>();
        if ((long) a.size() * b.size() > MAX_CELLS) {
            for (String s : a) out.add(new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.REMOVED, null, null, s));
            for (String s : b) out.add(new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.ADDED, null, null, s));
            return out;
        }
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                out.add(new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.CONTEXT, null, null, a.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.REMOVED, null, null, a.get(i++)));
            } else {
                out.add(new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.ADDED, null, null, b.get(j++)));
            }
        }
        while (i < a.size()) out.add(new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.REMOVED, null, null, a.get(i++)));
        while (j < b.size()) out.add(new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.ADDED, null, null, b.get(j++)));
        return out;
    }

    /** Every line of {@code text} as added (a new file's content). */
    public static List<DiffPaneBuilder.Line> added(String text) {
        List<DiffPaneBuilder.Line> out = new ArrayList<>();
        for (String s : lines(text)) out.add(new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.ADDED, null, null, s));
        return out;
    }

    /** The lines in +/− form, for copying. */
    public static String text(List<DiffPaneBuilder.Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (DiffPaneBuilder.Line l : lines) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(switch (l.kind()) {
                case ADDED -> "+";
                case REMOVED -> "-";
                default -> " ";
            }).append(l.code());
        }
        return sb.toString();
    }

    /** The text split into lines; a trailing newline does not add an empty line. */
    static List<String> lines(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        String t = text.replace("\r\n", "\n");
        if (t.endsWith("\n")) t = t.substring(0, t.length() - 1);
        return List.of(t.split("\n", -1));
    }
}
