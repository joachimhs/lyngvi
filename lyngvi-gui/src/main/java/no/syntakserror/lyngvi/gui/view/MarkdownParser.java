package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small block/inline markdown parser: enough for agent replies (headings, lists, code, quotes, tables, emphasis,
 * links).
 */
public final class MarkdownParser {

    public enum Kind { HEADING, PARAGRAPH, LIST, CODE, QUOTE, RULE, TABLE }

    /** How a table column sits in its cells, from the dashes under the header ({@code :---}, {@code ---:}). */
    public enum Align { LEFT, CENTER, RIGHT }

    /** A pipe table: the header cells, the body rows — every row as wide as the header — and the column alignments. */
    public record Table(List<String> header, List<List<String>> rows, List<Align> aligns) {

        public Align align(int column) {
            return column < aligns.size() ? aligns.get(column) : Align.LEFT;
        }
    }

    public record Block(Kind kind, int level, String text, List<String> items, boolean ordered, String language,
                        Table table) {
        static Block of(Kind kind, String text) {
            return new Block(kind, 0, text, List.of(), false, null, null);
        }
    }

    /** One styled run of inline text. */
    public record Run(String text, boolean bold, boolean italic, boolean code, String link) {}

    private static final Pattern INLINE = Pattern.compile("(`[^`]+`)|(\\*\\*[^*]+\\*\\*)|(__[^_]+__)|(\\*[^*\\s][^*]*\\*)|(_[^_\\s][^_]*_)|(\\[[^\\]]+]\\([^)]+\\))|(https?://\\S+)");

    /** One cell of the dashed line under a table header: dashes, with a colon on the side the column aligns to. */
    private static final Pattern DELIMITER = Pattern.compile(":?-+:?");

    private MarkdownParser() {}

