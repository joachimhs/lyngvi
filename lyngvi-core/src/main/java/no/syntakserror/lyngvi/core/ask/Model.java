package no.syntakserror.lyngvi.core.ask;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A model an agent can run with.
 *
 * @param local whether it comes from a model server of the user's own — the provider block in the agent's own
 *              configuration file ({@code agent.json localModels}) — rather than from the agent's own catalogue.
 *              Those are the few the user configured by hand, so they are offered as a group of their own. The
 *              cached catalogue ({@code state/agents/<id>/models.json}) holds only the agent's, so one written
 *              before this field existed reads back as {@code false}, which is what it means.
 */
public record Model(String id, String label, @JsonProperty("default") boolean isDefault, boolean local) {}
