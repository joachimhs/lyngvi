package no.syntakserror.lyngvi.gui.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import no.syntakserror.lyngvi.core.agents.LocalModelConfig;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.ask.UsageLimit;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.config.ResourcePreset;
import no.syntakserror.lyngvi.core.contract.AgentInfo;
import no.syntakserror.lyngvi.core.contract.AgentUsageReport;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.session.Conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The view helpers that do not need a toolkit: markdown, diff, work log, usage formatting. */
class PureViewHelpersTest {
    @Test
    void relativeTimeRoundsDownToOneUnitAndCallsTheLastMinuteNow() {
        assertThat(RelativeTime.of(0)).isEqualTo(new RelativeTime.Age("gui.chat.age.now", 0));
        assertThat(RelativeTime.of(-5000)).isEqualTo(new RelativeTime.Age("gui.chat.age.now", 0));
        assertThat(RelativeTime.of(RelativeTime.NOW_MILLIS - 1)).isEqualTo(new RelativeTime.Age("gui.chat.age.now", 0));
        assertThat(RelativeTime.of(Duration.ofMinutes(59).toMillis())).isEqualTo(new RelativeTime.Age("gui.chat.age.minutes", 59));
        assertThat(RelativeTime.of(Duration.ofMinutes(90).toMillis())).isEqualTo(new RelativeTime.Age("gui.chat.age.hours", 1));
        assertThat(RelativeTime.of(Duration.ofHours(30).toMillis())).isEqualTo(new RelativeTime.Age("gui.chat.age.days", 1));
        assertThat(RelativeTime.of(Duration.ofDays(6).toMillis())).isEqualTo(new RelativeTime.Age("gui.chat.age.days", 6));
        assertThat(RelativeTime.of(Duration.ofDays(15).toMillis())).isEqualTo(new RelativeTime.Age("gui.chat.age.weeks", 2));
    }


    @Test
    void markdownParserProducesBlocksAndInlineRuns() {
        List<MarkdownParser.Block> blocks = MarkdownParser.parse("""
                # Title
                Some **bold** and `code` and *it*.

                - one
                - two

                ```java
                int x = 1;
                ```
                > quoted
                ---
                1. first
                """);
        assertThat(blocks).extracting(MarkdownParser.Block::kind).containsExactly(
                MarkdownParser.Kind.HEADING, MarkdownParser.Kind.PARAGRAPH, MarkdownParser.Kind.LIST, MarkdownParser.Kind.CODE,
                MarkdownParser.Kind.QUOTE, MarkdownParser.Kind.RULE, MarkdownParser.Kind.LIST);
        assertThat(blocks.get(0).level()).isEqualTo(1);
        assertThat(blocks.get(0).text()).isEqualTo("Title");
        List<MarkdownParser.Run> runs = MarkdownParser.inline("Some **bold** and `code` and *it*.");
        assertThat(runs).extracting(MarkdownParser.Run::text).containsExactly("Some ", "bold", " and ", "code", " and ", "it", ".");
        assertThat(runs.get(1).bold()).isTrue();
        assertThat(runs.get(3).code()).isTrue();
        assertThat(runs.get(5).italic()).isTrue();
        assertThat(blocks.get(2).items()).containsExactly("one", "two");
        assertThat(blocks.get(3).text()).isEqualTo("int x = 1;");
        assertThat(blocks.get(3).language()).isEqualTo("java");
        assertThat(blocks.get(6).ordered()).isTrue();
        assertThat(MarkdownParser.parse("")).isEmpty();
        assertThat(MarkdownParser.plainText("**a** `b`")).isEqualTo("a b");
    }

    @Test
    void markdownParserReadsPipeTables() {
        List<MarkdownParser.Block> blocks = MarkdownParser.parse("""
                Sizes:
                | Name | Size | Note |
                |:-----|-----:|:----:|
                | a.txt | 12 | ok |
                | b\\|c.txt | 3 |
                | d | 4 | too | many |

                after
                """);
        assertThat(blocks).extracting(MarkdownParser.Block::kind).containsExactly(
                MarkdownParser.Kind.PARAGRAPH, MarkdownParser.Kind.TABLE, MarkdownParser.Kind.PARAGRAPH);
        MarkdownParser.Table table = blocks.get(1).table();
        assertThat(table.header()).containsExactly("Name", "Size", "Note");
        assertThat(table.aligns()).containsExactly(
                MarkdownParser.Align.LEFT, MarkdownParser.Align.RIGHT, MarkdownParser.Align.CENTER);
        // every row is as wide as the header: a short one is filled out, a long one cut
        assertThat(table.rows()).containsExactly(
                List.of("a.txt", "12", "ok"), List.of("b|c.txt", "3", ""), List.of("d", "4", "too"));
        assertThat(blocks.get(2).text()).isEqualTo("after");
    }

    @Test
    void markdownParserNeedsADashedRowToSeeATable() {
        assertThat(MarkdownParser.parse("| a | b |\n| c | d |"))
                .extracting(MarkdownParser.Block::kind).containsExactly(MarkdownParser.Kind.PARAGRAPH);
        // the dashed row states the column count, and must agree with the header
        assertThat(MarkdownParser.parse("| a | b |\n| --- |\n| c | d |"))
                .extracting(MarkdownParser.Block::kind).containsExactly(MarkdownParser.Kind.PARAGRAPH);
        assertThat(MarkdownParser.parse("---")).extracting(MarkdownParser.Block::kind)
                .containsExactly(MarkdownParser.Kind.RULE);
        // a table with no rows of its own is still a table
        assertThat(MarkdownParser.parse("| a |\n| - |").get(0).table().rows()).isEmpty();
    }

    @Test
    void filesPaneRendersMarkdownAndCutsAVeryLongFileAtALineBreak() {
        assertThat(FilesController.isMarkdown("repo/README.md")).isTrue();
        assertThat(FilesController.isMarkdown("repo/NOTES.Markdown")).isTrue();
        assertThat(FilesController.isMarkdown("repo/app.css")).isFalse();
        FilesController.Preview whole = FilesController.previewOf("# Title\n\ntext\n");
        assertThat(whole.truncated()).isFalse();
        assertThat(whole.markdown()).isEqualTo("# Title\n\ntext\n");
        // a specification of a few hundred kilobytes is laid out as far as the limit, and no line is shown half
        FilesController.Preview cut = FilesController.previewOf(("x".repeat(99) + "\n").repeat(2000));
        assertThat(cut.truncated()).isTrue();
        assertThat(cut.markdown().length()).isLessThanOrEqualTo(FilesController.MAX_PREVIEW_CHARS);
        assertThat(cut.markdown().lines().map(String::length).distinct()).containsExactly(99);
        assertThat(FilesController.previewOf(null).markdown()).isEmpty();
    }

    @Test
    void anAttachmentIsNamedByItsLastSegmentAndShownAsAPictureWhenItIsOne() {
        // the conversation records the path the agent reads, a turn still running the bare name
        assertThat(MessageAttachments.name("/work/_attachments/pasted-20261015-094246.png")).isEqualTo("pasted-20261015-094246.png");
        assertThat(MessageAttachments.name("pasted.png")).isEqualTo("pasted.png");
        assertThat(MessageAttachments.name("C:\\Users\\me\\shot.PNG")).isEqualTo("shot.PNG");
        assertThat(MessageAttachments.isImage("/work/_attachments/shot.PNG")).isTrue();
        assertThat(MessageAttachments.isImage("photo.jpeg")).isTrue();
        assertThat(MessageAttachments.isImage("notes.md")).isFalse();
        assertThat(MessageAttachments.isImage("png")).isFalse();
        assertThat(MessageAttachments.isImage("design.pdf")).isFalse();
    }

    @Test
    void diffPaneBuilderParsesUnifiedHunks() {
        String diff = """
                diff --git a/src/A.java b/src/A.java
                index 1..2 100644
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1,3 +1,4 @@
                 keep
                -old
                +new
                +added
                 tail
                """;
        List<DiffPaneBuilder.FileDiff> files = DiffPaneBuilder.parse(diff);
        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("src/A.java");
        List<DiffPaneBuilder.Line> lines = files.get(0).lines();
        assertThat(lines).extracting(DiffPaneBuilder.Line::kind).containsExactly(
                DiffPaneBuilder.Kind.HUNK, DiffPaneBuilder.Kind.CONTEXT, DiffPaneBuilder.Kind.REMOVED, DiffPaneBuilder.Kind.ADDED,
                DiffPaneBuilder.Kind.ADDED, DiffPaneBuilder.Kind.CONTEXT);
        assertThat(lines.get(1).oldNumber()).isEqualTo(1);
        assertThat(lines.get(1).newNumber()).isEqualTo(1);
        assertThat(lines.get(2).oldNumber()).isEqualTo(2);
        assertThat(lines.get(2).newNumber()).isNull();
        assertThat(lines.get(3).newNumber()).isEqualTo(2);
        assertThat(lines.get(5).oldNumber()).isEqualTo(3);
        assertThat(lines.get(5).newNumber()).isEqualTo(4);
        assertThat(lines.get(2).code()).isEqualTo("old");
        assertThat(files.get(0).additions()).isEqualTo(2);
        assertThat(files.get(0).deletions()).isEqualTo(1);
        assertThat(files.get(0).change()).isEqualTo(DiffPaneBuilder.Change.MODIFIED);
        assertThat(files.get(0).name()).isEqualTo("A.java");
        assertThat(files.get(0).directory()).isEqualTo("src");
        assertThat(DiffPaneBuilder.parse("")).isEmpty();
        assertThat(DiffPaneBuilder.parse(null)).isEmpty();
        assertThat(DiffPaneBuilder.copyText(lines)).isEqualTo("keep\nold\nnew\nadded\ntail");
    }

