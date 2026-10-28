package no.syntakserror.lyngvi.core.contract;

import java.util.List;
import no.syntakserror.lyngvi.core.session.Conversation;

/** A session as listed by {@code session list} and {@code session status}. */
public record SessionInfo(String id, String title, String agent, List<String> repos, long createdAt, long lastActiveAt,
                          int deliveries, int messages, AgentUsageReport.Totals usage, ContainerInfo container, boolean busy) {

    /** The session's container; {@code state} is empty when it does not exist. */
    public record ContainerInfo(String name, String state, String health) {}

    public static SessionInfo of(Conversation c, ContainerInfo container, boolean busy) {
        return new SessionInfo(c.id(), c.title(), c.agent(), c.repos(), c.createdAt(), c.lastActiveAt(),
                c.deliveries().size(), c.messages().size(), AgentUsageReport.Totals.of(c), container, busy);
    }
}