    public static List<Block> parse(String markdown) {
        List<Block> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return out;
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        StringBuilder para = new StringBuilder();
        while (i < lines.length) {
            String line = lines[i];
            String t = line.strip();
            if (t.startsWith("```")) {
                flush(out, para);
                String lang = t.substring(3).strip();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].strip().startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                i++;
                out.add(new Block(Kind.CODE, 0, code.toString().stripTrailing(), List.of(), false, lang.isEmpty() ? null : lang, null));
                continue;
            }
            if (t.isEmpty()) {
                flush(out, para);
                i++;
                continue;
            }
            if (t.matches("#{1,6}\\s+.*")) {
                flush(out, para);
                int level = t.indexOf(' ');
                out.add(new Block(Kind.HEADING, level, t.substring(level).strip(), List.of(), false, null, null));
                i++;
                continue;
            }
            if (t.matches("(-{3,}|\\*{3,}|_{3,})")) {
                flush(out, para);
                out.add(Block.of(Kind.RULE, ""));
                i++;
                continue;
            }
            if (t.startsWith(">")) {
                flush(out, para);
                StringBuilder q = new StringBuilder();
                while (i < lines.length && lines[i].strip().startsWith(">")) {
                    q.append(lines[i].strip().substring(1).strip()).append('\n');
                    i++;
                }
                out.add(Block.of(Kind.QUOTE, q.toString().strip()));
                continue;
            }
            boolean bullet = t.matches("[-*+]\\s+.*");
            boolean numbered = t.matches("\\d+[.)]\\s+.*");
            if (bullet || numbered) {
                flush(out, para);
                List<String> items = new ArrayList<>();
                while (i < lines.length) {
                    String s = lines[i].strip();
                    if (s.matches("[-*+]\\s+.*")) items.add(s.substring(1).strip());
                    else if (s.matches("\\d+[.)]\\s+.*")) items.add(s.replaceFirst("^\\d+[.)]\\s+", ""));
                    else if (!s.isEmpty() && lines[i].startsWith("  ") && !items.isEmpty()) items.set(items.size() - 1, items.getLast() + "\n" + s);
                    else break;
                    i++;
                }
                out.add(new Block(Kind.LIST, 0, "", List.copyOf(items), numbered, null, null));
                continue;
            }
            Table table = tableAt(lines, i);
            if (table != null) {
                flush(out, para);
                i += 2 + table.rows().size();
                out.add(new Block(Kind.TABLE, 0, "", List.of(), false, null, table));
                continue;
            }
            if (!para.isEmpty()) para.append('\n');
            para.append(t);
            i++;
        }
        flush(out, para);
        return out;
    }

    /**
     * The pipe table starting at {@code from}, or null when there is none: a header row, a dashed row that says how
     * many columns the table has and how they align, and the rows under it until a line that is no longer a row. A
     * short row is filled out with empty cells and a long one cut, so every row is as wide as the header — that is
     * what lets the view lay the table out as a grid.
     */
    private static Table tableAt(String[] lines, int from) {
        if (from + 1 >= lines.length || !lines[from].contains("|")) return null;
        List<Align> aligns = alignments(lines[from + 1]);
        List<String> header = cells(lines[from]);
        if (aligns == null || aligns.size() != header.size()) return null;
        List<List<String>> rows = new ArrayList<>();
        for (int i = from + 2; i < lines.length && isRow(lines[i]); i++) rows.add(fit(cells(lines[i]), header.size()));
        return new Table(List.copyOf(header), List.copyOf(rows), List.copyOf(aligns));
    }

    private static boolean isRow(String line) {
        return line.contains("|") && !line.isBlank();
    }

    /** The column alignments a dashed row states, or null when the line is not one. */
    private static List<Align> alignments(String line) {
        if (!line.contains("-")) return null;
        List<Align> out = new ArrayList<>();
        for (String cell : cells(line)) {
            if (!DELIMITER.matcher(cell).matches()) return null;
            boolean left = cell.startsWith(":");
            boolean right = cell.endsWith(":");
            out.add(left && right ? Align.CENTER : right ? Align.RIGHT : Align.LEFT);
        }
        return out.isEmpty() ? null : out;
    }

    /** The cells of one row: the outer pipes are the table's edge, an escaped {@code \|} is a pipe in the text. */
    private static List<String> cells(String line) {
        String t = line.strip();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length() - 1);
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '|') {
                out.add(cell.toString().strip());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(cell.toString().strip());
        return out;
    }

    private static List<String> fit(List<String> row, int width) {
        List<String> out = new ArrayList<>(row.subList(0, Math.min(row.size(), width)));
        while (out.size() < width) out.add("");
        return List.copyOf(out);
    }

    private static void flush(List<Block> out, StringBuilder para) {
        if (!para.isEmpty()) {
            out.add(Block.of(Kind.PARAGRAPH, para.toString()));
            para.setLength(0);
        }
    }

    public static List<Run> inline(String text) {
        List<Run> runs = new ArrayList<>();
        if (text == null || text.isEmpty()) return runs;
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) runs.add(new Run(text.substring(last, m.start()), false, false, false, null));
            String g = m.group();
            if (m.group(1) != null) runs.add(new Run(g.substring(1, g.length() - 1), false, false, true, null));
            else if (m.group(2) != null || m.group(3) != null) runs.add(new Run(g.substring(2, g.length() - 2), true, false, false, null));
            else if (m.group(4) != null || m.group(5) != null) runs.add(new Run(g.substring(1, g.length() - 1), false, true, false, null));
            else if (m.group(6) != null) {
                int close = g.indexOf("](");
                runs.add(new Run(g.substring(1, close), false, false, false, g.substring(close + 2, g.length() - 1)));
            } else runs.add(new Run(g, false, false, false, g));
            last = m.end();
        }
        if (last < text.length()) runs.add(new Run(text.substring(last), false, false, false, null));
        return runs;
    }

    /** The text without markup, for previews and titles. */
    public static String plainText(String markdown) {
        StringBuilder sb = new StringBuilder();
        for (Run r : inline(markdown == null ? "" : markdown)) sb.append(r.text());
        return sb.toString();
    }
}
