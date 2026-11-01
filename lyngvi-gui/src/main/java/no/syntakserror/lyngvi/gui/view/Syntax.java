package no.syntakserror.lyngvi.gui.view;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * One language's colouring rules, read from a {@code .syntax} file. A language is data, never code: it names the
 * comment markers, the string delimiters and the words that are keywords, and the scanner ({@link SyntaxScanner})
 * turns that into token kinds. The colours belong to the theme — a {@link Kind} has a style class, not a colour —
 * so a language added by a user takes both the light and the dark palette without saying anything about either.
 *
 * <p>The format is a properties file, the same as the GUI's own settings, so adding Rust is a file of ten lines
 * and no build:
 *
 * <pre>
 * name          = Rust
 * extensions    = rs
 * line.comment  = //
 * block.comment = /* *&#47;
 * string.quote  = "
 * escape        = \\
 * keywords      = fn let mut pub struct enum impl trait use mod match if else for while loop return
 * types         = i32 i64 u32 u64 usize f64 bool str String Vec Option Result
 * annotation    = #[
 * </pre>
 *
 * <p>An unknown key is a problem rather than something ignored: a typo in a hand-written file should be told to
 * the user who wrote it, and the settings page shows what {@link #parse} says.
 *
 * <p>What the format deliberately cannot express: anything that needs a parser rather than a scanner — JSX, a
 * heredoc, code inside an interpolated string, a regular expression told apart from a division, and tag-based
 * languages such as HTML and XML. This is for reading a working copy, and that is where it stops.
 */
