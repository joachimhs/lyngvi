package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.agents.LocalModelConfig;

/**
 * How a local model server's answer meets the models the user listed: pure, so the dialog only draws it.
 * Before the server has been asked every model is {@link State#UNKNOWN} — «not tested» is not the same as «missing».
 */
public final class LocalModelChoices {

    public enum State { UNKNOWN, AVAILABLE, MISSING }

    /** One listed model and what the last test said about it. */
    public record Row(String id, State state) {}

    private LocalModelChoices() {}

    /** The listed models in their own order, marked against the server's answer ({@code probe} null: untested). */
    public static List<Row> rows(List<String> models, LocalModelConfig.Probe probe) {
        List<Row> out = new ArrayList<>();
        for (String id : models) {
            State state = probe == null || !probe.reachable() ? State.UNKNOWN
                    : probe.available().contains(id) ? State.AVAILABLE : State.MISSING;
            out.add(new Row(id, state));
        }
        return List.copyOf(out);
    }

    /** What the server has and the user has not listed — what «add from server» would add. */
    public static List<String> notListed(List<String> models, LocalModelConfig.Probe probe) {
        if (probe == null || !probe.reachable()) return List.of();
        return probe.available().stream().filter(id -> !models.contains(id)).toList();
    }

    /**
     * The catalogue id a configured model is offered under: the provider it belongs to and the id the server knows
     * it by, which is what the agent's own listing prints — {@code lmstudio/qwen3-coder-30b}.
     */
    public static String catalogueId(String provider, String model) {
        return provider + "/" + model;
    }

    /**
     * The starred models after the configured list changed: a model the user has just configured is starred, so the
     * next conversation offers it without a trip through the picker, and one taken out of the configuration loses
     * its star again. Stars on anything else are left exactly as they were.
     *
     * @param before the models configured when the dialog opened, {@code after} the ones it saved
     */
    public static List<String> star(List<String> favourites, String provider, List<String> before, List<String> after) {
        List<String> out = new ArrayList<>(favourites);
        for (String model : before) {
            if (!after.contains(model)) out.remove(catalogueId(provider, model));
        }
        for (String model : after) {
            String id = catalogueId(provider, model);
            if (!out.contains(id)) out.add(id);
        }
        return List.copyOf(out);
    }

    /** Adds an id, trimmed; a blank or already listed one changes nothing. */
    public static List<String> add(List<String> models, String id) {
        String trimmed = id == null ? "" : id.strip();
        if (trimmed.isEmpty() || models.contains(trimmed)) return List.copyOf(models);
        List<String> out = new ArrayList<>(models);
        out.add(trimmed);
        return List.copyOf(out);
    }
}
