package no.syntakserror.lyngvi.core.ask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.session.Conversation;

/**
 * The ordered timeline of one turn: text segments and tool calls as they arrived. Persisted as the message's
 * {@code work} list ({@code type} {@code text} or {@code tool}); {@link #text()} is the readable concatenation
 * with a blank line between segments that a tool call separated.
 */
public final class WorkTimeline {

    private final List<Conversation.WorkItem> items = new ArrayList<>();
    private final Map<String, Integer> toolIndex = new HashMap<>();
    private boolean lastWasText;

    public synchronized void accept(AgentEvent e) {
        switch (e) {
            case AgentEvent.Text t -> {
                if (lastWasText && !items.isEmpty()) {
                    Conversation.WorkItem last = items.getLast();
                    items.set(items.size() - 1, new Conversation.WorkItem("text", null, null, last.output() + t.delta(), false));
                    lastWasText = true;
                } else if (t.delta() != null && !t.delta().isBlank()) {
                    // Blank text between two tool calls (an agent that opened a text block and wrote nothing in it)
                    // starts no segment: an empty segment is an empty section in the transcript.
                    items.add(new Conversation.WorkItem("text", null, null, t.delta(), false));
                    lastWasText = true;
                }
            }
            case AgentEvent.ToolStart t -> {
                toolIndex.put(t.id(), items.size());
                items.add(new Conversation.WorkItem("tool", t.name(), t.input(), null, false));
                lastWasText = false;
            }
            case AgentEvent.ToolEnd t -> {
                Integer idx = toolIndex.get(t.id());
                if (idx == null) {
                    toolIndex.put(t.id(), items.size());
                    items.add(new Conversation.WorkItem("tool", t.id(), null, t.output(), t.isError()));
                } else {
                    Conversation.WorkItem started = items.get(idx);
                    items.set(idx, new Conversation.WorkItem("tool", started.name(), started.input(), t.output(), t.isError()));
                }
                lastWasText = false;
            }
            default -> { }
        }
    }

    public synchronized List<Conversation.WorkItem> items() {
        return List.copyOf(items);
    }

    /** All text segments joined; a blank line separates segments split by tool calls. */
    public synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (Conversation.WorkItem i : items) {
            if (!"text".equals(i.type())) continue;
            if (!sb.isEmpty()) sb.append("\n\n").append(i.output().stripLeading());
            else sb.append(i.output());
        }
        return sb.toString();
    }

    public synchronized int toolCount() {
        return (int) items.stream().filter(i -> "tool".equals(i.type())).count();
    }
}
