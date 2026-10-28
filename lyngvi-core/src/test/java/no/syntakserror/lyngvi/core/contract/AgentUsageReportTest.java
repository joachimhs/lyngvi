package no.syntakserror.lyngvi.core.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.ask.AgentUsage;
import no.syntakserror.lyngvi.core.session.Conversation;
import org.junit.jupiter.api.Test;

/** Rolling the conversation files up into the numbers the usage view shows. */
class AgentUsageReportTest {

    private static Conversation conversation(String id, String agent, String model, long lastActiveAt, Conversation.Usage... usages) {
        List<Conversation.Message> messages = new ArrayList<>();
        messages.add(new Conversation.Message("u", "user", 1, "hi", List.of(), List.of(), null, null));
        for (Conversation.Usage u : usages) {
            messages.add(new Conversation.Message("a" + messages.size(), "assistant", 2, "ok", List.of(), List.of(), u, "end_turn"));
        }
        return new Conversation(Conversation.CURRENT_SCHEMA_VERSION, id, "Session " + id, 1, lastActiveAt, lastActiveAt,
                agent, null, model, List.of(), null, "c-" + id, Map.of(), List.of(), messages);
    }

    @Test
    void sumsOnlyTheChosenAgentAndGroupsByModel() {
        List<Conversation> all = List.of(
                conversation("a", "claude-code", "sonnet", 300,
                        new Conversation.Usage(10, 20, 5, 1, 0.10, 30L, 200_000L),
                        new Conversation.Usage(10, 20, 0, 0, 0.20, 60L, 200_000L)),
                conversation("b", "claude-code", "opus", 200, new Conversation.Usage(1, 2, 0, 0, null, null, null)),
                conversation("c", "opencode", "gpt", 400, new Conversation.Usage(999, 999, 0, 0, 9.0, null, null)));

        AgentUsageReport r = AgentUsageReport.of("claude-code", "Claude Code", AgentUsage.unsupported(), all);

        assertThat(r.local().turns()).isEqualTo(3);
        assertThat(r.local().input()).isEqualTo(21);
        assertThat(r.local().output()).isEqualTo(42);
        assertThat(r.local().cacheRead()).isEqualTo(5);
        assertThat(r.local().cacheWrite()).isEqualTo(1);
        assertThat(r.local().costUsd()).isCloseTo(0.30, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(r.local().tokens()).isEqualTo(69);
        assertThat(r.byModel()).extracting(AgentUsageReport.Group::key).containsExactly("sonnet", "opus");
        assertThat(r.byModel().getFirst().totals().turns()).isEqualTo(2);
    }

    @Test
    void sessionsAreNewestFirstAndCarryTheLastContextReading() {
        List<Conversation> all = List.of(
                conversation("old", "mock-sh", "mock-1", 100, new Conversation.Usage(1, 1, 0, 0, null, 10L, 100L)),
                conversation("new", "mock-sh", "mock-1", 900, new Conversation.Usage(1, 1, 0, 0, null, 10L, 100L),
                        new Conversation.Usage(1, 1, 0, 0, null, 80L, 100L)),
                conversation("silent", "mock-sh", "mock-1", 950));

        AgentUsageReport r = AgentUsageReport.of("mock-sh", "Mock", AgentUsage.unsupported(), all);

        assertThat(r.sessions()).extracting(AgentUsageReport.Session::id).containsExactly("new", "old");
        assertThat(r.sessions().getFirst().contextUsed()).isEqualTo(80L);
        assertThat(r.sessions().getFirst().contextMax()).isEqualTo(100L);
    }

    @Test
    void anAgentWithoutConversationsReportsZeroes() {
        AgentUsageReport r = AgentUsageReport.of("nobody", "Nobody", AgentUsage.unsupported(), List.of());

        assertThat(r.local()).isEqualTo(AgentUsageReport.Totals.EMPTY);
        assertThat(r.local().costUsd()).isNull();
        assertThat(r.byModel()).isEmpty();
        assertThat(r.sessions()).isEmpty();
    }

    @Test
    void aConversationsTotalsSumTheMessagesThatCarriedUsage() {
        Conversation c = conversation("x", "claude-code", "sonnet", 300,
                new Conversation.Usage(1_000_000, 200_000, 3_000_000, 50_000, 4.0, 30L, 200_000L),
                new Conversation.Usage(10, 20, 0, 0, 0.5, 60L, 200_000L));

        AgentUsageReport.Totals t = AgentUsageReport.Totals.of(c);

        assertThat(t.turns()).isEqualTo(2);
        assertThat(t.tokens()).isEqualTo(4_250_030L);
        assertThat(t.costUsd()).isCloseTo(4.5, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(AgentUsageReport.Totals.of(conversation("silent", "claude-code", "sonnet", 1))).isEqualTo(AgentUsageReport.Totals.EMPTY);
    }

    @Test
    void sessionInfoCarriesTheConversationsSpend() {
        Conversation c = conversation("y", "claude-code", "sonnet", 300, new Conversation.Usage(5, 5, 0, 0, null, null, null));

        SessionInfo info = SessionInfo.of(c, new SessionInfo.ContainerInfo("c-y", "", ""), false);

        assertThat(info.usage().turns()).isEqualTo(1);
        assertThat(info.usage().tokens()).isEqualTo(10L);
        assertThat(info.usage().costUsd()).isNull();
    }
}
