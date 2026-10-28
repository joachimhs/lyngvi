package no.syntakserror.lyngvi.gui.viewmodel;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unsent composer text and attachments, kept per conversation while the user switches between them. */
public final class Drafts {

    public record Draft(String text, List<Path> attachments) {
        static final Draft EMPTY = new Draft("", List.of());

        public boolean isEmpty() {
            return text.isEmpty() && attachments.isEmpty();
        }
    }

    private final Map<String, Draft> drafts = new HashMap<>();

    /** Remembers the draft; an empty one is forgotten. */
    public void save(String sessionId, String text, List<Path> attachments) {
        Draft d = new Draft(text == null ? "" : text, List.copyOf(attachments));
        if (d.isEmpty()) drafts.remove(sessionId); else drafts.put(sessionId, d);
    }

    /** The draft for a conversation, empty when there is none. Does not consume it. */
    public Draft restore(String sessionId) {
        return drafts.getOrDefault(sessionId, Draft.EMPTY);
    }

    public void clear(String sessionId) {
        drafts.remove(sessionId);
    }

    /** Forgets every draft; the window has changed sandbox, and none of the conversations they belong to is in it. */
    public void clearAll() {
        drafts.clear();
    }
}
