package no.syntakserror.lyngvi.gui.viewmodel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import no.syntakserror.lyngvi.core.contract.SessionInfo;

/**
 * The conversation list as rows: group headers, conversations, and one «show all» row for what is left out.
 *
 * <p>A sandbox collects conversations and never loses any, so a flat list becomes unusable long before it becomes
 * wrong: twenty conversations are sixty lines of grey text. Grouping by state answers the question the list is for
 * — what is running, what needs me, what did I do lately — and the quiet groups are capped so the list stays short.
 *
 * <p>Pure: the caller passes what it knows about running and unread state plus the current time, so this is
 * unit-testable without a toolkit or a clock.
 */
public final class ConversationGroups {

    private ConversationGroups() {}

    /** How far back «recent» reaches; older conversations are only shown when the user asks for all of them. */
    public static final long RECENT_WINDOW_MILLIS = Duration.ofDays(7).toMillis();

    /** Rows per quiet group before the rest is folded into «show all»; running and waiting are never capped. */
    public static final int MAX_PER_GROUP = 5;

    /** The order here is the order in the list: what moves, then what waits, then what is done. */
    public enum Group {
        RUNNING("gui.chat.group.running"),
        WAITING("gui.chat.group.waiting"),
        RECENT("gui.chat.group.recent"),
        OLDER("gui.chat.group.older");

        private final String key;

        Group(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** Running and waiting are actionable, so they are shown in full however many there are. */
        public boolean capped() {
            return this == RECENT || this == OLDER;
        }
    }

    public sealed interface Row permits Header, Item, More {}

    public record Header(Group group, int count) implements Row {}

    public record Item(SessionInfo info, Group group) implements Row {}

    /** The tail that was left out; selecting it shows every conversation. */
    public record More(int hidden) implements Row {}

    /**
     * @param running        whether a turn is running in the conversation with this id
     * @param needsAttention whether the conversation finished or failed without the user having seen it
     */
    public static Group groupOf(SessionInfo info, Predicate<String> running, Predicate<String> needsAttention, long now) {
        if (info.busy() || running.test(info.id())) return Group.RUNNING;
        if (needsAttention.test(info.id())) return Group.WAITING;
        return now - info.lastActiveAt() <= RECENT_WINDOW_MILLIS ? Group.RECENT : Group.OLDER;
    }

    /**
     * Builds the rows. With a filter, every match is shown and nothing is capped — a search that hides matches is
     * worse than a long list.
     */
    public static List<Row> rows(List<SessionInfo> sessions, Predicate<String> running, Predicate<String> needsAttention,
                                 long now, String filter, boolean showAll) {
        List<SessionInfo> matching = sessions.stream().filter(i -> matches(i, filter)).toList();
        boolean capped = !showAll && (filter == null || filter.isBlank());
        List<Row> rows = new ArrayList<>();
        int hidden = 0;
        for (Group g : Group.values()) {
            List<SessionInfo> inGroup = matching.stream()
                    .filter(i -> groupOf(i, running, needsAttention, now) == g)
                    .sorted(Comparator.comparingLong(SessionInfo::lastActiveAt).reversed())
                    .toList();
            if (inGroup.isEmpty()) continue;
            int shown = capped && g.capped() ? Math.min(MAX_PER_GROUP, inGroup.size()) : inGroup.size();
            hidden += inGroup.size() - shown;
            rows.add(new Header(g, inGroup.size()));
            for (SessionInfo i : inGroup.subList(0, shown)) rows.add(new Item(i, g));
        }
        if (hidden > 0) rows.add(new More(hidden));
        return rows;
    }

    /** Title, agent and repository names — everything the list itself knows about a conversation. */
    static boolean matches(SessionInfo info, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String needle = filter.strip().toLowerCase(Locale.ROOT);
        if (info.title().toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (info.agent().toLowerCase(Locale.ROOT).contains(needle)) return true;
        return info.repos().stream().anyMatch(r -> r.toLowerCase(Locale.ROOT).contains(needle));
    }

    /** The conversations in the rows, in the order they are shown. */
    public static List<SessionInfo> items(List<Row> rows) {
        return rows.stream().filter(Item.class::isInstance).map(r -> ((Item) r).info()).toList();
    }
}
