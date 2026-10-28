package no.syntakserror.lyngvi.gui.viewmodel;

import java.util.regex.Pattern;

/**
 * Titles for conversations the user did not name.
 *
 * <p>The core falls back to {@code Session yyyy-MM-dd HH:mm} when no title is given, which tells the list nothing:
 * eight such rows under each other are eight timestamps. The first thing the user writes says what the conversation
 * is about, so it becomes the title — until the user renames it, which still wins.
 */
public final class SessionTitles {

    private SessionTitles() {}

    /** What {@code SessionService} generates when {@code NewSessionOptions.title()} is empty. */
    private static final Pattern GENERATED = Pattern.compile("Session \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");

    /** Long enough to be recognisable in a 200 px sidebar, short enough not to wrap. */
    static final int MAX = 48;

    public static boolean isGenerated(String title) {
        return title != null && GENERATED.matcher(title).matches();
    }

    /**
     * The first line of the prompt, whitespace collapsed, cut on a word boundary. Empty when the prompt carries no
     * words — a bare code block, say — in which case the generated title is kept.
     */
    public static String fromPrompt(String prompt) {
        if (prompt == null) return "";
        String line = prompt.strip().lines().map(String::strip).filter(s -> !s.isBlank()).findFirst().orElse("");
        line = line.replaceAll("\\s+", " ").strip();
        if (line.length() <= MAX) return line;
        String cut = line.substring(0, MAX);
        int space = cut.lastIndexOf(' ');
        // a single word longer than the limit has no boundary to cut on, so it is cut where it is
        return (space > MAX / 2 ? cut.substring(0, space) : cut).strip() + "…";
    }
}
