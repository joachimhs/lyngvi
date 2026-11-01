package no.syntakserror.lyngvi.gui.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The colouring rules and the scanner over them: no toolkit, so every rule a {@code .syntax} file can express is
 * checked here. The last test reads every language Lyngvi ships, which is the only place a typo in one of those
 * files would otherwise be found — by the user, as a language that colours nothing.
 */
class SyntaxTest {

    private static final String JAVA = """
            name          = Java
            extensions    = java
            line.comment  = //
            block.comment = /* */
            string.quote  = "
            char.quote    = '
            escape        = \\\\
            annotation    = @
            keywords      = class return void
            types         = int String
            constants     = true null
            """;

    /** The spans of a line as «text:KIND», which is short enough to assert on directly. */
    private static List<String> spans(String line, Syntax syntax) {
        return spans(line, 0, syntax);
    }

    private static List<String> spans(String line, int carry, Syntax syntax) {
        List<String> out = new ArrayList<>();
        for (SyntaxScanner.Token t : SyntaxScanner.tokens(line, carry, syntax)) {
            out.add(line.substring(t.from(), t.to()) + ":" + t.kind());
        }
        return out;
    }

    @Test
    void aLanguageIsReadFromItsFileAndClaimsTheFilesItNames() {
        Syntax java = Syntax.parse("java", JAVA);

        assertThat(java.name()).isEqualTo("Java");
        assertThat(java.extensions()).containsExactly("java");
        assertThat(java.escape()).isEqualTo('\\');
        assertThat(java.blockComments()).containsExactly(new Syntax.Fence("/*", "*/"));
        assertThat(java.covers("repo/src/App.java")).isTrue();
        assertThat(java.covers("repo/src/App.JAVA")).isTrue();  // the name is matched without case
        assertThat(java.covers("repo/src/App.javascript")).isFalse();
        assertThat(java.covers("repo/java")).isFalse();         // an extension is not a whole file name
        assertThat(java.kindOf("class")).isEqualTo(Syntax.Kind.KEYWORD);
        assertThat(java.kindOf("String")).isEqualTo(Syntax.Kind.TYPE);
        assertThat(java.kindOf("null")).isEqualTo(Syntax.Kind.CONSTANT);
        assertThat(java.kindOf("customerName")).isEqualTo(Syntax.Kind.TEXT);
    }

    @Test
    void aFileNameCanBeNamedInFullAndTheCaseIsIgnoredWhereTheLanguageSaysSo() {
        Syntax docker = Syntax.parse("dockerfile", """
                extensions  = dockerfile
                filenames   = Dockerfile Containerfile
                ignore.case = true
                keywords    = FROM RUN
                """);

        assertThat(docker.covers("repo/Dockerfile")).isTrue();
        assertThat(docker.covers("repo/image/containerfile")).isTrue();
        assertThat(docker.covers("repo/base.dockerfile")).isTrue();
        assertThat(docker.covers("repo/Makefile")).isFalse();
        assertThat(docker.kindOf("from")).isEqualTo(Syntax.Kind.KEYWORD);
        assertThat(docker.kindOf("From")).isEqualTo(Syntax.Kind.KEYWORD);
        // the display name falls back to the file's own name, so a language is never listed nameless
        assertThat(docker.name()).isEqualTo("dockerfile");
    }

    @Test
    void whatAFileCannotSayIsReportedRatherThanIgnored() {
        assertThatThrownBy(() -> Syntax.parse("x", "extensions = x\nkeyword = class\n"))
                .isInstanceOf(Syntax.Problem.class).hasMessageContaining("unknown key: keyword");
        assertThatThrownBy(() -> Syntax.parse("x", "extensions = x\nblock.comment = /*\n"))
                .isInstanceOf(Syntax.Problem.class).hasMessageContaining("opening and a closing");
        assertThatThrownBy(() -> Syntax.parse("x", "extensions = x\nstring.quote = ''\n"))
                .isInstanceOf(Syntax.Problem.class).hasMessageContaining("single characters");
        assertThatThrownBy(() -> Syntax.parse("x", "extensions = x\nignore.case = sometimes\n"))
                .isInstanceOf(Syntax.Problem.class).hasMessageContaining("true or false");
        // a language that claims no file at all would be dead weight in the list, and says nothing about why
        assertThatThrownBy(() -> Syntax.parse("x", "name = X\nkeywords = a b\n"))
                .isInstanceOf(Syntax.Problem.class).hasMessageContaining("nothing would ever be coloured");
    }

    @Test
    void everyCharacterOfALineEndsUpInExactlyOneSpan() {
        Syntax java = Syntax.parse("java", JAVA);
        String line = "    return new int[] { 1, 0x2A, 3.5e-9 };  // note";

        int at = 0;
        for (SyntaxScanner.Token t : SyntaxScanner.tokens(line, 0, java)) {
            assertThat(t.from()).as("spans are contiguous").isEqualTo(at);
            at = t.to();
        }
        assertThat(at).as("the spans cover the whole line").isEqualTo(line.length());
    }

