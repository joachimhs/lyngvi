package no.syntakserror.lyngvi.core.delivery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.contract.FileChange;

/**
 * Sorts the files of a unified diff into the three buckets of spec §5.6 by reading the
 * headers git writes: file modes, renames, binary markers and paths. No git is needed.
 */
public final class PatchClassifier {

    private PatchClassifier() {}

    /** The patch split per file, keyed by the file's (new) path, in patch order. */
    public static Map<String, String> split(String patch) {
        Map<String, String> out = new LinkedHashMap<>();
        if (patch == null || patch.isBlank()) return out;
        StringBuilder current = null;
        String key = null;
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                if (current != null) out.put(key, current.toString());
                current = new StringBuilder();
                key = null;
            }
            if (current == null) continue;
            current.append(line).append('\n');
            if (key == null && line.startsWith("diff --git ")) key = pathFromHeader(line);
        }
        if (current != null) out.put(key, current.toString());
        // Trim the trailing newline that split(-1) adds after the last line.
        out.replaceAll((k, v) -> v.endsWith("\n\n") ? v.substring(0, v.length() - 1) : v);
        return refineKeys(out);
    }

    /** Prefer the paths from {@code rename to} / {@code +++ b/} / {@code --- a/} over the header split. */
    private static Map<String, String> refineKeys(Map<String, String> blocks) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            out.put(newPath(e.getValue(), e.getKey()), e.getValue());
        }
        return out;
    }

    static String pathFromHeader(String header) {
        int b = header.indexOf(" b/");
        return b < 0 ? header.substring("diff --git ".length()) : header.substring(b + 3);
    }

    static String newPath(String block, String fallback) {
        for (String line : block.split("\n")) {
            if (line.startsWith("rename to ")) return line.substring("rename to ".length());
            if (line.startsWith("+++ b/")) return line.substring("+++ b/".length());
            if (line.startsWith("+++ /dev/null")) {
                for (String l : block.split("\n")) {
                    if (l.startsWith("--- a/")) return l.substring("--- a/".length());
                }
            }
        }
        return fallback;
    }

    public static List<FileChange> classify(String patch, long maxBytes) {
        List<FileChange> out = new ArrayList<>();
        for (Map.Entry<String, String> e : split(patch).entrySet()) {
            out.add(classifyBlock(e.getKey(), e.getValue(), maxBytes));
        }
        return out;
    }

    static FileChange classifyBlock(String path, String block, long maxBytes) {
        String change = "modified";
        String renamedFrom = null;
        String mode = null;
        boolean binary = false;
        boolean symlink = false;
        boolean executable = false;
        int additions = 0;
        int deletions = 0;
        boolean inHunk = false;
        for (String line : block.split("\n")) {
            if (line.startsWith("@@")) { inHunk = true; continue; }
            if (inHunk) {
                if (line.startsWith("+") && !line.startsWith("+++")) additions++;
                else if (line.startsWith("-") && !line.startsWith("---")) deletions++;
                continue;
            }
            if (line.startsWith("new file mode ")) { change = "added"; mode = line.substring("new file mode ".length()).strip(); }
            else if (line.startsWith("deleted file mode ")) { change = "deleted"; }
            else if (line.startsWith("new mode ")) { mode = line.substring("new mode ".length()).strip(); }
            else if (line.startsWith("rename from ")) { change = "renamed"; renamedFrom = line.substring("rename from ".length()); }
            else if (line.startsWith("GIT binary patch") || line.startsWith("Binary files ")) { binary = true; }
        }
        if (mode != null) {
            if (mode.startsWith("120000")) symlink = true;
            else if (mode.endsWith("755")) executable = true;
        }
        String bucket = "apply";
        String reason = null;
        String rejectReason = rejectReason(path, renamedFrom);
        if (rejectReason != null) {
            bucket = "reject";
            reason = rejectReason;
        } else if (symlink) {
            bucket = "hold"; reason = "SYMLINK";
        } else if (binary) {
            bucket = "hold"; reason = "BINARY";
        } else if (executable) {
            bucket = "hold"; reason = "EXECUTABLE";
        } else if (block.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            bucket = "hold"; reason = "TOO_LARGE";
        }
        return new FileChange(path, renamedFrom, change, bucket, reason, additions, deletions, binary, mode, false);
    }

    /** {@code PATH_TRAVERSAL}, {@code ABSOLUTE_PATH}, {@code GIT_PATH} or null when the path is safe. */
    static String rejectReason(String path, String renamedFrom) {
        for (String p : renamedFrom == null ? List.of(path) : List.of(path, renamedFrom)) {
            if (p == null || p.isBlank()) return "PATH_TRAVERSAL";
            if (p.startsWith("/") || p.startsWith("\\")) return "ABSOLUTE_PATH";
            for (String segment : p.split("[/\\\\]")) {
                if (segment.equals("..")) return "PATH_TRAVERSAL";
                if (segment.equalsIgnoreCase(".git")) return "GIT_PATH";
            }
        }
        return null;
    }
}
