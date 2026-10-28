package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import no.syntakserror.lyngvi.core.contract.JsonOutput;

/**
 * What a tool call means, read out of its raw JSON input: which kind of tool it is (a file edit, a read, a shell
 * command, ...) and the parts worth showing instead of the JSON itself. An edit becomes a line diff of its old and
 * new text, a written file becomes added lines, a shell command a prompt line, and everything else key/value fields.
 * Pure: no toolkit, so it is unit-tested; {@link WorkLogPane} renders the parts.
 */
public final class ToolCall {

    /** The kind of tool, shown as a badge so edits stand apart from lookups and commands. */
    public enum Category {
        EDIT, READ, SEARCH, RUN, WEB, AGENT, OTHER;

        /** The suffix of the bundle key and the style class: {@code gui.chat.tool.edit}, {@code tool-edit}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public sealed interface Part permits Command, Field, Text, Changes, Patch {}

    /** A shell command line. */
    public record Command(String text) implements Part {}

    /** A short single-line value. */
    public record Field(String key, String value) implements Part {}

    /** A long or multi-line value (a prompt, a JSON object). */
    public record Text(String key, String text) implements Part {}

    /** A file change as +/− lines: an edit's old and new text, or a written file's content. */
    public record Changes(String path, List<DiffPaneBuilder.Line> lines, boolean replaceAll) implements Part {
        public int additions() {
            return (int) lines.stream().filter(l -> l.kind() == DiffPaneBuilder.Kind.ADDED).count();
        }

        public int deletions() {
            return (int) lines.stream().filter(l -> l.kind() == DiffPaneBuilder.Kind.REMOVED).count();
        }
    }

    /** A unified diff given as input (an apply_patch call). */
    public record Patch(String text) implements Part {}

    /** Lines added and removed by a call, summed over its edits; shown in the pane header. */
    public record Counts(int additions, int deletions) {}

    /** The change counts of an edit or patch call; empty for calls that change nothing. */
    public static Optional<Counts> changeCounts(String name, String input) {
        int add = 0, del = 0;
        boolean any = false;
        for (Part part : parts(name, input)) {
            switch (part) {
                case Changes c -> { any = true; add += c.additions(); del += c.deletions(); }
                case Patch d -> {
                    any = true;
                    for (String line : d.text().split("\n")) {
                        if (line.startsWith("+++") || line.startsWith("---")) continue;
                        if (line.startsWith("+")) add++;
                        else if (line.startsWith("-")) del++;
                    }
                }
                default -> {}
            }
        }
        return any ? Optional.of(new Counts(add, del)) : Optional.empty();
    }

    private static final Set<String> EDIT = Set.of("edit", "multiedit", "write", "notebookedit", "apply_patch", "patch", "str_replace_editor",
            "str_replace_based_edit_tool", "write_file", "edit_file", "create_file", "replace", "insert");
    private static final Set<String> READ = Set.of("read", "read_file", "cat", "view", "notebookread", "readfile", "list", "ls", "list_dir",
            "list_directory");
    private static final Set<String> SEARCH = Set.of("grep", "glob", "find", "search", "ripgrep", "codebase_search", "file_search", "rg");
    private static final Set<String> RUN = Set.of("bash", "shell", "sh", "exec", "execute", "command", "run", "terminal", "run_command", "powershell");
    private static final Set<String> WEB = Set.of("webfetch", "websearch", "web_fetch", "web_search", "fetch", "http", "browser", "curl");
    private static final Set<String> AGENT = Set.of("agent", "task", "skill", "subagent", "spawn", "delegate", "workflow");
    /** Bookkeeping tools whose names would otherwise match an edit or a lookup. */
    private static final Set<String> OTHER = Set.of("todowrite", "todoread", "askuserquestion", "exitplanmode", "enterplanmode", "toolsearch");

    private static final int FIELD_MAX = 120;

    private ToolCall() {}

    public static Category categoryOf(String name) {
        if (name == null) return Category.OTHER;
        String n = name.toLowerCase(Locale.ROOT);
        if (OTHER.contains(n)) return Category.OTHER;
        if (EDIT.contains(n)) return Category.EDIT;
        if (READ.contains(n)) return Category.READ;
        if (SEARCH.contains(n)) return Category.SEARCH;
        if (RUN.contains(n)) return Category.RUN;
        if (WEB.contains(n)) return Category.WEB;
        if (AGENT.contains(n)) return Category.AGENT;
        if (n.startsWith("mcp__")) return Category.OTHER;
        if (n.contains("edit") || n.contains("write") || n.contains("patch")) return Category.EDIT;
        if (n.contains("read") || n.contains("view")) return Category.READ;
        if (n.contains("search") || n.contains("grep") || n.contains("glob") || n.contains("find")) return Category.SEARCH;
        if (n.contains("bash") || n.contains("shell") || n.contains("exec") || n.contains("command")) return Category.RUN;
        if (n.contains("web") || n.contains("fetch") || n.contains("http")) return Category.WEB;
        if (n.contains("agent") || n.contains("task")) return Category.AGENT;
        return Category.OTHER;
    }