    @Test
    void wordsNumbersStringsAndCommentsAreToldApart() {
        Syntax java = Syntax.parse("java", JAVA);

        // the plain words and the spaces between them are one span, not five: a line of prose is one node
        assertThat(spans("class App {", java)).containsExactly("class:KEYWORD", " App :TEXT", "{:PUNCT");
        assertThat(spans("int x = 42;", java))
                .containsExactly("int:TYPE", " x :TEXT", "=:PUNCT", " :TEXT", "42:NUMBER", ";:PUNCT");
        assertThat(spans("return \"a, b\";", java))
                .containsExactly("return:KEYWORD", " :TEXT", "\"a, b\":STRING", ";:PUNCT");
        // an escaped quote does not end the string, and a comment marker inside one is just text
        assertThat(spans("x = \"a\\\" // b\";", java))
                .containsExactly("x :TEXT", "=:PUNCT", " :TEXT", "\"a\\\" // b\":STRING", ";:PUNCT");
        assertThat(spans("x = 1; // why", java))
                .containsExactly("x :TEXT", "=:PUNCT", " :TEXT", "1:NUMBER", ";:PUNCT", " :TEXT", "// why:COMMENT");
        assertThat(spans("@Override", java)).containsExactly("@Override:ANNOTATION");
        // a word that is called is a call, a word that is not is a word
        assertThat(spans("send(x)", java)).containsExactly("send:FUNCTION", "(:PUNCT", "x:TEXT", "):PUNCT");
        assertThat(spans("sent + x", java)).containsExactly("sent :TEXT", "+:PUNCT", " x:TEXT");
        // an apostrophe with no partner cannot colour the rest of the file: it ends with its line
        assertThat(spans("it's here", java)).containsExactly("it:TEXT", "'s here:STRING");
    }

    @Test
    void numbersAreTakenWholeRatherThanInPieces() {
        Syntax java = Syntax.parse("java", JAVA);

        assertThat(spans("0xFF_00", java)).containsExactly("0xFF_00:NUMBER");
        assertThat(spans("1_000L", java)).containsExactly("1_000L:NUMBER");
        assertThat(spans("1.5e-9", java)).containsExactly("1.5e-9:NUMBER");
        assertThat(spans(".5", java)).containsExactly(".5:NUMBER");
        // a dot that is not part of a number is punctuation, and the word after it is a word
        assertThat(spans("x.y", java)).containsExactly("x:TEXT", ".:PUNCT", "y:TEXT");
    }

    @Test
    void aBlockCommentRunsOnUntilItClosesAndTheLinesBetweenKnowThatTheyAreInsideIt() {
        Syntax java = Syntax.parse("java", JAVA);
        List<String> lines = List.of("/** A thing.", " * class", " */", "class App {}");

        int[] carries = SyntaxScanner.carries(lines, java);

        assertThat(carries).containsExactly(0, 1, 1, 0);
        // the middle line is a comment even though nothing on it says so
        assertThat(spans(lines.get(1), carries[1], java)).containsExactly(" * class:COMMENT");
        assertThat(spans(lines.get(2), carries[2], java)).containsExactly(" */:COMMENT");
        assertThat(spans(lines.get(3), carries[3], java)).containsExactly("class:KEYWORD", " App :TEXT", "{}:PUNCT");
        // and a comment that never closes colours the rest of the file rather than half a line
        assertThat(SyntaxScanner.carries(List.of("/* open", "still"), java)).containsExactly(0, 1);
    }

    @Test
    void aTripleQuotedStringCarriesOverLinesTheSameWay() {
        Syntax python = Syntax.parse("python", """
                extensions       = py
                line.comment     = #
                multiline.string = ""\" ""\" ''' '''
                string.quote     = " '
                escape           = \\\\
                keywords         = def return
                """);
        List<String> lines = List.of("def f():", "    \"\"\"Doc.", "    # not a comment", "    \"\"\"", "    return 1");

        int[] carries = SyntaxScanner.carries(lines, python);

        assertThat(carries).containsExactly(0, 0, -1, -1, 0);
        assertThat(spans(lines.get(2), carries[2], python)).containsExactly("    # not a comment:STRING");
        assertThat(spans(lines.get(4), carries[4], python)).containsExactly("    :TEXT", "return:KEYWORD", " :TEXT", "1:NUMBER");
    }

    @Test
    void aSampleIsBuiltFromTheLanguagesOwnWordsWhenTheFileGivesNone() {
        Syntax java = Syntax.parse("java", JAVA);
        assertThat(java.preview()).contains("//").contains("class").contains("\"text\"");

        Syntax given = Syntax.parse("x", "extensions = x\nsample = one\\ntwo\n");
        assertThat(given.preview()).isEqualTo("one\ntwo");
    }

