package no.syntakserror.lyngvi.core.ask;

import java.util.List;
import java.util.function.Consumer;

/**
 * The adapter contract (spec §6.2). Built-in adapters implement it directly;
 * external {@code adapter.sh} scripts are wrapped by {@link ProcessAdapter}.
 * Adapters never touch the conversation file and never run {@code podman run}.
 */
public interface AgentAdapter {

    AgentDescriptor descriptor();

    /** Whether {@code ask} needs the session's container to be running. */
    default boolean needsContainer() {
        return true;
    }

    /** Called once after the session's container starts (symlinks, agent config). */
    default void prepare(AskRequest request) {}

    /** Starts a turn; events are delivered on the adapter's thread, in order, ending with {@code done}. */
    AgentRunHandle ask(AskRequest request, Consumer<AgentEvent> onEvent);

    Suggestion suggest(AskRequest request, String diff);

    List<Model> models();

    /**
     * The models to offer, re-reading the agent's own catalogue when {@code refresh} is set.
     * Adapters that can only offer a fixed list ignore the flag.
     */
    default List<Model> models(boolean refresh) {
        return models();
    }

    AuthStatus authStatus();

    /**
     * The interactive login command (a full argv that needs a terminal), when the agent's login is a TUI.
     * Empty when the flow is non-interactive (lines streamed by {@link #login}) or configured by an env file.
     */
    default java.util.Optional<List<String>> loginCommand(String mode) {
        return java.util.Optional.empty();
    }

    /** Runs the agent's login flow; lines of output (URLs, codes) go to {@code onLine}. */
    default int login(String mode, Consumer<String> onLine) {
        throw new UnsupportedOperationException(descriptor().id() + " has no login flow");
    }

    default void logout() {}

    default AgentUsage usage() {
        return AgentUsage.unsupported();
    }
}
