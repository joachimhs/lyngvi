package no.syntakserror.lyngvi.gui.viewmodel;

import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import no.syntakserror.lyngvi.core.contract.SkillInfo;

/**
 * The sandbox's skills: the list, the on/off flags and the content of one skill at a time (spec §3.6).
 *
 * <p>Every change goes through {@code SkillService}, which copies the switched-on skills into the agents'
 * configuration directories as part of the same call — so by the time the list comes back, the next message in
 * any conversation already has the new set.
 */
public final class SkillsViewModel extends BaseViewModel {

    private final ObservableList<SkillInfo> skills = FXCollections.observableArrayList();
    /** The enabled agents that take skills at all, as a label list; empty when none of them do. */
    private final StringProperty agents = new SimpleStringProperty("");

    public SkillsViewModel(SandboxContext ctx, Async async, AppLog log) {
        super(ctx, async, log);
    }

    public ObservableList<SkillInfo> skills() {
        return skills;
    }

    public ReadOnlyStringProperty agentsProperty() {
        return agents;
    }

    /** Empties the list: the window has left the sandbox these skills belonged to. */
    public void clear() {
        skills.clear();
        agents.set("");
    }

    public void refresh() {
        call(() -> sandbox().skillService().list(), skills::setAll);
        call(() -> String.join(", ", sandbox().skillService().agentsTakingSkills()), agents::set);
    }

    /** Reads one skill's {@code SKILL.md} for the editor. */
    public void load(String name, Consumer<String> onContent) {
        call(() -> sandbox().skillService().read(name), onContent);
    }

    public void save(String name, String content, Runnable onSaved) {
        call(() -> sandbox().skillService().save(name, content), saved -> {
            log.info("skill " + saved.name() + " saved" + (saved.valid() ? "" : " (" + saved.problem() + ")"));
            refresh();
            onSaved.run();
        });
    }

    public void setEnabled(String name, boolean enabled, Runnable onDone) {
        call(() -> sandbox().skillService().setEnabled(name, enabled), changed -> {
            log.info("skill " + changed.name() + (changed.enabled() ? " switched on" : " switched off"));
            refresh();
            onDone.run();
        });
    }

    public void create(String name, Consumer<SkillInfo> onCreated) {
        call(() -> sandbox().skillService().create(name), created -> {
            log.info("skill " + created.name() + " created in " + created.path());
            refresh();
            onCreated.accept(created);
        });
    }

    public void remove(String name, Runnable onRemoved) {
        call(() -> sandbox().skillService().remove(name), removed -> {
            log.info("skill " + removed.name() + " removed");
            refresh();
            onRemoved.run();
        });
    }
}