    @Test
    void theShippedLanguagesAreAllReadableAndClaimDifferentFiles(@TempDir Path home) {
        Languages languages = Languages.inHome(home);

        List<Languages.Entry> all = languages.list();
        assertThat(all).isNotEmpty();
        assertThat(all).allSatisfy(e -> assertThat(e.problem()).as(e.id() + " parses").isNull());
        assertThat(all).allMatch(e -> e.source() == Languages.Source.BUILT_IN);
        assertThat(all).extracting(Languages.Entry::id).containsAll(List.of("java", "python", "json"));
        // the files a reviewer opens land on the language they are written in
        assertThat(languages.forPath("repo/src/App.java")).get().extracting(Syntax::id).isEqualTo("java");
        assertThat(languages.forPath("repo/build.gradle.kts")).get().extracting(Syntax::id).isEqualTo("kotlin");
        assertThat(languages.forPath("repo/web/app.tsx")).get().extracting(Syntax::id).isEqualTo("typescript");
        assertThat(languages.forPath("repo/Dockerfile")).get().extracting(Syntax::id).isEqualTo("dockerfile");
        assertThat(languages.forPath("repo/compose.yaml")).get().extracting(Syntax::id).isEqualTo("yaml");
        // and nothing claims a file no language is for
        assertThat(languages.forPath("repo/notes.txt")).isEmpty();
        assertThat(languages.forPath("repo/logo.png")).isEmpty();
    }

    @Test
    void aUserFileAddsALanguageAndCanReplaceAShippedOne(@TempDir Path home) throws Exception {
        Languages languages = Languages.inHome(home);
        assertThat(languages.forPath("main.zig")).isEmpty();

        languages.save("zig", "name = Zig\nextensions = zig\nline.comment = //\nkeywords = fn const\n");

        assertThat(languages.forPath("main.zig")).get().extracting(Syntax::name).isEqualTo("Zig");
        assertThat(languages.list()).filteredOn(e -> e.id().equals("zig"))
                .singleElement().satisfies(e -> {
                    assertThat(e.source()).isEqualTo(Languages.Source.USER);
                    assertThat(e.overrides()).isFalse();
                    assertThat(e.extensions()).isEqualTo("zig");
                });

        // a file named after a shipped language replaces it whole, so the shipped rules can be corrected
        languages.save("java", "name = Java (mine)\nextensions = java\nkeywords = class\n");
        assertThat(languages.forPath("App.java")).get().extracting(Syntax::name).isEqualTo("Java (mine)");
        assertThat(languages.list()).filteredOn(e -> e.id().equals("java"))
                .singleElement().satisfies(e -> {
                    assertThat(e.source()).isEqualTo(Languages.Source.USER);
                    assertThat(e.overrides()).isTrue();
                });

        // and removing the user's file brings the shipped one back rather than leaving a hole
        languages.remove("java");
        assertThat(languages.forPath("App.java")).get().extracting(Syntax::name).isEqualTo("Java");
        assertThat(languages.list()).filteredOn(e -> e.id().equals("java"))
                .singleElement().extracting(Languages.Entry::source).isEqualTo(Languages.Source.BUILT_IN);
        languages.remove("zig");
        assertThat(languages.forPath("main.zig")).isEmpty();
    }

    @Test
    void aBrokenFileKeepsItsPlaceInTheListWithTheReasonAndColoursNothing(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(Languages.USER_DIR));
        Files.writeString(home.resolve(Languages.USER_DIR).resolve("zig.syntax"),
                "extensions = zig\nkeyword = fn\n", StandardCharsets.UTF_8);

        Languages languages = Languages.inHome(home);

        assertThat(languages.list()).filteredOn(e -> e.id().equals("zig"))
                .singleElement().satisfies(e -> assertThat(e.problem()).contains("unknown key: keyword"));
        assertThat(languages.forPath("main.zig")).isEmpty();
        // the editor still offers the text, since the only way out is for the user to fix it
        assertThat(languages.text("zig")).contains("keyword = fn");
    }

    @Test
    void aNewLanguageStartsFromATemplateThatParses(@TempDir Path home) throws Exception {
        Languages languages = Languages.inHome(home);

        languages.create("my-lang");

        assertThat(languages.fileOf("my-lang")).isRegularFile();
        assertThat(languages.isUser("my-lang")).isTrue();
        // it has no extensions yet, so it is listed with the reason rather than silently doing nothing
        assertThat(languages.list()).filteredOn(e -> e.id().equals("my-lang"))
                .singleElement().satisfies(e -> assertThat(e.problem()).contains("nothing would ever be coloured"));
        assertThat(languages.text("my-lang")).contains("name          = my-lang");
        assertThatThrownBy(() -> languages.create("my-lang")).hasMessageContaining("already a language");
        assertThatThrownBy(() -> languages.create("Not A Name")).hasMessageContaining("lower-case words");
        assertThat(Languages.isValidId("rust")).isTrue();
        assertThat(Languages.isValidId("objective-c")).isTrue();
        assertThat(Languages.isValidId("../escape")).isFalse();
    }
}
