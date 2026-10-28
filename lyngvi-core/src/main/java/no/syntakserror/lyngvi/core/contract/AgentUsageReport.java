package no.syntakserror.lyngvi.core.contract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.ask.AgentUsage;
import no.syntakserror.lyngvi.core.session.Conversation;

/**
 * One agent's spend, as {@code agent usage} and the GUI's usage view show it: the quotas the
 * agent's own account reports ({@code account}, empty for agents that expose none) and the
 * token and cost accounting this sandbox has kept from its conversations ({@code local}).
 */
public record AgentUsageReport(String agentId, String label, AgentUsage account, Totals local,
                               List<Group> byModel, List<Session> sessions) {

    public AgentUsageReport {
        byModel = List.copyOf(byModel);
        sessions = List.copyOf(sessions);
    }

    /** Token and cost accounting; {@code turns} counts the messages that carried usage. */
    public record Totals(int turns, long input, long output, long cacheRead, long cacheWrite, Double costUsd) {

        public static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, null);

        public long tokens() {
            return input + output + cacheRead + cacheWrite;
        }

        public Totals plus(Totals o) {
            return new Totals(turns + o.turns, input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite,
                    sum(costUsd, o.costUsd));
        }

        /** Everything {@code c} has spent so far: the sum over the messages that carried usage. */
        public static Totals of(Conversation c) {
            Totals totals = EMPTY;
            for (Conversation.Message message : c.messages()) {
                if (message.usage() != null) totals = totals.plus(message.usage());
            }
            return totals;
        }

        public Totals plus(Conversation.Usage u) {
            return new Totals(turns + 1, input + u.input(), output + u.output(), cacheRead + u.cacheRead(), cacheWrite + u.cacheWrite(),
                    sum(costUsd, u.costUsd()));
        }

        private static Double sum(Double a, Double b) {
            if (a == null) return b;
            return b == null ? a : a + b;
        }
    }

    /** Usage for one model, biggest first. */
    public record Group(String key, Totals totals) {}

    /** One conversation's share; {@code contextUsed}/{@code contextMax} are from its last turn. */
    public record Session(String id, String title, String model, long lastActiveAt, Totals totals, Long contextUsed, Long contextMax) {}

    /** Rolls {@code conversations} up for one agent; conversations of other agents are skipped. */
    public static AgentUsageReport of(String agentId, String label, AgentUsage account, List<Conversation> conversations) {
        Totals local = Totals.EMPTY;
        Map<String, Totals> models = new LinkedHashMap<>();
        List<Session> sessions = new ArrayList<>();
        for (Conversation c : conversations) {
            if (agentId != null && !agentId.equals(c.agent())) continue;
            Totals totals = Totals.of(c);
            Long contextUsed = null;
            Long contextMax = null;
            for (Conversation.Message message : c.messages()) {
                Conversation.Usage u = message.usage();
                if (u == null) continue;
                if (u.contextUsed() != null) contextUsed = u.contextUsed();
                if (u.contextMax() != null) contextMax = u.contextMax();
            }
            if (totals.turns() == 0) continue;
            String model = c.model() == null || c.model().isBlank() ? "" : c.model();
            models.merge(model, totals, Totals::plus);
            local = local.plus(totals);
            sessions.add(new Session(c.id(), c.title(), model, c.lastActiveAt(), totals, contextUsed, contextMax));
        }
        sessions.sort(Comparator.comparingLong(Session::lastActiveAt).reversed());
        List<Group> byModel = models.entrySet().stream()
                .map(e -> new Group(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong((Group g) -> g.totals().tokens()).reversed())
                .toList();
        return new AgentUsageReport(agentId, label, account, local, byModel, sessions);
    }
}
