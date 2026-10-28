package no.syntakserror.lyngvi.gui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import no.syntakserror.lyngvi.core.contract.SessionInfo;
import org.junit.jupiter.api.Test;

/** The grouping rules and the cap that keeps a long conversation list short. */
class ConversationGroupsTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Predicate<String> NONE = id -> false;

    private static SessionInfo session(String id, long lastActiveAt) {
        return session(id, lastActiveAt, false);
    }

    private static SessionInfo session(String id, long lastActiveAt, boolean busy) {
        return new SessionInfo(id, "Title " + id, "claude-code", List.of("repo"), lastActiveAt - 1000, lastActiveAt,
                0, 2, null, null, busy);
    }

    private static long agoDays(long days) {
        return NOW - Duration.ofDays(days).toMillis();
    }

    @Test
    void busyOrRunningComesFirstThenWhatNeedsAttention() {
        SessionInfo busy = session("busy", agoDays(30), true);
        SessionInfo running = session("running", agoDays(30));
        SessionInfo unread = session("unread", agoDays(30));
        SessionInfo old = session("old", agoDays(30));
        assertThat(ConversationGroups.groupOf(busy, NONE, NONE, NOW)).isEqualTo(ConversationGroups.Group.RUNNING);
        assertThat(ConversationGroups.groupOf(running, id -> id.equals("running"), NONE, NOW)).isEqualTo(ConversationGroups.Group.RUNNING);
        assertThat(ConversationGroups.groupOf(unread, NONE, id -> id.equals("unread"), NOW)).isEqualTo(ConversationGroups.Group.WAITING);
        assertThat(ConversationGroups.groupOf(old, NONE, NONE, NOW)).isEqualTo(ConversationGroups.Group.OLDER);
    }

    @Test
    void theRecentWindowIsSevenDays() {
        assertThat(ConversationGroups.groupOf(session("a", agoDays(6)), NONE, NONE, NOW)).isEqualTo(ConversationGroups.Group.RECENT);
        assertThat(ConversationGroups.groupOf(session("b", agoDays(8)), NONE, NONE, NOW)).isEqualTo(ConversationGroups.Group.OLDER);
    }

    @Test
    void quietGroupsAreCappedAndTheRestIsOneShowAllRow() {
        List<SessionInfo> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(session("s" + i, agoDays(1) - i));
        List<ConversationGroups.Row> rows = ConversationGroups.rows(many, NONE, NONE, NOW, "", false);

        assertThat(ConversationGroups.items(rows)).hasSize(ConversationGroups.MAX_PER_GROUP);
        assertThat(rows.getFirst()).isEqualTo(new ConversationGroups.Header(ConversationGroups.Group.RECENT, 12));
        assertThat(rows.getLast()).isEqualTo(new ConversationGroups.More(7));
    }

    @Test
    void runningAndWaitingAreNeverCapped() {
        List<SessionInfo> many = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) many.add(session("r" + i, agoDays(1) - i, true));
        List<ConversationGroups.Row> rows = ConversationGroups.rows(many, NONE, NONE, NOW, "", false);

        assertThat(ConversationGroups.items(rows)).hasSize(9);
        assertThat(rows).noneMatch(ConversationGroups.More.class::isInstance);
    }

    @Test
    void showAllDropsTheCap() {
        List<SessionInfo> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(session("s" + i, agoDays(1) - i));
        assertThat(ConversationGroups.items(ConversationGroups.rows(many, NONE, NONE, NOW, "", true))).hasSize(12);
    }

    @Test
    void groupsAppearInOrderAndConversationsNewestFirst() {
        List<SessionInfo> sessions = List.of(session("old", agoDays(30)), session("recent", agoDays(1)),
                session("newer", agoDays(0)), session("busy", agoDays(20), true));
        List<ConversationGroups.Row> rows = ConversationGroups.rows(sessions, NONE, NONE, NOW, "", false);

        assertThat(rows.stream().filter(ConversationGroups.Header.class::isInstance)
                .map(r -> ((ConversationGroups.Header) r).group()))
                .containsExactly(ConversationGroups.Group.RUNNING, ConversationGroups.Group.RECENT, ConversationGroups.Group.OLDER);
        assertThat(ConversationGroups.items(rows).stream().map(SessionInfo::id))
                .containsExactly("busy", "newer", "recent", "old");
    }

    @Test
    void filteringMatchesTitleAgentAndRepoAndShowsEveryMatch() {
        List<SessionInfo> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(session("s" + i, agoDays(1) - i));
        List<ConversationGroups.Row> rows = ConversationGroups.rows(many, NONE, NONE, NOW, "title s", false);
        assertThat(ConversationGroups.items(rows)).hasSize(12);
        assertThat(rows).noneMatch(ConversationGroups.More.class::isInstance);

        assertThat(ConversationGroups.matches(session("x", NOW), "CLAUDE")).isTrue();
        assertThat(ConversationGroups.matches(session("x", NOW), "repo")).isTrue();
        assertThat(ConversationGroups.matches(session("x", NOW), "nothing")).isFalse();
        assertThat(ConversationGroups.matches(session("x", NOW), "  ")).isTrue();
    }

    @Test
    void everyGroupHasABundleKey() {
        assertThat(Set.of(ConversationGroups.Group.values()).size()).isEqualTo(4);
        for (ConversationGroups.Group g : ConversationGroups.Group.values()) {
            assertThat(g.key()).startsWith("gui.chat.group.");
        }
    }
}
