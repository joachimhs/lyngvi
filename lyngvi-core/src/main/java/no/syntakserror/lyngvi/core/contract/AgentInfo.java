package no.syntakserror.lyngvi.core.contract;

/**
 * An agent as listed by {@code agent list}.
 *
 * @param available          true when an adapter (built-in or {@code adapter.sh}) exists for the id
 * @param enabled            true when the profile enables the agent
 * @param canUseLocalModels  true when the agent can answer from a model server of the user's own at all
 *                           ({@code agent.json localModels} declares where its provider lives). It is what tells
 *                           «none configured yet» apart from «not a thing for this agent», which is the difference
 *                           between an offer worth making and a button that can only fail.
 * @param localModels        how many models the agent's own configuration file offers from that server; 0 when it
 *                           has no such notion or none are configured.
 *                           A server of your own is a way of working, not only a model: it is offered as its own choice.
 */
public record AgentInfo(String id, String label, boolean available, boolean enabled, boolean loggedIn, String authMode,
                        String account, boolean canUseLocalModels, int localModels) {}
