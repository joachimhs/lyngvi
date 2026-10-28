package no.syntakserror.lyngvi.gui.view;

import java.util.List;
import no.syntakserror.lyngvi.core.session.Conversation;

/**
 * What a bubble says, as text for the clipboard: the author line, the message as written (markdown kept), what the
 * agent said and did in timeline order with each tool call as its one-line title, then attachments and the stop
 * notice. Reasoning and usage are left out; they are not part of the conversation.
 */
final class TranscriptText {

    private TranscriptText() {}

    /** Renders one message; {@code text} is ignored when the timeline carries the text itself, like the bubble does. */
    static String message(String header, String text, List<String> attachments, List<Conversation.WorkItem> work, String trailer) {
        StringBuilder sb = new StringBuilder();
        if (header != null && !header.isBlank()) sb.append(header.strip()).append('\n');
        boolean inline = work != null && work.stream().anyMatch(w -> "text".equals(w.type()));
        if (!inline) line(sb, text);
        if (work != null) {
            for (Conversation.WorkItem w : work) {
                if ("text".equals(w.type())) line(sb, w.output());
                else line(sb, "→ " + ToolSummary.title(w.name(), w.input()) + (w.isError() ? "  ✗" : ""));
            }
        }
        if (attachments != null && !attachments.isEmpty()) line(sb, "📎 " + String.join(", ", attachments));
        line(sb, trailer);
        return sb.toString().stripTrailing();
    }

    /** The selected messages, one after the other, a blank line between them. */
    static String join(List<String> messages) {
        return String.join("\n\n", messages);
    }

    private static void line(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) sb.append(s.strip()).append('\n');
    }
}
