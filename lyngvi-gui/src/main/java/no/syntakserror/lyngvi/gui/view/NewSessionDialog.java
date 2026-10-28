package no.syntakserror.lyngvi.gui.view;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import no.syntakserror.lyngvi.core.api.NewSessionOptions;
import no.syntakserror.lyngvi.core.config.Profile;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.RepoEntry;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.config.ResourcePreset;
import no.syntakserror.lyngvi.core.contract.AgentInfo;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.gui.viewmodel.SessionViewModel;
import no.syntakserror.lyngvi.gui.viewmodel.Settings;

/**
 * «New conversation» (spec §9.3), in three groups: what the conversation is about (title, repositories), who
 * answers (agent, model), and — folded away, because the sandbox's defaults are nearly always right — the
 * container itself (history depth, resource limits, shared caches). The repositories ticked are remembered per
 * sandbox, so the next conversation starts from the same choice.
 */
public final class NewSessionDialog extends Dialog<NewSessionOptions> {

    /** The width of the label column, shared by the two grids so the folded section lines up with the rest. */
    private static final double LABEL_WIDTH = 110;

    public NewSessionDialog(Window owner, ResourceBundle b, Messages m, SessionViewModel sessions, Settings settings, Path sandboxDir,
                            List<RepoEntry> repos, List<AgentInfo> agents, String defaultAgent, List<ResourcePreset> presets) {
        initOwner(owner);
        setTitle(b.getString("gui.session.new.title"));
        setHeaderText(b.getString("gui.session.new.header"));
        ButtonType create = new ButtonType(b.getString("gui.session.new.create"), ButtonType.OK.getButtonData());
        getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);

        // --- What: title and repositories -------------------------------------------------------------
        TextField title = new TextField();
        title.setPromptText(b.getString("gui.session.new.title.prompt"));
        title.setMaxWidth(Double.MAX_VALUE);
        RepoSelection initial = RepoSelection.initial(repos.stream().map(RepoEntry::name).toList(), settings.lastRepos(sandboxDir));
        List<CheckBox> repoBoxes = new ArrayList<>();
        GridPane repoBox = new GridPane();
        repoBox.setHgap(12);
        repoBox.setVgap(4);
        Path home = Path.of(System.getProperty("user.home", ""));
        for (RepoEntry r : repos) {
            CheckBox cb = new CheckBox(r.name());
            cb.setSelected(initial.ticked().contains(r.name()));
            Label path = new Label(abbreviate(r.path(), home));
            path.getStyleClass().add("repo-path");
            path.setTooltip(new Tooltip(r.path().toString()));
            path.setOnMouseClicked(e -> { if (!cb.isDisabled()) cb.fire(); });
            repoBox.addRow(repoBoxes.size(), cb, path);
            repoBoxes.add(cb);
        }
        CheckBox noRepos = new CheckBox(b.getString("gui.session.new.norepos"));
        noRepos.selectedProperty().addListener((o, a, v) -> repoBoxes.forEach(cb -> cb.setDisable(v)));
        noRepos.setSelected(initial.none());
        VBox repoColumn = new VBox(4, repoBox, noRepos);
        if (!repos.isEmpty()) VBox.setMargin(noRepos, new Insets(4, 0, 0, 0));

