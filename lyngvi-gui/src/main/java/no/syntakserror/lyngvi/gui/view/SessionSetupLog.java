package no.syntakserror.lyngvi.gui.view;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.session.Conversation;

/**
 * The line or two at the top of a conversation that say what creating it did: when the session was made, which
 * agent answers in it, and which working copy each repository got — the branch and commit it was taken from, how
 * much history came with it, and whether the host's uncommitted changes are in it.
 *
 * <p>Making a conversation is the one step in Lyngvi that takes real time and happens out of sight: a clone and a
 * copy per repository, in a container, before the first prompt can be written. What came out of it decides what the
 * agent sees and what a delivery is measured from, and it was only in the sandbox log. These lines put it where the
 * work is.
 *
 * <p>Read from the conversation itself ({@link Conversation#base()}), so they are the same every time it is opened
 * rather than only just after it was created. Pure, so the wording is unit-testable without a toolkit.
 */
final class SessionSetupLog {

    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** How much of a commit sha is worth showing: enough to find it again, short enough to read. */
    private static final int SHA = 7;

    private SessionSetupLog() {}

    /** The lines, in reading order; the first says when and who, the second what is in {@code /work}. */
    static List<String> lines(Conversation c, Messages m, ZoneId zone) {
        List<String> lines = new ArrayList<>();
        lines.add(m.get("gui.chat.setup.created", CREATED.format(Instant.ofEpochMilli(c.createdAt()).atZone(zone)), c.agent()));
        List<String> copies = new ArrayList<>();
        for (String repo : c.repos()) {
            Conversation.RepoBase base = c.base() == null ? null : c.base().get(repo);
            copies.add(base == null ? repo : repo + " (" + describe(base, m) + ")");
        }
        lines.add(copies.isEmpty() ? m.get("gui.chat.setup.norepos") : m.get("gui.chat.setup.repos", String.join(" · ", copies)));
        return List.copyOf(lines);
    }

    /** Where one working copy came from: {@code main @ 1a2b3c4}, then the history it got and the host's own changes. */
    private static String describe(Conversation.RepoBase base, Messages m) {
        StringBuilder sb = new StringBuilder();
        if (base.hostBranch() != null && !base.hostBranch().isBlank()) sb.append(base.hostBranch()).append(' ');
        sb.append('@').append(' ').append(shortSha(base.hostSha()));
        sb.append(", ").append(history(base, m));
        if (base.hostDirty()) sb.append(", ").append(m.get("gui.chat.setup.dirty"));
        return sb.toString();
    }

    private static String history(Conversation.RepoBase base, Messages m) {
        History h = base.history() == null ? History.NONE : base.history();
        return switch (h) {
            case NONE -> m.get("gui.chat.setup.history.none");
            case SHALLOW -> base.depth() == null ? m.get("gui.chat.setup.history.shallow")
                    : m.get("gui.chat.setup.history.shallow.depth", base.depth());
            case FULL -> m.get("gui.chat.setup.history.full");
        };
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) return "?";
        return sha.length() > SHA ? sha.substring(0, SHA) : sha;
    }
}