    /** The parts to show for this call's input; empty when there is no input at all. */
    public static List<Part> parts(String name, String input) {
        if (input == null || input.isBlank()) return List.of();
        String s = input.strip();
        Map<String, Object> obj = null;
        if (s.startsWith("{")) {
            try {
                obj = JsonOutput.readObject(s, "tool input");
            } catch (RuntimeException e) {
                obj = null;
            }
        }
        if (obj == null) {
            return List.of(DiffPaneBuilder.looksLikeDiff(s) ? new Patch(s) : categoryOf(name) == Category.RUN ? new Command(s) : new Text(null, s));
        }
        List<Part> out = new ArrayList<>();
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String path = str(obj, "file_path", "filePath", "notebook_path", "path");
        Set<String> used = new HashSet<>();
        if (obj.get("edits") instanceof List<?> edits && path != null) {
            for (Object e : edits) {
                if (e instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked") Map<String, Object> em = (Map<String, Object>) m;
                    out.add(new Changes(path, LineDiff.of(str(em, "old_string", "oldString"), str(em, "new_string", "newString")), bool(em, "replace_all", "replaceAll")));
                }
            }
            used.addAll(List.of("edits", "file_path", "filePath", "path"));
        } else if (path != null && (has(obj, "old_string", "oldString") || has(obj, "new_string", "newString"))) {
            out.add(new Changes(path, LineDiff.of(str(obj, "old_string", "oldString"), str(obj, "new_string", "newString")), bool(obj, "replace_all", "replaceAll")));
            used.addAll(List.of("file_path", "filePath", "path", "old_string", "oldString", "new_string", "newString", "replace_all", "replaceAll"));
        } else if (path != null && has(obj, "content") && categoryOf(name) == Category.EDIT) {
            out.add(new Changes(path, LineDiff.added(str(obj, "content")), false));
            used.addAll(List.of("file_path", "filePath", "path", "content"));
        } else if (path != null && has(obj, "new_source")) {
            out.add(new Changes(path, LineDiff.added(str(obj, "new_source")), false));
            used.addAll(List.of("notebook_path", "path", "new_source"));
        } else if (categoryOf(name) == Category.RUN && has(obj, "command", "cmd")) {
            String cmd = str(obj, "command", "cmd");
            out.add(new Command(cmd));
            used.addAll(List.of("command", "cmd"));
        } else if (n.contains("patch") && (has(obj, "patch") || has(obj, "input"))) {
            String patch = str(obj, "patch", "input");
            out.add(DiffPaneBuilder.looksLikeDiff(patch) ? new Patch(patch) : new Text("patch", patch));
            used.addAll(List.of("patch", "input"));
        }
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            if (used.contains(e.getKey()) || e.getValue() == null) continue;
            String v = render(e.getValue());
            if (v.isBlank()) continue;
            if (v.contains("\n") || v.length() > FIELD_MAX) out.add(new Text(e.getKey(), v));
            else out.add(new Field(e.getKey(), v));
        }
        return out;
    }

    /** A plain-text rendering of the whole call, for copying: the parts as they are shown. */
    public static String copyText(List<Part> parts) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (!sb.isEmpty()) sb.append('\n');
            switch (p) {
                case Command c -> sb.append("$ ").append(c.text());
                case Field f -> sb.append(f.key()).append(": ").append(f.value());
                case Text t -> sb.append(t.key() == null ? "" : t.key() + ":\n").append(t.text());
                case Changes c -> sb.append(c.path()).append('\n').append(LineDiff.text(c.lines()));
                case Patch d -> sb.append(d.text());
            }
        }
        return sb.toString();
    }

    private static boolean has(Map<String, Object> obj, String... keys) {
        for (String k : keys) if (obj.get(k) != null) return true;
        return false;
    }

    private static String str(Map<String, Object> obj, String... keys) {
        for (String k : keys) {
            Object v = obj.get(k);
            if (v != null) return String.valueOf(v);
        }
        return null;
    }

    private static boolean bool(Map<String, Object> obj, String... keys) {
        for (String k : keys) {
            Object v = obj.get(k);
            if (v != null) return Boolean.parseBoolean(String.valueOf(v));
        }
        return false;
    }

    /** Scalars as-is; objects and lists as JSON so a nested argument stays readable. */
    static String render(Object v) {
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            try {
                return JsonOutput.line(v);
            } catch (RuntimeException e) {
                return String.valueOf(v);
            }
        }
        return String.valueOf(v);
    }
}
