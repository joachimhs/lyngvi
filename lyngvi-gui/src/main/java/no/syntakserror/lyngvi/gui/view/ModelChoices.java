package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import no.syntakserror.lyngvi.core.ask.Model;

/**
 * How a model catalogue is presented: starred models first, a search that matches id and label, and the short
 * list the composer offers without opening the picker. Pure, so the picker and the composer cannot disagree.
 */
public final class ModelChoices {

    /** How many models a short list falls back to before the user has starred any. */
    public static final int QUICK_PICKS = 6;

    /** One model as offered to the user, or — with no id — a heading over the models that follow it. */
    public record Choice(String id, String label, boolean favourite) {

        /** A heading names the group under it and is not a model: no model has an empty id. */
        public static Choice heading(String label) {
            return new Choice("", label, false);
        }

        public boolean heading() {
            return id.isEmpty();
        }
    }

    /** The headings a grouped list puts over its groups; the texts come from the bundle, so this stays pure. */
    public record Groups(String local, String other) {}

    private ModelChoices() {}

    /**
     * Starred models first, in the order the user starred them, then the rest of the catalogue in its own order.
     * A favourite the catalogue no longer lists is kept — it is still what the user picked — with its id as label.
     */
    public static List<Choice> order(List<Model> catalogue, List<String> favourites) {
        Map<String, Model> byId = new LinkedHashMap<>();
        for (Model m : catalogue) byId.put(m.id(), m);
        Set<String> starred = new LinkedHashSet<>(favourites);
        List<Choice> out = new ArrayList<>();
        for (String id : starred) {
            Model m = byId.get(id);
            out.add(new Choice(id, m == null ? id : m.label(), true));
        }
        for (Model m : catalogue) {
            if (!starred.contains(m.id())) out.add(new Choice(m.id(), m.label(), false));
        }
        return List.copyOf(out);
    }

    /** The choices whose id or label contains every word of the query, ignoring case; a blank query matches all. */
    public static List<Choice> filter(List<Choice> choices, String query) {
        if (query == null || query.isBlank()) return choices;
        String[] words = query.strip().toLowerCase(Locale.ROOT).split("\\s+");
        List<Choice> out = new ArrayList<>();
        for (Choice c : choices) {
            String haystack = (c.id() + " " + c.label()).toLowerCase(Locale.ROOT);
            boolean matches = true;
            for (String w : words) matches &= haystack.contains(w);
            if (matches) out.add(c);
        }
        return List.copyOf(out);
    }

    /** Adds the id to the favourites, or removes it when it is already starred; the order of the others is kept. */
    public static List<String> toggle(List<String> favourites, String id) {
        List<String> out = new ArrayList<>(favourites);
        if (!out.remove(id)) out.add(id);
        return List.copyOf(out);
    }

    /**
     * What the composer's list offers: the starred models — or, with nothing starred yet, the first {@code fallback}
     * of the catalogue, which are the ones the agent descriptor declares — always including the model in use.
     */
    /**
     * What the dropdown offers when a model server of the user's own is configured: every model from that server
     * first, under a heading of its own, then the short list of the rest under theirs. A handful of models the user
     * set up by hand would otherwise be told apart from hundreds the agent happens to know only by their provider
     * prefix — and the dropdown shows six.
     *
     * <p>One group is no group: with nothing local in the catalogue this is the short list exactly as before, and a
     * catalogue of nothing but local models — what the agent's own «local models» row offers — is listed plainly.
     * Either way the model in use is among the rows, whichever group it belongs to.
     */
    public static List<Choice> dropdown(List<Model> catalogue, List<String> favourites, String selected, Groups groups) {
        List<Choice> ordered = order(catalogue, favourites);
        Set<String> local = catalogue.stream().filter(Model::local).map(Model::id).collect(Collectors.toCollection(LinkedHashSet::new));
        if (local.isEmpty()) return quickPicks(ordered, selected, QUICK_PICKS);
        List<Choice> mine = ordered.stream().filter(c -> local.contains(c.id())).toList();
        // The local ones are all shown, so only a selection outside them has to be kept by the short list.
        List<Choice> rest = quickPicks(ordered.stream().filter(c -> !local.contains(c.id())).toList(),
                selected != null && local.contains(selected) ? null : selected, QUICK_PICKS);
        if (rest.isEmpty()) return mine;
        List<Choice> out = new ArrayList<>();
        out.add(Choice.heading(groups.local()));
        out.addAll(mine);
        out.add(Choice.heading(groups.other()));
        out.addAll(rest);
        return List.copyOf(out);
    }

    public static List<Choice> quickPicks(List<Choice> ordered, String selected, int fallback) {
        List<Choice> out = new ArrayList<>(ordered.stream().filter(Choice::favourite).toList());
        if (out.isEmpty()) out.addAll(ordered.subList(0, Math.min(Math.max(fallback, 0), ordered.size())));
        if (selected != null && !selected.isBlank() && out.stream().noneMatch(c -> c.id().equals(selected))) {
            out.addFirst(ordered.stream().filter(c -> c.id().equals(selected)).findFirst()
                    .orElseGet(() -> new Choice(selected, selected, false)));
        }
        return List.copyOf(out);
    }
}