public record Syntax(
        String id,
        String name,
        List<String> extensions,
        List<String> filenames,
        List<String> lineComments,
        List<Fence> blockComments,
        List<Fence> multilineStrings,
        List<Character> stringQuotes,
        List<Character> charQuotes,
        char escape,
        Set<String> keywords,
        Set<String> types,
        Set<String> constants,
        List<String> annotations,
        boolean functionCalls,
        boolean ignoreCase,
        String sample) {

    /** What a token is coloured as. The style class is the only thing the view knows; the theme holds the colour. */
    public enum Kind {
        TEXT, KEYWORD, TYPE, CONSTANT, STRING, NUMBER, COMMENT, ANNOTATION, FUNCTION, PUNCT;

        /** The CSS class for this kind: {@code code-keyword}, {@code code-string}, … */
        public String styleClass() {
            return "code-" + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Something that opens and closes again, possibly several lines later: a block comment, a triple-quoted string. */
    public record Fence(String open, String close) {}

    /** What a {@code .syntax} file may say. Anything else is a mistake worth reporting. */
    private static final Set<String> KEYS = Set.of("name", "extensions", "filenames", "line.comment", "block.comment",
            "multiline.string", "string.quote", "char.quote", "escape", "keywords", "types", "constants", "annotation",
            "function.calls", "ignore.case", "sample");

    /** No character is the escape: the language has none, and a backslash in a string is just a backslash. */
    static final char NO_ESCAPE = '\0';

    /** The file a new language starts from, so «New language…» opens something that already works. */
    public static final String TEMPLATE = """
            # A language for Lyngvi's file pane. The colours come from the theme; this file only says
            # which words and which markers are what. Save, and the files it covers are coloured at once.
            name          = %s
            extensions    =\s
            line.comment  = //
            block.comment = /* */
            string.quote  = "
            escape        = \\\\
            keywords      =\s
            types         =\s
            """;

    /** A {@code .syntax} file that cannot be used as it stands. The message is shown to whoever wrote the file. */
    public static final class Problem extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        Problem(String message) {
            super(message);
        }
    }

    /**
     * Reads a language definition. {@code id} is the file's name without its suffix, and the key a user file
     * overrides a built-in one by.
     *
     * @throws Problem when the file says something the format does not have, or nothing it could be used by
     */
    public static Syntax parse(String id, String text) {
        Properties p = new Properties();
        try (Reader r = new StringReader(text == null ? "" : text)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            throw new Problem("cannot be read: " + e.getMessage());
        }
        List<String> unknown = p.stringPropertyNames().stream().filter(k -> !KEYS.contains(k)).sorted().toList();
        if (!unknown.isEmpty()) throw new Problem("unknown " + (unknown.size() == 1 ? "key" : "keys") + ": " + String.join(", ", unknown));
        List<String> extensions = words(p, "extensions").stream().map(e -> e.startsWith(".") ? e.substring(1) : e)
                .map(e -> e.toLowerCase(Locale.ROOT)).toList();
        List<String> filenames = words(p, "filenames").stream().map(f -> f.toLowerCase(Locale.ROOT)).toList();
        if (extensions.isEmpty() && filenames.isEmpty()) {
            throw new Problem("no extensions and no filenames: nothing would ever be coloured by it");
        }
        boolean ignoreCase = bool(p, "ignore.case", false);
        return new Syntax(id,
                p.getProperty("name", "").isBlank() ? id : p.getProperty("name").strip(),
                extensions,
                filenames,
                words(p, "line.comment"),
                fences(p, "block.comment"),
                fences(p, "multiline.string"),
                chars(p, "string.quote"),
                chars(p, "char.quote"),
                escape(p),
                wordSet(p, "keywords", ignoreCase),
                wordSet(p, "types", ignoreCase),
                wordSet(p, "constants", ignoreCase),
                words(p, "annotation"),
                bool(p, "function.calls", true),
                ignoreCase,
                p.getProperty("sample", "").strip());
    }

    /** True when this language is the one for a file, by its extension or by its whole name. */
    public boolean covers(String path) {
        String name = FilesController.name(path == null ? "" : path).toLowerCase(Locale.ROOT);
        if (filenames.contains(name)) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 && extensions.contains(name.substring(dot + 1));
    }

    /** A keyword, a type name, a constant or an ordinary word — the kind an identifier is coloured as. */
    public Kind kindOf(String word) {
        String w = ignoreCase ? word.toLowerCase(Locale.ROOT) : word;
        if (keywords.contains(w)) return Kind.KEYWORD;
        if (types.contains(w)) return Kind.TYPE;
        if (constants.contains(w)) return Kind.CONSTANT;
        return Kind.TEXT;
    }

    /**
     * The few lines the settings page colours while the rules are being written. The file may give its own
     * {@code sample}; where it does not, one is built from the language's own words, so every colour in the
     * palette shows up even for a language nobody has written a sample for.
     */
    public String preview() {
        if (!sample.isBlank()) return sample.replace("\\n", "\n");
        String comment = lineComments.isEmpty() ? "" : lineComments.getFirst() + " a comment";
        char quote = stringQuotes.isEmpty() ? '"' : stringQuotes.getFirst();
        String keyword = keywords.stream().findFirst().orElse("name");
        String type = types.stream().findFirst().orElse("Thing");
        String annotation = annotations.isEmpty() ? "" : annotations.getFirst() + "Marked\n";
        return annotation + comment + "\n" + keyword + " " + type + " value = " + quote + "text" + quote + " 42";
    }

    private static List<String> words(Properties p, String key) {
        String v = p.getProperty(key, "");
        if (v.isBlank()) return List.of();
        return List.of(v.strip().split("\\s+"));
    }

    /** The words of a list, in the order the file gives them: the first one is what a generated sample uses. */
    private static Set<String> wordSet(Properties p, String key, boolean ignoreCase) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : words(p, key)) out.add(ignoreCase ? w.toLowerCase(Locale.ROOT) : w);
        return Collections.unmodifiableSet(out);
    }

    /** {@code block.comment = /* *&#47; <!-- -->}: the words two by two, opener then closer. */
    private static List<Fence> fences(Properties p, String key) {
        List<String> words = words(p, key);
        if (words.size() % 2 != 0) throw new Problem(key + " needs an opening and a closing marker for each pair");
        List<Fence> out = new ArrayList<>();
        for (int i = 0; i < words.size(); i += 2) out.add(new Fence(words.get(i), words.get(i + 1)));
        return List.copyOf(out);
    }

    private static List<Character> chars(Properties p, String key) {
        List<Character> out = new ArrayList<>();
        for (String w : words(p, key)) {
            if (w.length() != 1) throw new Problem(key + " takes single characters, not " + w);
            out.add(w.charAt(0));
        }
        return List.copyOf(out);
    }

    private static char escape(Properties p) {
        String v = p.getProperty("escape", "").strip();
        if (v.isEmpty()) return NO_ESCAPE;
        if (v.length() != 1) throw new Problem("escape takes one character, not " + v + " (write a backslash as \\\\)");
        return v.charAt(0);
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key, "").strip();
        if (v.isEmpty()) return fallback;
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes")) return true;
        if (v.equalsIgnoreCase("false") || v.equalsIgnoreCase("no")) return false;
        throw new Problem(key + " is true or false, not " + v);
    }
}
