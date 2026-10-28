package no.syntakserror.lyngvi.core.session;

import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.ResourceLimits;

/**
 * A session's conversation file, {@code state/conversations/<id>.json} (spec §5.4).
 * Shared by the CLI and the GUI; written only by the core.
 */
public record Conversation(
        int schemaVersion,
        String id,
        String title,
        long createdAt,
        long updatedAt,
        long lastActiveAt,
        String agent,
        String agentSessionId,
        String model,
        List<String> repos,
        ResourceLimits resources,
        String container,
        Map<String, RepoBase> base,
        List<Delivery> deliveries,
        List<Message> messages,
        String cacheMode) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    /**
     * The conversation as it was before the shared caches existed: {@code cacheMode} null, which means
     * «whatever the profile says». A file written by an older version deserialises straight into this.
     */
    public Conversation(int schemaVersion, String id, String title, long createdAt, long updatedAt, long lastActiveAt,
                        String agent, String agentSessionId, String model, List<String> repos, ResourceLimits resources,
                        String container, Map<String, RepoBase> base, List<Delivery> deliveries, List<Message> messages) {
        this(schemaVersion, id, title, createdAt, updatedAt, lastActiveAt, agent, agentSessionId, model, repos,
                resources, container, base, deliveries, messages, null);
    }

    /** Where a repository's working copy started. */
    public record RepoBase(String hostSha, String hostBranch, String baseCommit, boolean hostDirty, History history, Integer depth) {}

    /** One delivery ({@code session apply}). */
    public record Delivery(long at, String branch, String message, String mode, Map<String, DeliveryRepo> repos) {}

    public record DeliveryRepo(int files, String copyCommit, String hostCommit) {}

    /** One message; {@code work} holds the agent's tool calls, {@code usage} its token accounting. */
    public record Message(String id, String role, long at, String text, List<String> attachments,
                          List<WorkItem> work, Usage usage, String stopReason) {}

    public record WorkItem(String type, String name, String input, String output, boolean isError) {}

    public record Usage(long input, long output, long cacheRead, long cacheWrite, Double costUsd, Long contextUsed, Long contextMax) {}

    public Conversation {
        repos = List.copyOf(repos);
        base = Map.copyOf(base);
        deliveries = List.copyOf(deliveries);
        messages = List.copyOf(messages);
    }

    /** The first eight characters of the id, used in container names. */
    public String shortId() {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    public Conversation withTitle(String newTitle, long now) {
        return new Conversation(schemaVersion, id, newTitle, createdAt, now, lastActiveAt, agent, agentSessionId, model,
                repos, resources, container, base, deliveries, messages, cacheMode);
    }

    /**
     * The conversation with more working copies in it (spec §5.2.1). {@code newBase} holds the baseline of
     * each repository, the ones already there as well as the ones added, because that is what a delivery
     * measures every copy from.
     */
    public Conversation withRepos(List<String> newRepos, Map<String, RepoBase> newBase, long now) {
        return new Conversation(schemaVersion, id, title, createdAt, now, lastActiveAt, agent, agentSessionId, model,
                newRepos, resources, container, newBase, deliveries, messages, cacheMode);
    }

    public Conversation withLastActiveAt(long now) {
        return new Conversation(schemaVersion, id, title, createdAt, now, now, agent, agentSessionId, model,
                repos, resources, container, base, deliveries, messages, cacheMode);
    }
}
