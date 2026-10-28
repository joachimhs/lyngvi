package no.syntakserror.lyngvi.gui.viewmodel;

import java.util.List;

/**
 * The repository tags a conversation is labelled with in the list.
 *
 * <p>A sandbox holds every repository the user works in, and a conversation works in the ones it was opened with.
 * The title alone does not say which: «fix the spacing» is a different job in each of them, and finding out means
 * opening the conversation. The names are the user's own and are short, so they are shown as they are.
 *
 * <p>A 200 px sidebar fits about two of them side by side. A conversation that spans more is better told as a count
 * than as a row that wraps or is cut mid-name, so the tail becomes «+n» and the whole list is in the tooltip.
 *
 * <p>Pure, so the rule is unit-testable without a toolkit.
 */
public final class ConversationRepos {

    private ConversationRepos() {}

    /** Names shown side by side before the rest is counted. */
    public static final int MAX_TAGS = 2;

    /**
     * @param shown the names to label the conversation with, in the order the conversation has them
     * @param more  how many repositories are left out; 0 when every one is shown
     */
    public record Tags(List<String> shown, int more) {

        public Tags {
            shown = List.copyOf(shown);
        }

        /** True for a conversation with no repositories — «Ask the agent», say — which gets no row at all. */
        public boolean isEmpty() {
            return shown.isEmpty();
        }
    }

    public static Tags of(List<String> repos) {
        if (repos == null) return new Tags(List.of(), 0);
        List<String> named = repos.stream().filter(r -> r != null && !r.isBlank()).map(String::strip).toList();
        int shown = Math.min(MAX_TAGS, named.size());
        return new Tags(named.subList(0, shown), named.size() - shown);
    }
}