        // --- Who: agent and model ------------------------------------------------------------------------
        // An agent with a model server of its own gets a second row for that server: same agent, its models alone.
        List<AgentChoices.Choice> agentRows = AgentChoices.of(agents.stream().filter(a -> a.enabled() && a.available()).toList());
        ComboBox<AgentChoices.Choice> agent = new ComboBox<>(FXCollections.observableArrayList(agentRows));
        agent.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(AgentChoices.Choice c) {
                return AgentChoices.label(c, b.getString("gui.session.new.agent.local"), b.getString("gui.agents.notloggedin"));
            }
            @Override public AgentChoices.Choice fromString(String s) { return null; }
        });
        agent.setValue(AgentChoices.initial(agentRows, defaultAgent));
        ComboBox<ModelChoices.Choice> model = new ComboBox<>();
        model.setPromptText(b.getString("gui.session.new.model.auto"));
        ModelDropdown.wire(model);
        ModelChoices.Groups groups = new ModelChoices.Groups(b.getString("gui.model.group.local"), b.getString("gui.model.group.other"));
        AtomicReference<List<Model>> catalogue = new AtomicReference<>(List.of());
        // What the chosen row offers: the whole catalogue, or only the models from the user's own server.
        java.util.function.Supplier<List<Model>> offered = () -> AgentChoices.models(catalogue.get(), agent.getValue());
        Consumer<String> show = wanted -> {
            String id = agent.getValue() == null ? null : agent.getValue().id();
            model.getItems().setAll(ModelChoices.dropdown(offered.get(),
                    id == null ? List.of() : settings.favouriteModels(id), wanted, groups));
            model.setValue(model.getItems().stream().filter(c -> c.id().equals(wanted)).findFirst().orElse(null));
        };
        Button browseModels = new Button();
        Runnable loadModels = () -> {
            model.getItems().clear();
            AgentChoices.Choice a = agent.getValue();
            if (a == null) return;
            // The server's own row already lists every model it has, so there is no wider catalogue to browse.
            browseModels.setDisable(a.localOnly());
            sessions.models(a.id(), ms -> {
                catalogue.set(ms);
                List<Model> rows = offered.get();
                Optional<String> last = settings.lastModel(a.id());
                // On the server's own row the model last used is only a starting point when the server has it;
                // on the agent's row it stands whatever the catalogue says, as it always has.
                show.accept(a.localOnly()
                        ? last.filter(id -> rows.stream().anyMatch(x -> x.id().equals(id)))
                                .or(() -> rows.stream().findFirst().map(Model::id)).orElse(null)
                        : last.orElseGet(() -> rows.stream().filter(Model::isDefault).findFirst().map(Model::id).orElse(null)));
            });
        };
        agent.valueProperty().addListener((o, a, v) -> loadModels.run());
        loadModels.run();
        browseModels.setGraphic(Icon.of("search"));
        browseModels.getStyleClass().add("icon-button");
        browseModels.setTooltip(new Tooltip(b.getString("gui.model.picker.open")));
        browseModels.setOnAction(e -> {
            AgentChoices.Choice a = agent.getValue();
            if (a == null || a.localOnly()) return;
            String selected = ModelDropdown.selected(model).orElse(null);
            ModelPickerDialog d = new ModelPickerDialog(owner, b, sessions, a.id(), catalogue.get(), settings.favouriteModels(a.id()), selected);
            Optional<String> chosen = d.showAndWait();
            catalogue.set(d.catalogue());
            settings.setFavouriteModels(a.id(), d.favourites());
            settings.save();
            show.accept(chosen.orElse(selected));
        });
        HBox modelRow = new HBox(6, model, browseModels);
        modelRow.setAlignment(Pos.CENTER_LEFT);

        // --- The container: history, limits, caches -----------------------------------------------------
        ComboBox<History> history = new ComboBox<>(FXCollections.observableArrayList(History.values()));
        history.setValue(History.SHALLOW);
        TextField depth = new TextField();
        depth.setPromptText(b.getString("gui.session.new.depth.prompt"));
        depth.setPrefColumnCount(5);
        HBox historyRow = new HBox(6, history, depth);
        historyRow.setAlignment(Pos.CENTER_LEFT);
        TextField mem = new TextField();
        mem.setPromptText("8g");
        mem.setPrefColumnCount(6);
        TextField cpus = new TextField();
        cpus.setPromptText("4.0");
        cpus.setPrefColumnCount(6);
        // A preset is the whole set of limits; mem and cpus below override the two that are worth seeing.
        // The first choice is no preset at all — the sandbox default — so a preset can be taken back.
        List<ResourcePreset> presetChoices = new ArrayList<>();
        presetChoices.add(null);
        presetChoices.addAll(presets);
        ComboBox<ResourcePreset> preset = new ComboBox<>(FXCollections.observableArrayList(presetChoices));
        preset.setPromptText(b.getString("gui.session.new.preset.default"));
        preset.setMaxWidth(Double.MAX_VALUE);
        preset.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ResourcePreset p) { return p == null ? b.getString("gui.session.new.preset.default") : PresetChoices.describe(p, m); }
            @Override public ResourcePreset fromString(String s) { return null; }
        });
        preset.valueProperty().addListener((o, a, p) -> {
            mem.setText(p == null ? "" : p.limits().mem());
            cpus.setText(p == null ? "" : p.limits().cpus());
        });
        HBox limitsRow = new HBox(6, new Label("mem"), mem, new Label("cpus"), cpus);
        limitsRow.setAlignment(Pos.CENTER_LEFT);
        // The shared tool caches. «Off» is the one to pick for code there is no reason to trust: a shared
        // cache is the one thing sessions of this sandbox have in common.
        ComboBox<String> cache = new ComboBox<>(FXCollections.observableArrayList(
                null, Profile.Cache.OFF_MODE, Profile.Cache.SHARED, Profile.Cache.READONLY));
        cache.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String mode) {
                return mode == null ? b.getString("gui.session.new.cache.default") : b.getString("gui.session.new.cache." + mode);
            }
            @Override public String fromString(String s) { return s; }
        });
        cache.getSelectionModel().selectFirst();

        // --- Layout -------------------------------------------------------------------------------------
        GridPane main = grid();
        int row = 0;
        main.addRow(row++, new Label(b.getString("gui.session.new.title.label")), title);
        Label reposLabel = new Label(b.getString("gui.session.new.repos"));
        GridPane.setValignment(reposLabel, VPos.TOP);
        GridPane.setMargin(reposLabel, new Insets(3, 0, 0, 0));
        main.addRow(row++, reposLabel, repoColumn);
        Separator split = new Separator();
        GridPane.setColumnSpan(split, 2);
        GridPane.setMargin(split, new Insets(6, 0, 6, 0));
        main.add(split, 0, row++);
        main.addRow(row++, new Label(b.getString("gui.session.new.agent")), agent);
        main.addRow(row++, new Label(b.getString("gui.session.new.model")), modelRow);

        // Inset by the pane's padding, and the label column narrower by as much, so its fields line up with the rest.
        GridPane containerGrid = grid();
        containerGrid.setPadding(new Insets(10, 12, 12, 12));
        containerGrid.getColumnConstraints().getFirst().setMinWidth(LABEL_WIDTH - 12);
        row = 0;
        containerGrid.addRow(row++, new Label(b.getString("gui.session.new.history")), historyRow);
        containerGrid.addRow(row++, new Label(b.getString("gui.session.new.preset")), preset);
        containerGrid.addRow(row++, new Label(b.getString("gui.session.new.resources")), limitsRow);
        containerGrid.addRow(row++, new Label(b.getString("gui.session.new.cache")), cache);
        TitledPane container = new TitledPane(b.getString("gui.session.new.container"), containerGrid);
        container.setAnimated(false);
        container.setExpanded(false);
        // The dialog does not follow its content on its own: fit the window to the pane after a fold.
        container.expandedProperty().addListener((o, a, v) -> javafx.application.Platform.runLater(() -> {
            Window w = getDialogPane().getScene() == null ? null : getDialogPane().getScene().getWindow();
            if (w != null) w.sizeToScene();
        }));

        VBox content = new VBox(14, main, container);
        content.setPadding(new Insets(4, 0, 0, 0));
        content.setMinWidth(560);
        getDialogPane().setContent(content);
        javafx.application.Platform.runLater(title::requestFocus);

        setResultConverter(bt -> {
            if (bt != create) return null;
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < repos.size(); i++) if (repoBoxes.get(i).isSelected()) selected.add(repos.get(i).name());
            settings.setLastRepos(sandboxDir, noRepos.isSelected() ? List.of() : selected);
            settings.save();
            Optional<Integer> d = Optional.empty();
            try {
                if (!depth.getText().isBlank()) d = Optional.of(Integer.parseInt(depth.getText().strip()));
            } catch (NumberFormatException ignored) {
                // an unparsable depth falls back to the profile default
            }
            ResourceLimits typed = new ResourceLimits(blank(mem), null, blank(cpus), null);
            ResourceLimits limits = preset.getValue() != null ? preset.getValue().limits().merge(typed)
                    : typed.mem() == null && typed.cpus() == null ? null : typed;
            return new NewSessionOptions(
                    title.getText().isBlank() ? Optional.empty() : Optional.of(title.getText().strip()),
                    noRepos.isSelected() || selected.size() == repos.size() ? Optional.empty() : Optional.of(selected),
                    agent.getValue() == null ? Optional.empty() : Optional.of(agent.getValue().id()),
                    ModelDropdown.selected(model),
                    Optional.ofNullable(history.getValue()), d, Optional.ofNullable(limits), noRepos.isSelected(),
                    Optional.ofNullable(cache.getValue()));
        });
    }

    /**
     * Which repositories to tick when the dialog opens, from what was ticked last time in this sandbox.
     *
     * @param ticked the names to tick; every name when nothing is remembered or nothing remembered still exists
     * @param none   whether «no repositories» is ticked, as it was last time
     */
    record RepoSelection(Set<String> ticked, boolean none) {

        static RepoSelection initial(List<String> names, Optional<List<String>> last) {
            if (last.isPresent() && last.get().isEmpty()) return new RepoSelection(Set.copyOf(names), true);
            Set<String> remembered = last.map(l -> l.stream().filter(names::contains).collect(java.util.stream.Collectors.toSet())).orElse(Set.of());
            return new RepoSelection(remembered.isEmpty() ? Set.copyOf(names) : remembered, false);
        }
    }

    /** A host path with the home directory as {@code ~}, which is all the eye needs next to the repository's name. */
    static String abbreviate(Path path, Path home) {
        if (home.toString().isEmpty() || !path.startsWith(home)) return path.toString();
        if (path.equals(home)) return "~";
        return "~" + path.getFileSystem().getSeparator() + home.relativize(path);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(LABEL_WIDTH);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }

    private static String blank(TextField f) {
        return f.getText() == null || f.getText().isBlank() ? null : f.getText().strip();
    }
}
