package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.session.Conversation;

/**
 * The work log behind a bubble: one reasoning block (always first, only when non-empty), then notices and tool
 * calls in arrival order. Tool entries are updated in place from the run's complete tool list, so a refresh never
 * moves or duplicates anything; entries carry stable keys so the pane can update instead of rebuild.
 */
public final class WorkLogModel {

    public enum Kind { REASONING, NOTICE, TOOL, TEXT }

    /** {@code key} is stable across updates: {@code reasoning}, {@code notice:<n>}, {@code item:<n>} (timeline position). */
    public record Entry(String key, Kind kind, String text, String level, Conversation.WorkItem tool) {}

    private String reasoning;
    private final List<Entry> notices = new ArrayList<>();
    private final List<Conversation.WorkItem> tools = new ArrayList<>();

    public static WorkLogModel of(List<Conversation.WorkItem> saved) {
        WorkLogModel m = new WorkLogModel();
        if (saved != null) m.work(saved);
        return m;
    }

    /** The full reasoning text so far; blank text (redacted thinking) shows nothing. */
    public void reasoning(String full) {
        reasoning = full == null || full.isBlank() ? null : full;
    }

    public void notice(String level, String text) {
        notices.add(new Entry("notice:" + notices.size(), Kind.NOTICE, text, level, null));
    }

    /** The complete tool list so far; earlier entries are updated in place, new ones appended. */
    public void work(List<Conversation.WorkItem> items) {
        tools.clear();
        tools.addAll(items);
    }

    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        if (reasoning != null) out.add(new Entry("reasoning", Kind.REASONING, reasoning, null, null));
        out.addAll(notices);
        for (int i = 0; i < tools.size(); i++) {
            Conversation.WorkItem w = tools.get(i);
            // A blank text item (recorded before empty text blocks were dropped) would render as an empty section.
            if ("text".equals(w.type())) {
                if (!isBlank(w)) out.add(new Entry("item:" + i, Kind.TEXT, w.output(), null, null));
            } else {
                out.add(new Entry("item:" + i, Kind.TOOL, null, null, w));
            }
        }
        return out;
    }

    /** The timeline so far: text and tool calls in order. */
    public List<Conversation.WorkItem> work() {
        return List.copyOf(tools);
    }

    public int toolCount() {
        return (int) tools.stream().filter(w -> !"text".equals(w.type())).count();
    }

    /** True when the timeline carries the message text itself (then the bubble needs no separate text area). */
    public boolean hasText() {
        return tools.stream().anyMatch(w -> "text".equals(w.type()) && !isBlank(w));
    }

    public boolean hasErrors() {
        return tools.stream().anyMatch(w -> !"text".equals(w.type()) && w.isError()) || notices.stream().anyMatch(n -> "error".equals(n.level()));
    }

    /** Nothing to render: a pane built from this model would be an empty box. */
    public boolean isEmpty() {
        return entries().isEmpty();
    }

    private static boolean isBlank(Conversation.WorkItem w) {
        return w.output() == null || w.output().isBlank();
    }
}
