package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.contract.AgentInfo;

/**
 * How the agents are offered when a conversation is made: one row per agent, and — under an agent that has a model
 * server of the user's own — a second row for that server alone. Working against your own server is a choice in its
 * own right, not a model buried in the agent's catalogue: that row answers with those models and nothing else.
 *
 * <p>Pure, so the dialog only draws it. Both rows are the same agent; only the models on offer differ.
 */
public final class AgentChoices {

    /** One row of the agent list: the agent, and whether this row is its own model server rather than all of it. */
    public record Choice(AgentInfo agent, boolean localOnly) {

        public String id() {
            return agent.id();
        }
    }

    /**
     * Where an agent stands with a model server of its own. Three states and not two: an agent that cannot use one
     * at all is not an agent that has none yet, and only the second is worth inviting the user to set up.
     */
    public enum OwnModels {
        /** The agent has no such notion ({@code agent.json} declares no {@code localModels}): nothing to offer. */
        UNSUPPORTED,
        /** It could use a server of your own, and none is configured yet. */
        NONE,
        /** Models from a server of your own are configured, and the agent answers with them. */
        CONFIGURED
    }

    private AgentChoices() {}

    /** The one reading of {@link AgentInfo} the Agents page, its «Local models…» button and the wizard all share. */
    public static OwnModels ownModels(AgentInfo a) {
        if (a == null || !a.canUseLocalModels()) return OwnModels.UNSUPPORTED;
        return a.localModels() == 0 ? OwnModels.NONE : OwnModels.CONFIGURED;
    }

    /**
     * The rows, in the order the agents came: each agent, followed by its own model server when one is configured
     * with models. An agent with none has the one row it always had.
     */
    public static List<Choice> of(List<AgentInfo> agents) {
        List<Choice> out = new ArrayList<>();
        for (AgentInfo a : agents) {
            out.add(new Choice(a, false));
            if (a.localModels() > 0) out.add(new Choice(a, true));
        }
        return List.copyOf(out);
    }

    /**
     * The row the dialog should start on: the one for {@code agentId}, the agent itself rather than its server.
     * Falls back to the first row, so a dialog is never opened with nothing chosen.
     */
    public static Choice initial(List<Choice> choices, String agentId) {
        return choices.stream().filter(c -> !c.localOnly() && c.id().equals(agentId)).findFirst()
                .orElseGet(() -> choices.isEmpty() ? null : choices.getFirst());
    }

    /**
     * How a row reads. The agent's own row names the id, as it always has, and says when it is not logged in; the
     * server's row names the server instead — logging in is beside the point there, which is half of why it exists.
     */
    public static String label(Choice c, String localModels, String notLoggedIn) {
        if (c == null) return "";
        if (c.localOnly()) return c.agent().label() + " (" + localModels + ")";
        return c.agent().label() + " (" + c.id() + ")" + (c.agent().loggedIn() ? "" : "  " + notLoggedIn);
    }

    /**
     * Whether a question asked on this row goes to the conversation that is open, or needs one of its own. A
     * conversation is tied to the agent that answers it, so another agent needs another conversation; the row for
     * an agent's own model server is that same agent — it narrows the models and nothing else — and keeps it.
     * With no conversation open there is nothing to keep, and with no row chosen the conversation's agent decides.
     */
    public static boolean answers(Choice c, String conversationAgent) {
        if (conversationAgent == null || conversationAgent.isBlank()) return false;
        return c == null || c.id().equals(conversationAgent);
    }

    /** The models a row offers: everything the agent knows, or only what the user's own server serves. */
    public static List<Model> models(List<Model> catalogue, Choice c) {
        return c != null && c.localOnly() ? catalogue.stream().filter(Model::local).toList() : catalogue;
    }
}
