package no.syntakserror.lyngvi.gui.view;

import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.contract.JsonOutput;

/** A one-line title for a tool call: the tool name and the field that matters (command, path, pattern, ...), never truncated. */
public final class ToolSummary {

    private static final List<String> PREFERRED = List.of("command", "file_path", "pattern", "path", "query", "url", "skill", "args", "prompt", "description");

    private ToolSummary() {}

    public static String title(String name, String input) {
        String n = name == null ? "" : name;
        if (input == null || input.isBlank()) return n;
        String s = input.strip();
        if (s.startsWith("{")) {
            try {
                Map<String, Object> obj = JsonOutput.readObject(s, "tool input");
                StringBuilder sb = new StringBuilder();
                for (String k : PREFERRED) {
                    if (k.equals("description") && !sb.isEmpty()) continue;
                    Object v = obj.get(k);
                    if (v != null && !String.valueOf(v).isBlank()) {
                        if (!sb.isEmpty()) sb.append("  ");
                        sb.append(oneLine(String.valueOf(v)));
                        if (k.equals("command")) break;
                    }
                }
                if (sb.isEmpty()) {
                    obj.forEach((k, v) -> { if (!sb.isEmpty()) sb.append(", "); sb.append(k).append(": ").append(oneLine(String.valueOf(v))); });
                }
                return sb.isEmpty() ? n : n + "  " + sb;
            } catch (RuntimeException e) {
                return n + "  " + oneLine(s);
            }
        }
        return n + "  " + oneLine(s);
    }

    private static String oneLine(String s) {
        return s.replace("\r", "").replace('\n', ' ').strip();
    }
}
