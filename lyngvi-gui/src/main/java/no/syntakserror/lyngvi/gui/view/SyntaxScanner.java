package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a line of source into coloured spans, by the rules in a {@link Syntax}. One line at a time, because the
 * view that shows the code is virtualised: a cell that scrolls into sight colours its own line and nothing else,
 * which is what lets a two-megabyte file be opened at all.
 *
 * <p>A line is not self-contained — it can begin inside a block comment or a triple-quoted string — so scanning
 * carries a state in and out. The carry is an int so a whole file's worth of them is an {@code int[]}: {@code 0}
 * is ordinary code, {@code 1+i} is inside block comment {@code i}, and {@code -(1+i)} inside multiline string
 * {@code i}. {@link #carries} runs the scanner over the file once to work them out, without building any tokens;
 * after that every line can be coloured on its own, in any order.
 *
 * <p>No toolkit, no scene, no nodes: everything here is unit-tested.
 */
final class SyntaxScanner {

    /** A coloured span of a line: {@code [from, to)} and what it is. The spans of a line cover all of it. */
    record Token(int from, int to, Syntax.Kind kind) {}

    private SyntaxScanner() {}

    /** The spans of one line, given the state it begins in. */
    static List<Token> tokens(String line, int carry, Syntax syntax) {
        List<Token> out = new ArrayList<>();
        scan(line, carry, syntax, out);
        return out;
    }

    /** The state each line begins in: {@code [0]} is 0, and the rest follow from the lines before them. */
    static int[] carries(List<String> lines, Syntax syntax) {
        int[] carries = new int[lines.size()];
        int carry = 0;
        for (int i = 0; i < lines.size(); i++) {
            carries[i] = carry;
            carry = scan(lines.get(i), carry, syntax, null);
        }
        return carries;
    }

    /**
     * Scans one line, appending its spans to {@code out} when that is given — {@link #carries} passes null and so
     * allocates nothing for a file it only needs the states of. Returns the state the next line begins in.
     */
    private static int scan(String line, int carry, Syntax syntax, List<Token> out) {
        int n = line.length();
        int i = 0;
        if (carry > 0) {
            Syntax.Fence fence = syntax.blockComments().get(carry - 1);
            int end = closeOf(line, 0, fence.close(), Syntax.NO_ESCAPE);
            if (end < 0) {
                add(out, 0, n, Syntax.Kind.COMMENT);
                return carry;
            }
            add(out, 0, end, Syntax.Kind.COMMENT);
            i = end;
        } else if (carry < 0) {
            Syntax.Fence fence = syntax.multilineStrings().get(-carry - 1);
            int end = closeOf(line, 0, fence.close(), syntax.escape());
            if (end < 0) {
                add(out, 0, n, Syntax.Kind.STRING);
                return carry;
            }
            add(out, 0, end, Syntax.Kind.STRING);
            i = end;
        }
        while (i < n) {
            // A comment first: inside one, nothing else in the line means anything.
            int block = fenceAt(line, i, syntax.blockComments());
            if (block >= 0) {
                Syntax.Fence fence = syntax.blockComments().get(block);
                int end = closeOf(line, i + fence.open().length(), fence.close(), Syntax.NO_ESCAPE);
                if (end < 0) {
                    add(out, i, n, Syntax.Kind.COMMENT);
                    return block + 1;
                }
                add(out, i, end, Syntax.Kind.COMMENT);
                i = end;
                continue;
            }
            if (startsWithAny(line, i, syntax.lineComments())) {
                add(out, i, n, Syntax.Kind.COMMENT);
                return 0;
            }
            int multiline = fenceAt(line, i, syntax.multilineStrings());
            if (multiline >= 0) {
                Syntax.Fence fence = syntax.multilineStrings().get(multiline);
                int end = closeOf(line, i + fence.open().length(), fence.close(), syntax.escape());
                if (end < 0) {
                    add(out, i, n, Syntax.Kind.STRING);
                    return -(multiline + 1);
                }
                add(out, i, end, Syntax.Kind.STRING);
                i = end;
                continue;
            }
            char c = line.charAt(i);
            if (syntax.stringQuotes().contains(c) || syntax.charQuotes().contains(c)) {
                // An unterminated quote ends with the line: a single-line string cannot run past it, and
                // guessing otherwise would colour the rest of the file from one stray apostrophe.
                int end = closeOf(line, i + 1, String.valueOf(c), syntax.escape());
                add(out, i, end < 0 ? n : end, Syntax.Kind.STRING);
                i = end < 0 ? n : end;
                continue;
            }
            int annotation = startsWithIndex(line, i, syntax.annotations());
            if (annotation >= 0) {
                int end = i + syntax.annotations().get(annotation).length();
                while (end < n && isWordPart(line.charAt(end))) end++;
                add(out, i, end, Syntax.Kind.ANNOTATION);
                i = end;
                continue;
            }
            if (isDigit(c) || (c == '.' && i + 1 < n && isDigit(line.charAt(i + 1)))) {
                int end = numberEnd(line, i);
                add(out, i, end, Syntax.Kind.NUMBER);
                i = end;
                continue;
            }
            if (isWordStart(c)) {
                int end = i + 1;
                while (end < n && isWordPart(line.charAt(end))) end++;
                Syntax.Kind kind = syntax.kindOf(line.substring(i, end));
                if (kind == Syntax.Kind.TEXT && syntax.functionCalls() && callAt(line, end)) kind = Syntax.Kind.FUNCTION;
                add(out, i, end, kind);
                i = end;
                continue;
            }
            add(out, i, i + 1, isPunctuation(c) ? Syntax.Kind.PUNCT : Syntax.Kind.TEXT);
            i++;
        }
        return 0;
    }

    /**
     * Appends a span, joined to the one before it when they are of the same kind — a line of plain words and
     * spaces becomes one span rather than twenty, which is what the view pays for in nodes.
     */
    private static void add(List<Token> out, int from, int to, Syntax.Kind kind) {
        if (out == null || to <= from) return;
        if (!out.isEmpty()) {
            Token last = out.getLast();
            if (last.kind() == kind && last.to() == from) {
                out.set(out.size() - 1, new Token(last.from(), to, kind));
                return;
            }
        }
        out.add(new Token(from, to, kind));
    }

    /** Where the closing marker ends, searching from {@code at}, or −1 when the line does not hold it. */
    private static int closeOf(String line, int at, String close, char escape) {
        for (int i = at; i <= line.length() - close.length(); i++) {
            if (escape != Syntax.NO_ESCAPE && line.charAt(i) == escape) {
                i++;  // the escaped character cannot close anything, whatever it is
                continue;
            }
            if (line.startsWith(close, i)) return i + close.length();
        }
        return -1;
    }

    /** The index of the fence that opens here, or −1. */
    private static int fenceAt(String line, int at, List<Syntax.Fence> fences) {
        for (int i = 0; i < fences.size(); i++) {
            if (line.startsWith(fences.get(i).open(), at)) return i;
        }
        return -1;
    }

    private static boolean startsWithAny(String line, int at, List<String> prefixes) {
        return startsWithIndex(line, at, prefixes) >= 0;
    }

    private static int startsWithIndex(String line, int at, List<String> prefixes) {
        for (int i = 0; i < prefixes.size(); i++) {
            if (line.startsWith(prefixes.get(i), at)) return i;
        }
        return -1;
    }

    /**
     * The end of a number. Wide on purpose: {@code 0xFF_00}, {@code 1_000}, {@code 1.5e-9}, {@code 42L} and
     * {@code 3u8} are all one number, and a scanner that split them would colour half a literal.
     */
    private static int numberEnd(String line, int at) {
        int n = line.length();
        int i = at;
        while (i < n) {
            char c = line.charAt(i);
            if (isDigit(c) || isLetter(c) || c == '_') {
                i++;
            } else if (c == '.' && i + 1 < n && isDigit(line.charAt(i + 1))) {
                i++;
            } else if ((c == '+' || c == '-') && i > at && (line.charAt(i - 1) == 'e' || line.charAt(i - 1) == 'E')) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /** True when the word that ends here is being called: the next thing, spaces aside, is an opening bracket. */
    private static boolean callAt(String line, int end) {
        int i = end;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i < line.length() && line.charAt(i) == '(';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isLetter(char c) {
        return Character.isLetter(c);
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isWordPart(char c) {
        return isWordStart(c) || isDigit(c);
    }

    private static boolean isPunctuation(char c) {
        return !Character.isWhitespace(c) && !isWordPart(c);
    }
}