    @Test
    void selectingTextInADiffCopiesTheCodeItCoversAndNothingElse() {
        List<DiffPaneBuilder.Line> lines = List.of(
                new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.HUNK, null, null, "@@ -1,3 +1,4 @@"),
                new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.CONTEXT, 1, 1, "public void run() {"),
                new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.CONTEXT, 2, 2, ""),
                new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.ADDED, null, 3, "    int x = 1;"),
                new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.REMOVED, 3, null, "}"));

        // part of one line, in either direction
        assertThat(DiffPaneBuilder.selectedText(lines, TranscriptCopy.Range.of(1, 7, 1, 11))).isEqualTo("void");
        assertThat(DiffPaneBuilder.selectedText(lines, TranscriptCopy.Range.of(1, 11, 1, 7))).isEqualTo("void");
        // across lines: the blank line stays a blank line, and the gutter and the +/− are not part of the text
        assertThat(DiffPaneBuilder.selectedText(lines, TranscriptCopy.Range.of(1, 12, 3, 8)))
                .isEqualTo("run() {\n\n    int ");
        // past the end of a line is the end of that line
        assertThat(DiffPaneBuilder.selectedText(lines, TranscriptCopy.Range.of(3, 0, 4, 99))).isEqualTo("    int x = 1;\n}");
        // a caret is not a selection
        assertThat(DiffPaneBuilder.selectedText(lines, TranscriptCopy.Range.of(1, 4, 1, 4))).isEmpty();
        assertThat(DiffPaneBuilder.selectedText(lines, TranscriptCopy.Range.NONE)).isEmpty();
    }

    @Test
    void theFileAfterTheChangeIsTheNewSideOfAWholeFileDiff() {
        String whole = """
                diff --git a/a.txt b/a.txt
                index 1..2 100644
                --- a/a.txt
                +++ b/a.txt
                @@ -1,3 +1,3 @@
                 one
                -two
                +too
                 three
                """;
        List<DiffPaneBuilder.Line> lines = DiffPaneBuilder.parse(whole).get(0).lines();

        assertThat(DiffPaneBuilder.newFileText(lines)).isEqualTo("one\ntoo\nthree\n");

        // «\ No newline at end of file» belongs to the side of the line above it: after the new side it is the
        // file's own last line that has none, after the old side the new file still ends with a newline.
        List<DiffPaneBuilder.Line> newSide = List.of(
                new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.CONTEXT, 1, 1, "one"),
                new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.ADDED, null, 2, "two"),
                new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.META, null, null, "\\ No newline at end of file"));
        List<DiffPaneBuilder.Line> oldSide = List.of(
                new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.REMOVED, 1, null, "one"),
                new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.META, null, null, "\\ No newline at end of file"),
                new DiffPaneBuilder.Line(DiffPaneBuilder.Kind.ADDED, null, 1, "one"));
        assertThat(DiffPaneBuilder.newFileText(newSide)).isEqualTo("one\ntwo");
        assertThat(DiffPaneBuilder.newFileText(oldSide)).isEqualTo("one\n");
        assertThat(DiffPaneBuilder.newFileText(List.of())).isEmpty();
    }

    @Test
    void diffPaneBuilderHandlesHeaderlessDiffsAndRecognisesDiffText() {
        // a removed SQL comment looks like a "---" header; the hunk counts keep it a line
        String sql = "diff --git a/a.sql b/a.sql\n--- a/a.sql\n+++ b/a.sql\n@@ -1,2 +1,2 @@\n--- old comment\n+-- new comment\n x\n"
                + "diff --git a/b.txt b/b.txt\nnew file mode 100644\n--- /dev/null\n+++ b/b.txt\n@@ -0,0 +1 @@\n+hello\n";
        List<DiffPaneBuilder.FileDiff> files = DiffPaneBuilder.parse(sql);
        assertThat(files).hasSize(2);
        assertThat(files.get(0).lines()).extracting(DiffPaneBuilder.Line::kind).containsExactly(
                DiffPaneBuilder.Kind.HUNK, DiffPaneBuilder.Kind.REMOVED, DiffPaneBuilder.Kind.ADDED, DiffPaneBuilder.Kind.CONTEXT);
        assertThat(files.get(0).lines().get(1).code()).isEqualTo("-- old comment");
        assertThat(files.get(1).path()).isEqualTo("b.txt");
        assertThat(files.get(1).additions()).isEqualTo(1);
        assertThat(files.get(1).change()).isEqualTo(DiffPaneBuilder.Change.ADDED);
        assertThat(files.get(1).directory()).isEmpty();

        // deleted and renamed files are told apart by their headers; a rename keeps the old path
        String moved = "diff --git a/old.txt b/old.txt\ndeleted file mode 100644\n--- a/old.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-gone\n"
                + "diff --git a/a/x.txt b/b/y.txt\nsimilarity index 90%\nrename from a/x.txt\nrename to b/y.txt\n--- a/a/x.txt\n+++ b/b/y.txt\n@@ -1 +1 @@\n-p\n+q\n";
        List<DiffPaneBuilder.FileDiff> movedFiles = DiffPaneBuilder.parse(moved);
        assertThat(movedFiles).extracting(DiffPaneBuilder.FileDiff::change).containsExactly(DiffPaneBuilder.Change.DELETED, DiffPaneBuilder.Change.RENAMED);
        assertThat(movedFiles.get(0).path()).isEqualTo("old.txt");
        assertThat(movedFiles.get(1).path()).isEqualTo("b/y.txt");
        assertThat(movedFiles.get(1).oldPath()).isEqualTo("a/x.txt");
        // a plain "diff -u" against /dev/null is an added file, not a rename from "/dev/null"
        assertThat(DiffPaneBuilder.parse("--- /dev/null\n+++ n.txt\n@@ -0,0 +1 @@\n+new\n")).singleElement().satisfies(f -> {
            assertThat(f.path()).isEqualTo("n.txt");
            assertThat(f.oldPath()).isNull();
            assertThat(f.change()).isEqualTo(DiffPaneBuilder.Change.ADDED);
        });

        // plain "diff -u" output names its files in the ---/+++ lines
        String plain = "--- a.txt\t2024-01-01\n+++ b.txt\t2024-01-02\n@@ -1 +1 @@\n-x\n+y\n";
        files = DiffPaneBuilder.parse(plain);
        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("b.txt");
        assertThat(files.get(0).oldPath()).isEqualTo("a.txt");
        assertThat(files.get(0).change()).isEqualTo(DiffPaneBuilder.Change.RENAMED);

        // a bare hunk still renders, without a file header
        assertThat(DiffPaneBuilder.parse("@@ -1,2 +1,2 @@\n a\n-b\n+c\n")).singleElement().satisfies(f -> {
            assertThat(f.path()).isEmpty();
            assertThat(f.lines()).hasSize(4);
        });

        assertThat(DiffPaneBuilder.looksLikeDiff(sql)).isTrue();
        assertThat(DiffPaneBuilder.looksLikeDiff(plain)).isTrue();
        assertThat(DiffPaneBuilder.looksLikeDiff("+ one\n- two\n")).isFalse();
        assertThat(DiffPaneBuilder.looksLikeDiff("The file has been updated.\n")).isFalse();
        assertThat(DiffPaneBuilder.looksLikeDiff(null)).isFalse();
        assertThat(DiffPaneBuilder.kindOf("+++ b/x")).isEqualTo(DiffPaneBuilder.Kind.META);
        assertThat(DiffPaneBuilder.kindOf("+x")).isEqualTo(DiffPaneBuilder.Kind.ADDED);
        assertThat(DiffPaneBuilder.kindOf("@@ -1 +1 @@")).isEqualTo(DiffPaneBuilder.Kind.HUNK);
    }

    @Test
    void workLogModelKeepsOneReasoningBlockFirstAndToolsStableAcrossUpdates() {
        WorkLogModel m = new WorkLogModel();
        // tools first, then reasoning, then more tool updates: the sequence that produced duplicate "Thinking" panes
        m.work(List.of(new Conversation.WorkItem("tool", "write_file", "/work/a", null, false)));
        m.reasoning("Analysing");
        m.work(List.of(new Conversation.WorkItem("tool", "write_file", "/work/a", "ok", false), new Conversation.WorkItem("tool", "bash", "ls", "x", true)));
        m.reasoning("Analysing\nUsing MarkdownArea.");
        m.notice("warning", "slow");
        m.work(List.of(new Conversation.WorkItem("tool", "write_file", "/work/a", "ok", false), new Conversation.WorkItem("tool", "bash", "ls", "x", true),
                new Conversation.WorkItem("tool", "bash", "pwd", null, false)));
        assertThat(m.entries()).extracting(WorkLogModel.Entry::kind).containsExactly(
                WorkLogModel.Kind.REASONING, WorkLogModel.Kind.NOTICE, WorkLogModel.Kind.TOOL, WorkLogModel.Kind.TOOL, WorkLogModel.Kind.TOOL);
        assertThat(m.entries()).extracting(WorkLogModel.Entry::key).containsExactly("reasoning", "notice:0", "item:0", "item:1", "item:2");
        assertThat(m.entries().get(0).text()).isEqualTo("Analysing\nUsing MarkdownArea.");
        assertThat(m.entries().get(2).tool().output()).isEqualTo("ok");
        assertThat(m.entries().get(3).tool().isError()).isTrue();
        assertThat(m.toolCount()).isEqualTo(3);
        assertThat(m.hasErrors()).isTrue();
        // redacted thinking (blank text) shows no block at all
        WorkLogModel blank = new WorkLogModel();
        blank.reasoning("");
        blank.reasoning("   ");
        assertThat(blank.entries()).isEmpty();
        assertThat(blank.isEmpty()).isTrue();
        WorkLogModel fromSaved = WorkLogModel.of(List.of(new Conversation.WorkItem("tool", "bash", "ls", "x", false)));
        assertThat(fromSaved.entries()).hasSize(1);
        // text items from the timeline render between the tools, in order
        WorkLogModel timeline = WorkLogModel.of(List.of(new Conversation.WorkItem("text", null, null, "Looking.", false),
                new Conversation.WorkItem("tool", "Bash", "{\"command\":\"ls\"}", "a", false), new Conversation.WorkItem("text", null, null, "Done.", false)));
        assertThat(timeline.entries()).extracting(WorkLogModel.Entry::kind).containsExactly(WorkLogModel.Kind.TEXT, WorkLogModel.Kind.TOOL, WorkLogModel.Kind.TEXT);
        assertThat(timeline.entries()).extracting(WorkLogModel.Entry::key).containsExactly("item:0", "item:1", "item:2");
        assertThat(timeline.entries().get(2).text()).isEqualTo("Done.");
        assertThat(timeline.hasText()).isTrue();
        // a blank text item, as conversations recorded before empty text blocks were dropped carry, renders nothing
        WorkLogModel blankText = WorkLogModel.of(List.of(new Conversation.WorkItem("text", null, null, "", false),
                new Conversation.WorkItem("tool", "Bash", "{\"command\":\"ls\"}", "a", false),
                new Conversation.WorkItem("text", null, null, "  ", false)));
        assertThat(blankText.entries()).extracting(WorkLogModel.Entry::kind).containsExactly(WorkLogModel.Kind.TOOL);
        assertThat(blankText.entries()).extracting(WorkLogModel.Entry::key).containsExactly("item:1");
        assertThat(blankText.hasText()).isFalse();
        assertThat(blankText.isEmpty()).isFalse();
        assertThat(WorkLogModel.of(List.of(new Conversation.WorkItem("text", null, null, "", false))).isEmpty()).isTrue();
    }

    @Test
    void toolTitlesShowTheInterestingInputFieldInFullWidth() {
        assertThat(ToolSummary.title("Bash", "{\"command\":\"sed -n '1,120p' lyngvi-core/pom.xml\",\"description\":\"Read\"}")).isEqualTo("Bash  sed -n '1,120p' lyngvi-core/pom.xml");
        assertThat(ToolSummary.title("Read", "{\"file_path\":\"/work/api/README.md\"}")).isEqualTo("Read  /work/api/README.md");
        assertThat(ToolSummary.title("Grep", "{\"pattern\":\"foo\",\"path\":\"src\"}")).isEqualTo("Grep  foo  src");
        assertThat(ToolSummary.title("Skill", "{\"skill\":\"claude-api\",\"args\":\"usage\"}")).isEqualTo("Skill  claude-api  usage");
        assertThat(ToolSummary.title("Custom", "{\"a\":1,\"b\":\"x\"}")).isEqualTo("Custom  a: 1, b: x");
        assertThat(ToolSummary.title("Echo", "plain text")).isEqualTo("Echo  plain text");
        assertThat(ToolSummary.title("Echo", null)).isEqualTo("Echo");
        String longCmd = "x".repeat(500);
        assertThat(ToolSummary.title("Bash", "{\"command\":\"" + longCmd + "\"}")).hasSize("Bash  ".length() + 500);
        assertThat(ToolSummary.title("Bash", "{\"command\":\"a\\nb\"}")).isEqualTo("Bash  a b");
    }

    @Test
    void toolCallsAreCategorisedByWhatTheyDo() {
        assertThat(ToolCall.categoryOf("Edit")).isEqualTo(ToolCall.Category.EDIT);
        assertThat(ToolCall.categoryOf("MultiEdit")).isEqualTo(ToolCall.Category.EDIT);
        assertThat(ToolCall.categoryOf("write")).isEqualTo(ToolCall.Category.EDIT);
        assertThat(ToolCall.categoryOf("NotebookEdit")).isEqualTo(ToolCall.Category.EDIT);
        assertThat(ToolCall.categoryOf("Read")).isEqualTo(ToolCall.Category.READ);
        assertThat(ToolCall.categoryOf("Grep")).isEqualTo(ToolCall.Category.SEARCH);
        assertThat(ToolCall.categoryOf("glob")).isEqualTo(ToolCall.Category.SEARCH);
        assertThat(ToolCall.categoryOf("Bash")).isEqualTo(ToolCall.Category.RUN);
        assertThat(ToolCall.categoryOf("WebFetch")).isEqualTo(ToolCall.Category.WEB);
        assertThat(ToolCall.categoryOf("Agent")).isEqualTo(ToolCall.Category.AGENT);
        assertThat(ToolCall.categoryOf("Skill")).isEqualTo(ToolCall.Category.AGENT);
        assertThat(ToolCall.categoryOf("TodoWrite")).isEqualTo(ToolCall.Category.OTHER);
        assertThat(ToolCall.categoryOf("str_replace_editor")).isEqualTo(ToolCall.Category.EDIT);
        assertThat(ToolCall.categoryOf("run_shell_command")).isEqualTo(ToolCall.Category.RUN);
        assertThat(ToolCall.categoryOf("mcp__jira__search")).isEqualTo(ToolCall.Category.OTHER);
        assertThat(ToolCall.categoryOf(null)).isEqualTo(ToolCall.Category.OTHER);
        assertThat(ToolCall.Category.SEARCH.key()).isEqualTo("search");
    }

    @Test
    void toolCallInputIsShownAsDiffCommandOrFieldsInsteadOfJson() {
        // an edit becomes a line diff of old and new text under the file's path
        List<ToolCall.Part> edit = ToolCall.parts("Edit", "{\"replace_all\":true,\"file_path\":\"/work/a/app.css\",\"old_string\":\"  a;\\n  b;\\n\",\"new_string\":\"  a;\\n  c;\\n  d;\\n\"}");
        assertThat(edit).singleElement().isInstanceOfSatisfying(ToolCall.Changes.class, c -> {
            assertThat(c.path()).isEqualTo("/work/a/app.css");
            assertThat(c.replaceAll()).isTrue();
            assertThat(c.lines()).extracting(DiffPaneBuilder.Line::kind).containsExactly(
                    DiffPaneBuilder.Kind.CONTEXT, DiffPaneBuilder.Kind.REMOVED, DiffPaneBuilder.Kind.ADDED, DiffPaneBuilder.Kind.ADDED);
            assertThat(c.lines().get(1).code()).isEqualTo("  b;");
            assertThat(c.additions()).isEqualTo(2);
            assertThat(c.deletions()).isEqualTo(1);
        });
        // OpenCode spells the same fields in camel case
        assertThat(ToolCall.parts("edit", "{\"filePath\":\"x.txt\",\"oldString\":\"a\",\"newString\":\"b\"}")).singleElement()
                .isInstanceOfSatisfying(ToolCall.Changes.class, c -> assertThat(c.path()).isEqualTo("x.txt"));
        // a written file is all added lines
        assertThat(ToolCall.parts("Write", "{\"file_path\":\"new.txt\",\"content\":\"one\\ntwo\\n\"}")).singleElement()
                .isInstanceOfSatisfying(ToolCall.Changes.class, c -> {
                    assertThat(c.additions()).isEqualTo(2);
                    assertThat(c.deletions()).isZero();
                });
        // several edits to one file: one change each, in order
        List<ToolCall.Part> multi = ToolCall.parts("MultiEdit", "{\"file_path\":\"m.txt\",\"edits\":[{\"old_string\":\"a\",\"new_string\":\"b\"},{\"old_string\":\"c\",\"new_string\":\"d\",\"replace_all\":true}]}");
        assertThat(multi).hasSize(2).allMatch(p -> p instanceof ToolCall.Changes);
        assertThat(((ToolCall.Changes) multi.get(1)).replaceAll()).isTrue();
        // a command is a prompt line; its other arguments stay as fields
        List<ToolCall.Part> bash = ToolCall.parts("Bash", "{\"command\":\"git status --short\",\"description\":\"Show status\",\"timeout\":120000}");
        assertThat(bash).containsExactly(new ToolCall.Command("git status --short"), new ToolCall.Field("description", "Show status"),
                new ToolCall.Field("timeout", "120000"));
        // a lookup is its fields; long or multi-line values become text blocks, nested values JSON
        List<ToolCall.Part> read = ToolCall.parts("Read", "{\"file_path\":\"/work/README.md\",\"offset\":10}");
        assertThat(read).containsExactly(new ToolCall.Field("file_path", "/work/README.md"), new ToolCall.Field("offset", "10"));
        List<ToolCall.Part> agent = ToolCall.parts("Agent", "{\"prompt\":\"Find the\\nthing\",\"opts\":{\"a\":1}}");
        assertThat(agent).containsExactly(new ToolCall.Text("prompt", "Find the\nthing"), new ToolCall.Field("opts", "{\"a\":1}"));
        assertThat(ToolCall.parts("Agent", "{\"prompt\":\"" + "x".repeat(200) + "\"}")).singleElement().isInstanceOf(ToolCall.Text.class);
        // a patch given as input is shown as a diff; non-JSON input as a command or text
        String patch = "--- a/f\n+++ b/f\n@@ -1 +1 @@\n-x\n+y\n";
        assertThat(ToolCall.parts("apply_patch", "{\"input\":\"" + patch.replace("\n", "\\n") + "\"}")).containsExactly(new ToolCall.Patch(patch));
        assertThat(ToolCall.parts("bash", "ls -la")).containsExactly(new ToolCall.Command("ls -la"));
        assertThat(ToolCall.parts("Echo", "hello")).containsExactly(new ToolCall.Text(null, "hello"));
        assertThat(ToolCall.parts("Echo", "{not json")).containsExactly(new ToolCall.Text(null, "{not json"));
        assertThat(ToolCall.parts("Echo", null)).isEmpty();
        assertThat(ToolCall.parts("Echo", "  ")).isEmpty();
        assertThat(ToolCall.copyText(bash)).isEqualTo("$ git status --short\ndescription: Show status\ntimeout: 120000");
        assertThat(ToolCall.copyText(edit)).isEqualTo("/work/a/app.css\n   a;\n-  b;\n+  c;\n+  d;");
    }

    @Test
    void lineDiffKeepsCommonLinesAsContext() {
        assertThat(LineDiff.of("a\nb\nc\n", "a\nx\nc\n")).extracting(DiffPaneBuilder.Line::kind).containsExactly(
                DiffPaneBuilder.Kind.CONTEXT, DiffPaneBuilder.Kind.REMOVED, DiffPaneBuilder.Kind.ADDED, DiffPaneBuilder.Kind.CONTEXT);
        assertThat(LineDiff.of("a\nb", "a\nb\nc")).extracting(DiffPaneBuilder.Line::kind).containsExactly(
                DiffPaneBuilder.Kind.CONTEXT, DiffPaneBuilder.Kind.CONTEXT, DiffPaneBuilder.Kind.ADDED);
        assertThat(LineDiff.of("", "a")).extracting(DiffPaneBuilder.Line::kind).containsExactly(DiffPaneBuilder.Kind.ADDED);
        assertThat(LineDiff.of("a", null)).extracting(DiffPaneBuilder.Line::kind).containsExactly(DiffPaneBuilder.Kind.REMOVED);
        assertThat(LineDiff.of("same", "same")).extracting(DiffPaneBuilder.Line::kind).containsExactly(DiffPaneBuilder.Kind.CONTEXT);
        assertThat(LineDiff.of("a\r\nb\r\n", "a\nb\n")).allMatch(l -> l.kind() == DiffPaneBuilder.Kind.CONTEXT);
        assertThat(LineDiff.added("x\ny")).hasSize(2).allMatch(l -> l.kind() == DiffPaneBuilder.Kind.ADDED);
        assertThat(LineDiff.text(LineDiff.of("a\nb", "a\nc"))).isEqualTo(" a\n-b\n+c");
    }

    @Test
    void usageFormatterFormatsCountsCostAndContext() {
        assertThat(UsageFormatter.count(950)).isEqualTo("950");
        assertThat(UsageFormatter.count(12_345)).isEqualTo("12.3k");
        assertThat(UsageFormatter.count(2_500_000)).isEqualTo("2.5M");
        assertThat(UsageFormatter.cost(0.0123)).isEqualTo("$0.012");
        assertThat(UsageFormatter.cost(3.5)).isEqualTo("$3.50");
        assertThat(UsageFormatter.cost(null)).isEmpty();
        assertThat(UsageFormatter.context(30_000L, 200_000L)).isEqualTo("30.0k / 200.0k (15%)");
        assertThat(UsageFormatter.context(null, null)).isEmpty();
        assertThat(UsageFormatter.contextRatio(180_000L, 200_000L)).isEqualTo(0.9);
        Conversation.Usage u = new Conversation.Usage(10, 20, 5, 0, 0.5, 30L, 200_000L);
        assertThat(UsageFormatter.summary(u)).isEqualTo("in 10 · out 20 · cache 5 · $0.50 · context 30 / 200.0k (0%)");
        assertThat(UsageFormatter.summary(null)).isEmpty();
    }

    @Test
    void usageFormatterFormatsQuotasAndRollups() {
        UsageLimit weekly = new UsageLimit("weekly", "Weekly", 380, 400.0, "requests", null);
        assertThat(UsageFormatter.limit(weekly)).isEqualTo("380 / 400 requests (95%)");
        assertThat(UsageFormatter.levelStyle(weekly.level())).isEqualTo("usage-bar-danger");
        assertThat(UsageFormatter.limit(new UsageLimit("credits", "Credits", 12.5, null, null, null))).isEqualTo("12.50");
        assertThat(UsageFormatter.levelStyle(UsageLimit.Level.WARN)).isEqualTo("usage-bar-warn");
        assertThat(UsageFormatter.levelStyle(UsageLimit.Level.OK)).isEmpty();
        assertThat(UsageFormatter.amount(1500)).isEqualTo("1,500");
        assertThat(UsageFormatter.moment(null)).isEmpty();
        assertThat(UsageFormatter.moment(0L)).isEmpty();
        // the zone is the user's, so the shape is what matters
        assertThat(UsageFormatter.moment(Instant.parse("2026-09-05T10:00:00Z").toEpochMilli())).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");

        AgentUsageReport.Totals t = new AgentUsageReport.Totals(3, 10, 20, 5, 1, 0.30);
        assertThat(UsageFormatter.totals(t)).isEqualTo("in 10 · out 20 · cache 6 · 0.00M total · $0.30");
        assertThat(UsageFormatter.totals(AgentUsageReport.Totals.EMPTY)).isEqualTo("in 0 · out 0 · 0.00M total");
        assertThat(UsageFormatter.totals(null)).isEmpty();
    }

    @Test
    void usageFormatterShowsAccumulatedSpendInMillionsOfTokens() {
        assertThat(UsageFormatter.tokens(0)).isEqualTo("0.00M");
        assertThat(UsageFormatter.tokens(12_345)).isEqualTo("0.01M");
        assertThat(UsageFormatter.tokens(1_234_567)).isEqualTo("1.23M");
        assertThat(UsageFormatter.tokens(250_000_000)).isEqualTo("250.00M");
        assertThat(UsageFormatter.spend(new AgentUsageReport.Totals(4, 1_000_000, 200_000, 3_000_000, 50_000, 4.5))).isEqualTo("4.25M · $4.50");
        assertThat(UsageFormatter.spend(new AgentUsageReport.Totals(1, 500_000, 0, 0, 0, null))).isEqualTo("0.50M");
        assertThat(UsageFormatter.spend(AgentUsageReport.Totals.EMPTY)).isEmpty();
        assertThat(UsageFormatter.spend(null)).isEmpty();
    }

    @Test
    void closeWarningIsPhrasedForOneOrManyConversations() {
        no.syntakserror.lyngvi.core.i18n.Messages m = no.syntakserror.lyngvi.core.i18n.Messages.forLanguage("en");
        assertThat(no.syntakserror.lyngvi.gui.App.activeConversationWarning(1, m)).isEqualTo("One conversation is still working. If you quit now, its work is interrupted.");
        assertThat(no.syntakserror.lyngvi.gui.App.activeConversationWarning(3, m)).isEqualTo("3 conversations are still working. If you quit now, their work is interrupted.");
    }

    /**
     * Closing one window of several, and changing a window's sandbox, both interrupt less than quitting does —
     * the warnings must not say «quit», or the user is told the wrong thing is about to happen.
     */
    @Test
    void closingOneWindowAndSwitchingSandboxAreWarnedAboutInTheirOwnWords() {
        no.syntakserror.lyngvi.core.i18n.Messages m = no.syntakserror.lyngvi.core.i18n.Messages.forLanguage("en");
        assertThat(no.syntakserror.lyngvi.gui.MainWindow.closeWindowWarning(1, m)).contains("this window").doesNotContain("quit");
        assertThat(no.syntakserror.lyngvi.gui.MainWindow.closeWindowWarning(4, m)).startsWith("4 conversations");
        assertThat(no.syntakserror.lyngvi.gui.MainController.switchWarning(1, m)).contains("One conversation").doesNotContain("quit");
        assertThat(no.syntakserror.lyngvi.gui.MainController.switchWarning(2, m)).startsWith("2 conversations");
    }

    /**
     * The switcher's lines: the open sandbox first and marked, the remembered ones after it, no sandbox twice,
     * nothing whose folder is gone, and «open in a new window» offered only for the sandboxes no window has.
     */
    @Test
    void sandboxSwitcherOffersEveryKnownSandboxOnceWithTheOpenOneFirst() {
        java.nio.file.Path open = java.nio.file.Path.of("/s/one");
        java.util.List<java.nio.file.Path> recent = java.util.List.of(
                java.nio.file.Path.of("/s/two"), java.nio.file.Path.of("/s/one/."), java.nio.file.Path.of("/s/gone"),
                java.nio.file.Path.of("/s/three"));
        java.util.function.Predicate<java.nio.file.Path> stillThere = p -> !p.endsWith("gone");
        java.util.function.Predicate<java.nio.file.Path> elsewhere = p -> p.endsWith("three");
        java.util.List<SandboxChoices.Entry> entries = SandboxChoices.of(open, recent, stillThere, elsewhere);
        assertThat(entries).extracting(e -> e.dir().toString())
                .containsExactly("/s/one", "/s/two", "/s/three");
        assertThat(entries.getFirst().open()).isTrue();
        assertThat(entries.getFirst().inAnotherWindow()).isFalse();
        assertThat(entries.get(2).inAnotherWindow()).isTrue();
        // a new window can be opened on the sandbox no window shows, and on neither of the other two
        assertThat(entries.stream().filter(SandboxChoices.Entry::free).map(e -> e.dir().toString()))
                .containsExactly("/s/two");
        // with no sandbox open every remembered one is free, and nothing is marked as chosen
        java.util.List<SandboxChoices.Entry> none = SandboxChoices.of(null, recent, stillThere, p -> false);
        assertThat(none).extracting(SandboxChoices.Entry::open).containsOnly(false);
        assertThat(none).allMatch(SandboxChoices.Entry::free).hasSize(3);
    }

    /** The sandbox as it is written in the toolbar and in the window title. */
    @Test
    void sandboxLabelShortensTheHomeDirectoryAndNamesTheFolder() {
        java.nio.file.Path home = java.nio.file.Path.of("/Users/jhs");
        assertThat(SandboxLabel.path(java.nio.file.Path.of("/Users/jhs/Projects/Sandbox1"), home)).isEqualTo("~/Projects/Sandbox1");
        assertThat(SandboxLabel.path(java.nio.file.Path.of("/Users/jhs/Projects/./Sandbox1"), home)).isEqualTo("~/Projects/Sandbox1");
        assertThat(SandboxLabel.path(home, home)).isEqualTo("~");
        assertThat(SandboxLabel.path(java.nio.file.Path.of("/opt/sandboxes/two"), home)).isEqualTo("/opt/sandboxes/two");
        // A directory beside the home directory only starts with its name, not with the directory itself.
        assertThat(SandboxLabel.path(java.nio.file.Path.of("/Users/jhs-old/s"), home)).isEqualTo("/Users/jhs-old/s");
        assertThat(SandboxLabel.path(java.nio.file.Path.of("/opt/s"), null)).isEqualTo("/opt/s");
        assertThat(SandboxLabel.name(java.nio.file.Path.of("/Users/jhs/Projects/Sandbox1"))).isEqualTo("Sandbox1");
        assertThat(SandboxLabel.name(java.nio.file.Path.of("/"))).isEqualTo("/");
    }

    @Test
    void transcriptScrollKeepsTheViewportTopWhenContentBelowItGrows() {
        // 1000px of content, 400px viewport, scrolled to the bottom: the top of the viewport is at 600px.
        double offset = TranscriptScroll.offset(1.0, 1000, 400);
        assertThat(offset).isEqualTo(600);
        // A tool call below the top expands the content to 1300px: the same top offset is a smaller fraction.
        assertThat(TranscriptScroll.vvalue(offset, 1300, 400)).isCloseTo(600.0 / 900.0, org.assertj.core.data.Offset.offset(1e-9));
        // Content shorter than the viewport cannot scroll.
        assertThat(TranscriptScroll.vvalue(offset, 300, 400)).isEqualTo(0);
        assertThat(TranscriptScroll.offset(0.5, 300, 400)).isEqualTo(0);
        // An offset beyond the new range clamps to the bottom.
        assertThat(TranscriptScroll.vvalue(900, 1000, 400)).isEqualTo(1.0);
    }

    @Test
    void theComposerGrowsWithThePromptFromThreeRowsToNine() {
        double row = 17.0;
        // a short prompt keeps the resting height, wrapped or not
        assertThat(ComposerRows.of(0, row)).isEqualTo(3);
        assertThat(ComposerRows.of(row, row)).isEqualTo(3);
        assertThat(ComposerRows.of(3 * row, row)).isEqualTo(3);
        // a longer one grows row by row, up to three times the resting height, after which the area scrolls
        assertThat(ComposerRows.of(4 * row, row)).isEqualTo(4);
        assertThat(ComposerRows.of(9 * row, row)).isEqualTo(9);
        assertThat(ComposerRows.of(40 * row, row)).isEqualTo(9);
        // a part-filled row counts, a rounding hair does not
        assertThat(ComposerRows.of(4.5 * row, row)).isEqualTo(5);
        assertThat(ComposerRows.of(4 * row + 0.4, row)).isEqualTo(4);
        // nothing measured yet
        assertThat(ComposerRows.of(100, 0)).isEqualTo(3);
    }

    @Test
    void draggingTheSidebarStaysBetweenItsBounds() {
        assertThat(SidebarWidth.clamp(300)).isEqualTo(300);
        assertThat(SidebarWidth.clamp(SidebarWidth.MIN)).isEqualTo(SidebarWidth.MIN);
        assertThat(SidebarWidth.clamp(SidebarWidth.MAX)).isEqualTo(SidebarWidth.MAX);
        // dragged past a bound, or started from a width no longer allowed: the nearest width that is
        assertThat(SidebarWidth.clamp(40)).isEqualTo(SidebarWidth.MIN);
        assertThat(SidebarWidth.clamp(-1000)).isEqualTo(SidebarWidth.MIN);
        assertThat(SidebarWidth.clamp(4000)).isEqualTo(SidebarWidth.MAX);
        // the pref width of a sidebar that has never been laid out
        assertThat(SidebarWidth.clamp(Double.NaN)).isEqualTo(SidebarWidth.DEFAULT);
    }

    @Test
    void transcriptFollowsNewContentOnlyWhileTheUserIsAtTheBottom() {
        assertThat(TranscriptScroll.follows(1.0)).isTrue();
        assertThat(TranscriptScroll.follows(0.985)).isTrue();
        assertThat(TranscriptScroll.follows(0.9)).isFalse();
    }

    @Test
    void toolCallChangeCountsSummariseAnEditForTheHeader() {
        // one edit: the lines added and removed
        assertThat(ToolCall.changeCounts("Edit", "{\"file_path\":\"a.css\",\"old_string\":\"  a;\\n  b;\\n\",\"new_string\":\"  a;\\n  c;\\n  d;\\n\"}"))
                .contains(new ToolCall.Counts(2, 1));
        // several edits to one file add up
        assertThat(ToolCall.changeCounts("MultiEdit", "{\"file_path\":\"m.txt\",\"edits\":[{\"old_string\":\"a\",\"new_string\":\"b\"},{\"old_string\":\"c\",\"new_string\":\"d\\ne\"}]}"))
                .contains(new ToolCall.Counts(3, 2));
        // a written file is all additions
        assertThat(ToolCall.changeCounts("Write", "{\"file_path\":\"new.txt\",\"content\":\"one\\ntwo\\n\"}")).contains(new ToolCall.Counts(2, 0));
        // a patch counts its +/- lines, not the file headers
        assertThat(ToolCall.changeCounts("apply_patch", "{\"input\":\"--- a/f\\n+++ b/f\\n@@ -1 +1 @@\\n-x\\n+y\\n+z\\n\"}")).contains(new ToolCall.Counts(2, 1));
        // calls without changes have no counts to show
        assertThat(ToolCall.changeCounts("Bash", "{\"command\":\"ls\"}")).isEmpty();
        assertThat(ToolCall.changeCounts("Read", "{\"file_path\":\"x\"}")).isEmpty();
        assertThat(ToolCall.changeCounts("Edit", null)).isEmpty();
    }

    @Test
    void transcriptTextRendersWhatTheBubbleShows() {
        List<Conversation.WorkItem> work = List.of(
                new Conversation.WorkItem("text", null, null, "Looking first.", false),
                new Conversation.WorkItem("tool", "Bash", "{\"command\":\"ls -la\"}", "a\nb", false),
                new Conversation.WorkItem("tool", "Read", "{\"file_path\":\"/x/y\"}", "nope", true),
                new Conversation.WorkItem("text", null, null, "Done: **two** files.", false));
        String t = TranscriptText.message("claude · 12:00:00", "ignored when the timeline has text", List.of(), work, "The agent run failed.");
        assertThat(t).isEqualTo("""
                claude · 12:00:00
                Looking first.
                → Bash  ls -la
                → Read  /x/y  ✗
                Done: **two** files.
                The agent run failed.""");
        assertThat(TranscriptText.message("You · 12:00:01", "hello\n", List.of("a.txt", "b.txt"), null, null))
                .isEqualTo("You · 12:00:01\nhello\n📎 a.txt, b.txt");
        assertThat(TranscriptText.message("claude · 12:00:02", "plain text with tools stored apart", List.of(),
                List.of(new Conversation.WorkItem("tool", "Grep", "{\"pattern\":\"foo\"}", "", false)), null))
                .isEqualTo("claude · 12:00:02\nplain text with tools stored apart\n→ Grep  foo");
        assertThat(TranscriptText.message("", null, List.of(), List.of(), null)).isEmpty();
        assertThat(TranscriptText.join(List.of("one", "two"))).isEqualTo("one\n\ntwo");
    }

    /** Two bubbles of laid-out text: author lines, a message, and a bullet sharing its line with its item. */
    private static final List<TranscriptCopy.Run> RUNS = List.of(
            new TranscriptCopy.Run("You · 12:00:00", 0, 0),
            new TranscriptCopy.Run("Rename the button", 0, 1),
            new TranscriptCopy.Run("claude · 12:00:09", 1, 2),
            new TranscriptCopy.Run("•  ", 1, 3),
            new TranscriptCopy.Run("renamed it", 1, 3));

    @Test
    void transcriptSelectionCopiesTheRunsTheWayTheTranscriptReads() {
        // a run on its own is a line, runs sharing a line are not broken up, and a new bubble is a blank line apart
        assertThat(TranscriptCopy.text(RUNS, new TranscriptCopy.Range(0, 0, 4, 10)))
                .isEqualTo("You · 12:00:00\nRename the button\n\nclaude · 12:00:09\n•  renamed it");
        // part of one run
        assertThat(TranscriptCopy.text(RUNS, new TranscriptCopy.Range(1, 7, 1, 13))).isEqualTo("the bu");
        // ends that fall on a run's edge take nothing from it, and add no empty line either
        assertThat(TranscriptCopy.text(RUNS, new TranscriptCopy.Range(1, 17, 3, 0))).isEqualTo("claude · 12:00:09");
        // a child of a text flow that is not text holds one position in it and says nothing
        assertThat(TranscriptCopy.text(List.of(new TranscriptCopy.Run("a\uFFFCb", 0, 0)), new TranscriptCopy.Range(0, 0, 0, 3)))
                .isEqualTo("ab");
        assertThat(TranscriptCopy.text(RUNS, TranscriptCopy.Range.NONE)).isEmpty();
        assertThat(TranscriptCopy.text(RUNS, new TranscriptCopy.Range(2, 3, 2, 3))).isEmpty();
    }

    @Test
    void transcriptSelectionRangeReadsTopToBottomWhicheverWayTheDragWent() {
        assertThat(TranscriptCopy.Range.of(3, 2, 1, 5)).isEqualTo(new TranscriptCopy.Range(1, 5, 3, 2));
        assertThat(TranscriptCopy.Range.of(1, 9, 1, 4)).isEqualTo(new TranscriptCopy.Range(1, 4, 1, 9));
        assertThat(TranscriptCopy.Range.of(1, 4, 1, 9)).isEqualTo(new TranscriptCopy.Range(1, 4, 1, 9));
        assertThat(TranscriptCopy.Range.of(-1, 0, 2, 0).isEmpty()).isTrue();
        assertThat(new TranscriptCopy.Range(2, 4, 2, 4).isEmpty()).isTrue();
        // a run the range runs through, one it ends past the end of, and one outside it
        TranscriptCopy.Range range = new TranscriptCopy.Range(1, 3, 2, 99);
        assertThat(range.startIn(1, 10)).isEqualTo(3);
        assertThat(range.endIn(1, 10)).isEqualTo(10);
        assertThat(range.startIn(2, 5)).isZero();
        assertThat(range.endIn(2, 5)).isEqualTo(5);
        assertThat(range.startIn(0, 5)).isEqualTo(-1);
    }

    @Test
    void transcriptSelectionTakesAWholeWordOnADoubleClick() {
        assertThat(TranscriptCopy.word("Rename the button", 8)).isEqualTo(new TranscriptCopy.Word(7, 10));
        // a path, a file name or a model id is one word: the dots, dashes and slashes inside it join it up
        assertThat(word("open src/main/App.java now", 12)).isEqualTo("src/main/App.java");
        assertThat(word("claude-sonnet-4-5 it is", 3)).isEqualTo("claude-sonnet-4-5");
        // a full stop that ends a sentence belongs to no word, and a click just past a word still takes it
        assertThat(word("Done.", 4)).isEqualTo("Done");
        assertThat(word("a  b", 1)).isEqualTo("a");
        // anything else is one character
        assertThat(word(" - ", 1)).isEqualTo("-");
        assertThat(TranscriptCopy.word("", 0)).isEqualTo(new TranscriptCopy.Word(0, 0));
        assertThat(TranscriptCopy.word("x", 9)).isEqualTo(new TranscriptCopy.Word(0, 1));
    }

    private static String word(String text, int at) {
        TranscriptCopy.Word w = TranscriptCopy.word(text, at);
        return text.substring(w.from(), w.to());
    }

    @Test
    void transcriptSelectionPicksTheRunThePointerPointsAt() {
        // two runs on one line, a third on the next
        List<Bounds> runs = List.of(new BoundingBox(10, 0, 40, 20), new BoundingBox(50, 0, 60, 20), new BoundingBox(10, 30, 100, 20));
        assertThat(TranscriptCopy.nearest(runs, 60, 10)).isEqualTo(1);    // under the pointer
        assertThat(TranscriptCopy.nearest(runs, 5, 10)).isZero();         // left of the line: the first run on it
        assertThat(TranscriptCopy.nearest(runs, 500, 10)).isEqualTo(1);   // past the line's end: the last run on it
        assertThat(TranscriptCopy.nearest(runs, 60, 25)).isEqualTo(1);    // between the lines: the line above
        assertThat(TranscriptCopy.nearest(runs, 60, 900)).isEqualTo(2);   // below everything: the last run
        assertThat(TranscriptCopy.nearest(runs, 60, -50)).isZero();       // above everything: the first run
        assertThat(TranscriptCopy.nearest(List.of(), 0, 0)).isEqualTo(-1);
    }

    @Test
    void aRunInACodeBlockIsHitTestedOnWhatTheBlockShowsOfIt() {
        // a code block 100 wide, scrolled 30 to the right: the line it holds is 300 wide and starts left of the block
        Bounds view = new BoundingBox(10, 0, 100, 20);
        Bounds line = new BoundingBox(-20, 0, 300, 20);
        assertThat(TranscriptCopy.clamp(line, view)).isEqualTo(new BoundingBox(10, 0, 100, 20));
        // a run well inside the block is left as it is, and one scrolled out of sight becomes an empty box
        assertThat(TranscriptCopy.clamp(new BoundingBox(20, 2, 40, 16), view)).isEqualTo(new BoundingBox(20, 2, 40, 16));
        assertThat(TranscriptCopy.clamp(new BoundingBox(200, 0, 50, 20), view)).isEqualTo(new BoundingBox(200, 0, 0, 20));

        // the clamped box is what the pointer is hit-tested against: inside the block it is the block's line, and a
        // point to the right of the block — past what it shows of that line — is no longer inside the line at all
        List<Bounds> runs = List.of(TranscriptCopy.clamp(line, view), new BoundingBox(10, 20, 300, 20));
        assertThat(runs.getFirst().contains(60, 10)).isTrue();
        assertThat(runs.getFirst().contains(250, 10)).isFalse();
        assertThat(TranscriptCopy.nearest(runs, 60, 10)).isZero();
        assertThat(TranscriptCopy.nearest(runs, 250, 25)).isEqualTo(1);
    }

    private static final List<Model> CATALOGUE = List.of(
            new Model("anthropic/claude-sonnet-4-5", "Claude Sonnet 4.5 (Anthropic)", true, false),
            new Model("anthropic/claude-opus-4-1", "Claude Opus 4.1 (Anthropic)", false, false),
            new Model("openai/gpt-5", "gpt-5 (openai)", false, false),
            new Model("openrouter/qwen/qwen3-coder", "qwen/qwen3-coder (openrouter)", false, false));

    @Test
    void starredModelsLeadTheListInTheOrderTheUserStarredThem() {
        List<ModelChoices.Choice> ordered = ModelChoices.order(CATALOGUE, List.of("openai/gpt-5", "anthropic/claude-opus-4-1"));
        assertThat(ordered).extracting(ModelChoices.Choice::id).containsExactly(
                "openai/gpt-5", "anthropic/claude-opus-4-1", "anthropic/claude-sonnet-4-5", "openrouter/qwen/qwen3-coder");
        assertThat(ordered).extracting(ModelChoices.Choice::favourite).containsExactly(true, true, false, false);
        assertThat(ordered.getFirst().label()).isEqualTo("gpt-5 (openai)");

        // a favourite the catalogue no longer lists is still offered, labelled with its id
        List<ModelChoices.Choice> gone = ModelChoices.order(CATALOGUE, List.of("openai/gpt-4"));
        assertThat(gone.getFirst()).isEqualTo(new ModelChoices.Choice("openai/gpt-4", "openai/gpt-4", true));
        assertThat(gone).hasSize(5);
    }

    @Test
    void aPastedImageIsNamedAfterTheSecondItWasPastedAndNeverOverwritesAnother(@TempDir Path tmp) throws Exception {
        Instant at = ZonedDateTime.of(2026, 9, 7, 14, 30, 12, 0, ZoneId.systemDefault()).toInstant();
        Path first = ClipboardImage.fileName(tmp, at);
        assertThat(first.getFileName()).hasToString("pasted-20260907-143012.png");

        Files.createFile(first);
        Path second = ClipboardImage.fileName(tmp, at);
        assertThat(second.getFileName()).hasToString("pasted-20260907-143012-2.png");

        Files.createFile(second);
        assertThat(ClipboardImage.fileName(tmp, at).getFileName()).hasToString("pasted-20260907-143012-3.png");
    }

    @Test
    void searchMatchesEveryWordAgainstBothTheIdAndTheLabel() {
        List<ModelChoices.Choice> all = ModelChoices.order(CATALOGUE, List.of());
        assertThat(ModelChoices.filter(all, "  ")).isEqualTo(all);
        assertThat(ModelChoices.filter(all, "OPENAI")).extracting(ModelChoices.Choice::id).containsExactly("openai/gpt-5");
        assertThat(ModelChoices.filter(all, "claude 4.5")).extracting(ModelChoices.Choice::id)
                .containsExactly("anthropic/claude-sonnet-4-5");
        assertThat(ModelChoices.filter(all, "qwen")).hasSize(1);
        assertThat(ModelChoices.filter(all, "nothing here")).isEmpty();
    }

    @Test
    void togglingAStarAddsOrRemovesItAndKeepsTheOrderOfTheOthers() {
        List<String> one = ModelChoices.toggle(List.of(), "openai/gpt-5");
        assertThat(one).containsExactly("openai/gpt-5");
        assertThat(ModelChoices.toggle(one, "anthropic/claude-opus-4-1")).containsExactly("openai/gpt-5", "anthropic/claude-opus-4-1");
        assertThat(ModelChoices.toggle(one, "openai/gpt-5")).isEmpty();
    }

    @Test
    void theShortListIsTheStarredModelsAndAlwaysTheOneInUse() {
        List<ModelChoices.Choice> ordered = ModelChoices.order(CATALOGUE, List.of("openai/gpt-5"));
        assertThat(ModelChoices.quickPicks(ordered, "openai/gpt-5", 2)).extracting(ModelChoices.Choice::id)
                .containsExactly("openai/gpt-5");
        assertThat(ModelChoices.quickPicks(ordered, "openrouter/qwen/qwen3-coder", 2)).extracting(ModelChoices.Choice::id)
                .containsExactly("openrouter/qwen/qwen3-coder", "openai/gpt-5");

        // nothing starred yet: the head of the catalogue, which is what the agent descriptor declares
        List<ModelChoices.Choice> none = ModelChoices.order(CATALOGUE, List.of());
        assertThat(ModelChoices.quickPicks(none, null, 2)).extracting(ModelChoices.Choice::id)
                .containsExactly("anthropic/claude-sonnet-4-5", "anthropic/claude-opus-4-1");
        assertThat(ModelChoices.quickPicks(none, "openai/gpt-5", 2)).extracting(ModelChoices.Choice::id)
                .containsExactly("openai/gpt-5", "anthropic/claude-sonnet-4-5", "anthropic/claude-opus-4-1");
        assertThat(ModelChoices.quickPicks(List.of(), "x", 2)).extracting(ModelChoices.Choice::id).containsExactly("x");
    }

    /** A model server of your own is a way of working, so it is a row of its own — with its models and no others. */
    @Test
    void anAgentWithAModelServerOfItsOwnIsOfferedTwice() {
        AgentInfo claude = new AgentInfo("claude-code", "Claude Code", true, true, true, "subscription", "me", false, 0);
        AgentInfo opencode = new AgentInfo("opencode", "OpenCode", true, true, false, null, null, true, 2);

        List<AgentChoices.Choice> rows = AgentChoices.of(List.of(claude, opencode));

        assertThat(rows).extracting(c -> AgentChoices.label(c, "local models", "not logged in")).containsExactly(
                "Claude Code (claude-code)", "OpenCode (opencode)  not logged in", "OpenCode (local models)");
        assertThat(rows).extracting(AgentChoices.Choice::localOnly).containsExactly(false, false, true);
        // both rows are the same agent, and the dialog starts on the agent itself
        assertThat(rows.get(2).id()).isEqualTo("opencode");
        assertThat(AgentChoices.initial(rows, "opencode")).isEqualTo(rows.get(1));
        assertThat(AgentChoices.initial(rows, "no-such-agent")).isEqualTo(rows.getFirst());

        // an agent with nothing configured keeps the one row it always had
        assertThat(AgentChoices.of(List.of(claude))).hasSize(1);
        assertThat(AgentChoices.of(List.of())).isEmpty();
        assertThat(AgentChoices.initial(List.of(), "opencode")).isNull();

        List<Model> catalogue = new java.util.ArrayList<>(CATALOGUE);
        catalogue.add(new Model("lmstudio/qwen3-coder-30b", "qwen3-coder-30b (lmstudio)", false, true));
        assertThat(AgentChoices.models(catalogue, rows.get(1))).isEqualTo(catalogue);
        assertThat(AgentChoices.models(catalogue, rows.get(2))).extracting(Model::id).containsExactly("lmstudio/qwen3-coder-30b");
        assertThat(AgentChoices.models(catalogue, null)).isEqualTo(catalogue);
    }

    /**
     * «Ask the agent» picks who answers next to the composer, so a question can be meant for another agent than the
     * one answering the conversation on screen. That question needs a conversation of its own; the row for an
     * agent's own model server does not, since it is the same agent with a narrower list of models.
     */
    @Test
    void aQuestionGoesToTheOpenConversationOnlyWhenItIsTheSameAgent() {
        AgentInfo opencode = new AgentInfo("opencode", "OpenCode", true, true, true, "key", "me", true, 2);
        List<AgentChoices.Choice> rows = AgentChoices.of(List.of(opencode));

        assertThat(AgentChoices.answers(rows.get(0), "opencode")).isTrue();
        assertThat(AgentChoices.answers(rows.get(1), "opencode")).isTrue();  // the server's row is the same agent
        assertThat(AgentChoices.answers(rows.get(0), "claude-code")).isFalse();

        // nothing open: the first question of all makes the conversation, whatever is picked
        assertThat(AgentChoices.answers(rows.get(0), null)).isFalse();
        assertThat(AgentChoices.answers(rows.get(0), "")).isFalse();
        // nothing picked (no sandbox, no agents): the conversation's own agent answers
        assertThat(AgentChoices.answers(null, "opencode")).isTrue();
        assertThat(AgentChoices.answers(null, null)).isFalse();
    }

    /**
     * «None configured yet» is an invitation and «not a thing for this agent» is not: the Agents page says the one
     * in its own column and leaves the other blank, and the button that would open the dialog is offered for the
     * first and refused for the second. One reading, so the column and the button cannot disagree.
     */
    @Test
    void anAgentWithoutAServerOfItsOwnIsToldApartFromOneThatHasNoneYet() {
        AgentInfo mock = new AgentInfo("mock", "Mock agent", true, false, false, null, "mock", false, 0);
        AgentInfo opencode = new AgentInfo("opencode", "OpenCode", true, true, false, null, null, true, 0);
        AgentInfo configured = new AgentInfo("opencode", "OpenCode", true, true, false, null, null, true, 2);

        assertThat(AgentChoices.ownModels(mock)).isEqualTo(AgentChoices.OwnModels.UNSUPPORTED);
        assertThat(AgentChoices.ownModels(opencode)).isEqualTo(AgentChoices.OwnModels.NONE);
        assertThat(AgentChoices.ownModels(configured)).isEqualTo(AgentChoices.OwnModels.CONFIGURED);
        // nothing selected is nothing to set up, which is the same answer as an agent that cannot use one
        assertThat(AgentChoices.ownModels(null)).isEqualTo(AgentChoices.OwnModels.UNSUPPORTED);

        // the second row is still about models that exist, not about the agent being able to have them
        assertThat(AgentChoices.of(List.of(opencode))).hasSize(1);
        assertThat(AgentChoices.of(List.of(configured))).hasSize(2);
    }

    /** The handful of models the user set up themselves get a group of their own, or the dropdown buries them. */
    @Test
    void theDropdownPutsAModelServerOfYourOwnInAGroupOfItsOwn() {
        ModelChoices.Groups groups = new ModelChoices.Groups("Local LLM models", "Other models");
        List<Model> withLocal = new java.util.ArrayList<>(CATALOGUE);
        withLocal.add(new Model("lmstudio/qwen3-coder-30b", "qwen3-coder-30b (lmstudio)", false, true));
        withLocal.add(new Model("lmstudio/gpt-oss-120b", "gpt-oss-120b (lmstudio)", false, true));

        List<ModelChoices.Choice> rows = ModelChoices.dropdown(withLocal, List.of("lmstudio/qwen3-coder-30b"), null, groups);

        // every local model is offered, under its heading, before the short list of everything else under theirs
        assertThat(rows).extracting(ModelChoices.Choice::label).containsExactly(
                "Local LLM models", "qwen3-coder-30b (lmstudio)", "gpt-oss-120b (lmstudio)",
                "Other models", "Claude Sonnet 4.5 (Anthropic)", "Claude Opus 4.1 (Anthropic)",
                "gpt-5 (openai)", "qwen/qwen3-coder (openrouter)");
        assertThat(rows).extracting(ModelChoices.Choice::heading)
                .containsExactly(true, false, false, true, false, false, false, false);
        // a heading is not a model: it has no id, which is how the dropdown keeps it from being picked
        assertThat(rows.getFirst().id()).isEmpty();

        // the model in use is among the rows whichever group it is in, and never twice
        assertThat(ModelChoices.dropdown(withLocal, List.of(), "lmstudio/gpt-oss-120b", groups))
                .extracting(ModelChoices.Choice::id).containsOnlyOnce("lmstudio/gpt-oss-120b");
        assertThat(ModelChoices.dropdown(withLocal, List.of(), "openrouter/qwen/qwen3-coder", groups))
                .extracting(ModelChoices.Choice::id).contains("openrouter/qwen/qwen3-coder");

        // nothing local: one group is no group, so it is the short list exactly as before, with no headings at all
        assertThat(ModelChoices.dropdown(CATALOGUE, List.of("openai/gpt-5"), "openai/gpt-5", groups))
                .isEqualTo(ModelChoices.quickPicks(ModelChoices.order(CATALOGUE, List.of("openai/gpt-5")),
                        "openai/gpt-5", ModelChoices.QUICK_PICKS));

        // nothing but local — what the agent's «local models» row offers — is a plain list, headings and all gone
        List<Model> onlyLocal = withLocal.stream().filter(Model::local).toList();
        assertThat(ModelChoices.dropdown(onlyLocal, List.of(), "lmstudio/gpt-oss-120b", groups))
                .extracting(ModelChoices.Choice::id).containsExactly("lmstudio/qwen3-coder-30b", "lmstudio/gpt-oss-120b");
        assertThat(ModelChoices.dropdown(onlyLocal, List.of(), null, groups))
                .noneMatch(ModelChoices.Choice::heading);
    }

    @Test
    void aPresetReadsAsItsLabelAndTheTwoNumbersThatDecideTheChoice() {
        Messages m = Messages.forLanguage("en");
        ResourcePreset dev = ResourcePreset.find(ResourcePreset.BUILT_IN, "dev").orElseThrow();

        assertThat(PresetChoices.describe(dev, m)).isEqualTo("Development - tests, bundlers, linters  (8g, 4.0 cpus)");
        // a profile's own preset keeps its label; an untranslated id falls back to the id itself
        assertThat(PresetChoices.describe(new ResourcePreset("house", "House style", dev.limits()), m))
                .startsWith("House style");
        assertThat(PresetChoices.describe(new ResourcePreset("house", null, dev.limits()), m)).startsWith("house");

        assertThat(PresetChoices.matching(ResourcePreset.BUILT_IN, dev.limits())).contains(dev);
        assertThat(PresetChoices.matching(ResourcePreset.BUILT_IN, new ResourceLimits("7g", "12g", "4.0", "2048"))).isEmpty();
        assertThat(PresetChoices.matching(ResourcePreset.BUILT_IN, null)).isEmpty();
    }

    @Test
    void everyDeliveryIsOfferedAsABaselineWithItsTimeAndBranch() {
        ResourceBundle b = Messages.forLanguage("en").bundle();
        Conversation fresh = conversation();
        assertThat(BaselineChoices.of(fresh, b)).containsExactly(new BaselineChoices.Choice("start", "Session start"));
        assertThat(BaselineChoices.initial(fresh)).isEqualTo("start");

        Conversation c = conversation(delivery(2026, 9, 7, 9, 5, "agent/login"), delivery(2026, 9, 7, 11, 40, "agent/tests"));
        assertThat(BaselineChoices.of(c, b)).containsExactly(
                new BaselineChoices.Choice("start", "Session start"),
                new BaselineChoices.Choice("0", "Delivery 2026-09-07 09:05 (agent/login)"),
                new BaselineChoices.Choice("1", "Delivery 2026-09-07 11:40 (agent/tests)"));

        // the values are the indices the core's --from takes, and «last» is the newest delivery
        assertThat(BaselineChoices.initial(c)).isEqualTo("1");
        assertThat(BaselineChoices.canonical("last", c)).isEqualTo("1");
        assertThat(BaselineChoices.canonical("start", c)).isEqualTo("start");
        assertThat(BaselineChoices.label("last", c, b)).isEqualTo("Delivery 2026-09-07 11:40 (agent/tests)");
        assertThat(BaselineChoices.label("7", c, b)).isEqualTo("7");
    }

    /** «Not tested» and «not on the server» must not look the same: the marks only appear once the server has answered. */
    @Test
    void localModelRowsAreOnlyMarkedOnceTheServerHasAnswered() {
        List<String> configured = List.of("qwen-flash", "qwen-coder");

        assertThat(LocalModelChoices.rows(configured, null)).extracting(LocalModelChoices.Row::state)
                .containsExactly(LocalModelChoices.State.UNKNOWN, LocalModelChoices.State.UNKNOWN);
        assertThat(LocalModelChoices.rows(configured, new LocalModelConfig.Probe(false, 0, "no answer", List.of())))
                .extracting(LocalModelChoices.Row::state)
                .containsExactly(LocalModelChoices.State.UNKNOWN, LocalModelChoices.State.UNKNOWN);

        LocalModelConfig.Probe answered = new LocalModelConfig.Probe(true, 200, "2 model(s)", List.of("qwen-flash", "gpt-oss"));
        assertThat(LocalModelChoices.rows(configured, answered))
                .containsExactly(new LocalModelChoices.Row("qwen-flash", LocalModelChoices.State.AVAILABLE),
                        new LocalModelChoices.Row("qwen-coder", LocalModelChoices.State.MISSING));
        assertThat(LocalModelChoices.notListed(configured, answered)).containsExactly("gpt-oss");
        assertThat(LocalModelChoices.notListed(configured, null)).isEmpty();
    }

    /** Configuring a model is choosing it: it is starred, so «New conversation» offers it without the picker. */
    @Test
    void savingTheLocalModelsStarsThemAndUnstarsTheOnesTakenOut() {
        List<String> none = LocalModelChoices.star(List.of(), "lmstudio", List.of(), List.of("qwen-flash", "qwen-coder"));
        assertThat(none).containsExactly("lmstudio/qwen-flash", "lmstudio/qwen-coder");

        // a model taken out of the configuration loses its star; stars on other models are untouched
        List<String> favourites = List.of("anthropic/claude-sonnet-4-5", "lmstudio/qwen-flash", "lmstudio/qwen-coder");
        assertThat(LocalModelChoices.star(favourites, "lmstudio", List.of("qwen-flash", "qwen-coder"), List.of("qwen-coder")))
                .containsExactly("anthropic/claude-sonnet-4-5", "lmstudio/qwen-coder");

        // saving the same list again changes nothing, and a star the user set by hand is not added twice
        assertThat(LocalModelChoices.star(favourites, "lmstudio", List.of("qwen-flash", "qwen-coder"), List.of("qwen-flash", "qwen-coder")))
                .isEqualTo(favourites);
    }

    @Test
    void addingALocalModelTrimsAndIgnoresBlanksAndDuplicates() {
        assertThat(LocalModelChoices.add(List.of(), "  qwen-flash ")).containsExactly("qwen-flash");
        assertThat(LocalModelChoices.add(List.of("qwen-flash"), "qwen-flash")).containsExactly("qwen-flash");
        assertThat(LocalModelChoices.add(List.of("qwen-flash"), "   ")).containsExactly("qwen-flash");
        assertThat(LocalModelChoices.add(List.of("qwen-flash"), null)).containsExactly("qwen-flash");
    }

    /**
     * The lines at the top of a conversation: when it was made and who answers in it, then where each working copy
     * came from — read from the conversation, so they say the same thing long after the creation itself.
     */
    @Test
    void theSetupLogSaysWhenTheSessionWasMadeAndWhereEveryWorkingCopyCameFrom() {
        Messages m = Messages.forLanguage("en");
        java.util.Map<String, Conversation.RepoBase> base = new java.util.LinkedHashMap<>();
        base.put("api", new Conversation.RepoBase("1a2b3c4d5e6f", "main", "99aa", true, History.SHALLOW, 20));
        base.put("web", new Conversation.RepoBase("abcdef0", "HEAD", "88bb", false, History.NONE, null));
        base.put("docs", new Conversation.RepoBase("f0f0f0f0f0f0", "release", "77cc", false, History.FULL, null));

        List<String> lines = SessionSetupLog.lines(conversation(List.of("api", "web", "docs"), base), m, ZoneId.of("Europe/Oslo"));

        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).isEqualTo("Conversation created 2025-09-04 17:33  ·  agent claude-code");
        assertThat(lines.get(1)).isEqualTo("Working copies in /work: "
                + "api (main @ 1a2b3c4, shallow clone, 20 commits, your uncommitted changes included)"
                + " · web (HEAD @ abcdef0, no git history)"
                + " · docs (release @ f0f0f0f, full history of the branch)");
    }

    @Test
    void theSetupLogOfAConversationWithoutWorkingCopiesSaysSo() {
        Messages m = Messages.forLanguage("en");

        List<String> lines = SessionSetupLog.lines(conversation(List.of(), Map.of()), m, ZoneId.of("Europe/Oslo"));

        assertThat(lines.get(1)).isEqualTo("No working copies: /work holds output/ and the files you attach.");
    }

    /** A conversation written before a repository's baseline was recorded still gets its name on the line. */
    @Test
    void theSetupLogNamesARepositoryWithoutARecordedBaseline() {
        Messages m = Messages.forLanguage("en");

        List<String> lines = SessionSetupLog.lines(conversation(List.of("api"), Map.of()), m, ZoneId.of("Europe/Oslo"));

        assertThat(lines.get(1)).isEqualTo("Working copies in /work: api");
    }

    private static Conversation conversation(List<String> repos, Map<String, Conversation.RepoBase> base) {
        return new Conversation(Conversation.CURRENT_SCHEMA_VERSION, "6f1c0000-0000-4000-8000-000000000001", "Fix login",
                1757000000000L, 1757000000000L, 1757000000000L, "claude-code", null, null, repos,
                new ResourceLimits("8g", "12g", "4.0", "2048"), "lyngvi-sb-6f1c0000", base, List.of(), List.of());
    }

    private static Conversation.Delivery delivery(int year, int month, int day, int hour, int minute, String branch) {
        long at = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new Conversation.Delivery(at, branch, "Fix login", "commit",
                Map.of("api", new Conversation.DeliveryRepo(2, "c0ffee", "beef")));
    }

    private static Conversation conversation(Conversation.Delivery... deliveries) {
        return new Conversation(Conversation.CURRENT_SCHEMA_VERSION, "6f1c0000-0000-4000-8000-000000000001", "Fix login",
                1757000000000L, 1757000000000L, 1757000000000L, "claude-code", null, null, List.of("api"),
                new ResourceLimits("8g", "12g", "4.0", "2048"), "lyngvi-sb-6f1c0000",
                Map.of("api", new Conversation.RepoBase("abc", "main", "abc", false, History.SHALLOW, 100)),
                List.of(deliveries), List.of());
    }

    @Test
    void newConversationTicksTheRepositoriesTickedLastTimeInThisSandbox() {
        List<String> names = List.of("api", "web", "docs");
        var fresh = NewSessionDialog.RepoSelection.initial(names, java.util.Optional.empty());
        assertThat(fresh.ticked()).containsExactlyInAnyOrder("api", "web", "docs");
        assertThat(fresh.none()).isFalse();
        var some = NewSessionDialog.RepoSelection.initial(names, java.util.Optional.of(List.of("web", "gone")));
        assertThat(some.ticked()).containsExactly("web");
        assertThat(some.none()).isFalse();
        var stale = NewSessionDialog.RepoSelection.initial(names, java.util.Optional.of(List.of("gone")));
        assertThat(stale.ticked()).containsExactlyInAnyOrder("api", "web", "docs");
        var none = NewSessionDialog.RepoSelection.initial(names, java.util.Optional.of(List.of()));
        assertThat(none.none()).isTrue();
        assertThat(none.ticked()).containsExactlyInAnyOrder("api", "web", "docs");
    }

    @Test
    void repositoryPathsShowTheHomeDirectoryAsATilde() {
        Path home = Path.of("/Users/me");
        assertThat(NewSessionDialog.abbreviate(Path.of("/Users/me/Projects/api"), home)).isEqualTo("~/Projects/api");
        assertThat(NewSessionDialog.abbreviate(Path.of("/Users/me"), home)).isEqualTo("~");
        assertThat(NewSessionDialog.abbreviate(Path.of("/srv/api"), home)).isEqualTo("/srv/api");
        assertThat(NewSessionDialog.abbreviate(Path.of("/srv/api"), Path.of(""))).isEqualTo("/srv/api");
    }

    /**
     * The new-sandbox wizard's one forward button: it runs the step while the step has work left, and only moves
     * on once that work is done — a step whose work failed is a dead end unless the wizard may be left without it.
     */
    @Test
    void theWizardRunsAStepBeforeItLetsTheUserPastIt() {
        WizardFlow flow = new WizardFlow(List.of(new WizardFlow.Step("folder", false), new WizardFlow.Step("build", false),
                new WizardFlow.Step("agent", true), new WizardFlow.Step("done", false)));
        assertThat(flow.canBack()).isFalse();
        assertThat(flow.forward()).isEqualTo(WizardFlow.Forward.RUN);
        assertThat(flow.forwardKey("gui.wizard.folder.action")).isEqualTo("gui.wizard.folder.action");
        // a step cannot be run before its own input is there, and cannot be left while it runs
        assertThat(flow.canForward(false)).isFalse();
        assertThat(flow.canForward(true)).isTrue();
        flow.setState(WizardFlow.State.RUNNING);
        assertThat(flow.canForward(true)).isFalse();
        assertThat(flow.canBack()).isFalse();

        flow.setState(WizardFlow.State.DONE);
        assertThat(flow.forward()).isEqualTo(WizardFlow.Forward.ADVANCE);
        assertThat(flow.forwardKey("gui.wizard.folder.action")).isEqualTo("gui.wizard.next");
        flow.advance();
        assertThat(flow.current().key()).isEqualTo("build");
        assertThat(flow.canBack()).isTrue();

        // a required step that failed offers another go and nothing else; an optional one may be passed by
        flow.setState(WizardFlow.State.FAILED);
        assertThat(flow.forward()).isEqualTo(WizardFlow.Forward.RUN);
        assertThat(flow.forwardKey("gui.wizard.build.action")).isEqualTo("gui.wizard.retry");
        flow.setState(WizardFlow.State.DONE);
        flow.advance();
        flow.setState(WizardFlow.State.FAILED);
        assertThat(flow.forward()).isEqualTo(WizardFlow.Forward.ADVANCE);
        assertThat(flow.canForward(false)).isTrue();

        // the last step finishes instead of going anywhere, and going back does not run a finished step again
        flow.advance();
        assertThat(flow.last()).isTrue();
        flow.setState(WizardFlow.State.DONE);
        assertThat(flow.forwardKey(null)).isEqualTo("gui.wizard.finish");
        flow.advance();
        assertThat(flow.index()).isEqualTo(3);
        flow.back();
        flow.back();
        assertThat(flow.current().key()).isEqualTo("build");
        assertThat(flow.forward()).isEqualTo(WizardFlow.Forward.ADVANCE);
    }

    /** The rail marks what each step came to: a number while it is ahead, a tick behind it, «!» where it failed. */
    @Test
    void theWizardRailMarksEveryStepWithWhatBecameOfIt() {
        WizardFlow flow = new WizardFlow(List.of(new WizardFlow.Step("folder", false), new WizardFlow.Step("check", true),
                new WizardFlow.Step("done", false)));
        assertThat(flow.marker(0)).isEqualTo("1");
        flow.setState(WizardFlow.State.RUNNING);
        assertThat(flow.marker(0)).isEqualTo("1");
        flow.setState(WizardFlow.State.DONE);
        flow.advance();
        flow.setState(WizardFlow.State.FAILED);
        assertThat(flow.marker(0)).isEqualTo("✓");
        assertThat(flow.marker(1)).isEqualTo("!");
        assertThat(flow.marker(2)).isEqualTo("3");
    }
}
