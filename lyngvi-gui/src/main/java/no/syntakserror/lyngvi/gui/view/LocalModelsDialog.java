package no.syntakserror.lyngvi.gui.view;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import no.syntakserror.lyngvi.core.agents.LocalModelConfig;
import no.syntakserror.lyngvi.core.stack.DomainEntry;
import no.syntakserror.lyngvi.gui.Services;
import no.syntakserror.lyngvi.gui.viewmodel.AgentsViewModel;
import no.syntakserror.lyngvi.gui.viewmodel.ConfigViewModel;

/**
 * «Local models»: the address of a model server on the developer's own machine and the models the agent may use
 * from it. The models are declared, not discovered — so the dialog can ask the server itself: «Test» marks every
 * listed model as available or missing, and «Add from server» lists the ones the server has and the user has not.
 * The result is the configuration as it was saved, and absent when the dialog was cancelled.
 */
public final class LocalModelsDialog extends Dialog<LocalModelConfig.Settings> {

    /**
     * Opens the dialog for one agent and stars what it saved. The Agents page and the first-run wizard both set a
     * server up, and a model you configured by hand is one you mean to use wherever you did it: the star is what
     * puts it in front of «New conversation» and the composer, which otherwise offer the head of a catalogue of
     * hundreds. {@code saved} runs only when the dialog saved — the listing it refreshes has a new count to show.
     */
    public static void open(Window owner, ResourceBundle b, Services s, String agentId, Runnable saved) {
        s.agents().localModels(agentId, state -> new LocalModelsDialog(owner, b, s.agents(), s.config(), agentId, state)
                .showAndWait().ifPresent(settings -> {
                    s.settings().setFavouriteModels(agentId, LocalModelChoices.star(s.settings().favouriteModels(agentId),
                            settings.provider(), state.settings().models(), settings.models()));
                    s.settings().save();
                    s.log().info(s.messages().get("gui.localmodels.saved"));
                    saved.run();
                }));
    }

    private final ResourceBundle b;
    private final AgentsViewModel agents;
    private final ConfigViewModel config;
    private final String agentId;

    private final TextField url = new TextField();
    private final TextField newModel = new TextField();
    private final PasswordField apiKey = new PasswordField();
    private final Label keyState = new Label();
    private final ListView<LocalModelChoices.Row> list = new ListView<>();
    private final Label status = new Label();
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Button wayOut = new Button();

    private List<String> models;
    private LocalModelConfig.Probe probe;
    private LocalModelConfig.WayOut offered = LocalModelConfig.WayOut.NONE;
    private boolean removeKey;

    public LocalModelsDialog(Window owner, ResourceBundle b, AgentsViewModel agents, ConfigViewModel config,
                             String agentId, LocalModelConfig.State state) {
        LocalModelConfig.Settings settings = state.settings();
        this.b = b;
        this.agents = agents;
        this.config = config;
        this.agentId = agentId;
        // What the proxy allows decides what a denial can be undone with, so the list is current when it happens.
        config.refreshDomains();
        this.models = new ArrayList<>(settings.models());
        initOwner(owner);
        setTitle(b.getString("gui.localmodels.title") + " — " + agentId);
        setHeaderText(MessageFormat.format(b.getString("gui.localmodels.header"), agentId, settings.provider()));
        ButtonType save = new ButtonType(b.getString("gui.localmodels.save"), ButtonType.OK.getButtonData());
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        getDialogPane().setPrefSize(620, 520);

        url.setText(settings.baseUrl());
        url.setPromptText(b.getString("gui.localmodels.url.prompt"));
        // The answer belongs to the address it was given for; changing the address makes the marks stale.
        url.textProperty().addListener((o, a, v) -> {
            probe = null;
            status.setText("");
            render();
        });
        HBox address = new HBox(8, new Label(b.getString("gui.localmodels.url")), url);
        address.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(url, Priority.ALWAYS);

        // The key is never read back out of the sandbox: an empty field keeps whatever is stored.
        boolean keyPossible = !state.apiKeyEnv().isBlank();
        apiKey.setPromptText(b.getString(state.apiKeySet() ? "gui.localmodels.key.stored.prompt" : "gui.localmodels.key.prompt"));
        apiKey.setDisable(!keyPossible);
        keyState.getStyleClass().add("context-label");
        Button forgetKey = new Button(b.getString("gui.localmodels.key.remove"));
        forgetKey.setVisible(state.apiKeySet());
        forgetKey.setManaged(state.apiKeySet());
        forgetKey.setOnAction(e -> {
            removeKey = !removeKey;
            apiKey.clear();
            apiKey.setDisable(removeKey);
            keyState.setText(b.getString(removeKey ? "gui.localmodels.key.removing" : "gui.localmodels.key.stored"));
        });
        keyState.setText(!keyPossible ? b.getString("gui.localmodels.key.unsupported")
                : state.apiKeySet() ? MessageFormat.format(b.getString("gui.localmodels.key.stored.where"), state.apiKeyEnv())
                : MessageFormat.format(b.getString("gui.localmodels.key.none"), state.apiKeyEnv()));
        HBox key = new HBox(8, new Label(b.getString("gui.localmodels.key")), apiKey, forgetKey);
        key.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(apiKey, Priority.ALWAYS);

        Label help = new Label(b.getString("gui.localmodels.help"));
        help.setWrapText(true);
        help.getStyleClass().add("section-help");

        list.setCellFactory(v -> new RowCell());
        list.setPlaceholder(new Label(b.getString("gui.localmodels.empty")));
        VBox.setVgrow(list, Priority.ALWAYS);

        newModel.setPromptText(b.getString("gui.localmodels.new"));
        HBox.setHgrow(newModel, Priority.ALWAYS);
        // Enter in the field adds the model; without consuming it the dialog's default button would also save and close.
        newModel.setOnAction(e -> {
            addTyped();
            e.consume();
        });
        Button add = new Button(b.getString("gui.localmodels.add"));
        add.setOnAction(e -> addTyped());
        Button remove = new Button(b.getString("gui.localmodels.remove"));
        remove.setOnAction(e -> {
            LocalModelChoices.Row row = list.getSelectionModel().getSelectedItem();
            if (row != null) {
                models.remove(row.id());
                render();
            }
        });
        HBox adding = new HBox(8, newModel, add, remove);
        adding.setAlignment(Pos.CENTER_LEFT);

        Button test = new Button(b.getString("gui.localmodels.test"));
        test.setGraphic(Icon.of("server"));
        Button fetch = new Button(b.getString("gui.localmodels.fetch"));
        test.setOnAction(e -> ask(test, fetch, false));
        fetch.setOnAction(e -> ask(test, fetch, true));
        // Only after the proxy has denied the request: egress is the sandbox's policy, widened on purpose.
        wayOut.getStyleClass().add("primary");
        wayOut.setVisible(false);
        wayOut.setManaged(false);
        wayOut.setOnAction(e -> {
            switch (offered) {
                case OPEN_PORT -> openPort(test, fetch);
                case ALLOW_HOST -> allowHost(test, fetch);
                case NONE -> { }
            }
        });
        busy.setPrefSize(16, 16);
        busy.setVisible(false);
        busy.setManaged(false);
        status.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, test, fetch, wayOut, busy, spacer);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, address, key, keyState, help, list, adding, actions, status);
        content.setPadding(new Insets(10));
        getDialogPane().setContent(content);
        render();
        setOnShown(e -> newModel.requestFocus());

        // An address a container cannot use is caught here, so the dialog stays open with what the user typed.
        getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            LocalModelConfig.UrlProblem problem = LocalModelConfig.check(url.getText());
            if (problem != LocalModelConfig.UrlProblem.NONE) {
                status.setText(b.getString("gui.localmodels.problem." + problem.name().toLowerCase(java.util.Locale.ROOT)));
                status.getStyleClass().setAll("status-warn");
                e.consume();
                return;
            }
            askToAllowTheHost();
        });
        setResultConverter(bt -> {
            if (bt != save) return null;
            LocalModelConfig.Settings edited = new LocalModelConfig.Settings(settings.provider(), url.getText(), models);
            agents.saveLocalModels(agentId, edited, keyChange(), () -> {});
            return edited;
        });
    }

    /**
     * On the way out: a server whose host the proxy has never been told to let out is the next thing that would
     * fail — in the middle of an ask, where the answer is hardest to find — so the question is asked here, while
     * the address is still on screen. Only asked, though: declining saves the configuration all the same, and the
     * allowlist is one «Setup → Domains» away. Nothing is asked when the host is already allowed, when the server
     * is on this machine (the port is that door, and «Test» offers it) or when the address is not usable at all.
     */
    private void askToAllowTheHost() {
        if (wayOutOfADenial() != LocalModelConfig.WayOut.ALLOW_HOST) return;
        String host = LocalModelConfig.host(url.getText());
        Dialogs dialogs = new Dialogs(b, getDialogPane().getScene().getWindow());
        if (dialogs.confirm(MessageFormat.format(b.getString("gui.localmodels.allow.title"), host),
                MessageFormat.format(b.getString("gui.localmodels.allow.text"), host, agentId),
                b.getString("gui.localmodels.allow.button"))) {
            // The dialog is closing, so this one reports through the app's own error channel rather than the status line.
            config.addDomain(host, Optional.empty());
        }
    }

    /** The egress policy as it stands, against the address in the field: which door, if any, is still shut. */
    private LocalModelConfig.WayOut wayOutOfADenial() {
        return LocalModelConfig.wayOut(url.getText(), config.localModelHost(), config.localModelPorts(),
                config.domains().stream().map(DomainEntry::domain).toList());
    }

    /** What Save should do with the key: nothing (null), remove it (blank), or store what was typed. */
    private String keyChange() {
        if (removeKey) return "";
        return apiKey.getText().isBlank() ? null : apiKey.getText();
    }

    private void addTyped() {
        models = new ArrayList<>(LocalModelChoices.add(models, newModel.getText()));
        newModel.clear();
        render();
    }

    /** One trip to the server: «Test» only marks the listed models, «Add from server» also takes the ones missing. */
    private void ask(Button test, Button fetch, boolean addAll) {
        test.setDisable(true);
        fetch.setDisable(true);
        busy.setVisible(true);
        busy.setManaged(true);
        status.setText(b.getString("gui.localmodels.testing"));
        String asked = url.getText();
        // A key typed but not saved is what the user wants tested; an empty field means the stored one.
        agents.probeLocalModels(agentId, asked, removeKey ? "" : apiKey.getText().isBlank() ? null : apiKey.getText(), result -> {
            // an answer from the address the user has since edited away says nothing about the new one
            if (!asked.equals(url.getText())) {
                done(test, fetch);
                return;
            }
            probe = result;
            List<String> notListed = LocalModelChoices.notListed(models, result);
            if (addAll) {
                for (String id : notListed) models = new ArrayList<>(LocalModelChoices.add(models, id));
            }
            done(test, fetch);
            report(result, addAll ? notListed.size() : -1);
        }, t -> {
            probe = null;
            done(test, fetch);
            status.setText(t.getMessage() == null ? t.toString() : t.getMessage());
            status.getStyleClass().setAll("status-warn");
        });
    }

    private void done(Button test, Button fetch) {
        test.setDisable(false);
        fetch.setDisable(false);
        busy.setVisible(false);
        busy.setManaged(false);
        offerTheWayOut();
        render();
    }

    /**
     * A denial is the one failure the user can undo from here, so it is offered as an action, not as instructions —
     * and which action it is follows from the address: the port to this machine, or the host in the allowlist. When
     * the door the address needs is open already the denial was about something else, and nothing is offered.
     */
    private void offerTheWayOut() {
        offered = probe == null || probe.status() != 403 ? LocalModelConfig.WayOut.NONE : wayOutOfADenial();
        switch (offered) {
            case OPEN_PORT -> wayOut.setText(MessageFormat.format(b.getString("gui.localmodels.openport"),
                    LocalModelConfig.port(url.getText())));
            case ALLOW_HOST -> wayOut.setText(MessageFormat.format(b.getString("gui.localmodels.allowhost"),
                    LocalModelConfig.host(url.getText())));
            case NONE -> { }
        }
        boolean show = offered != LocalModelConfig.WayOut.NONE;
        wayOut.setVisible(show);
        wayOut.setManaged(show);
    }

    /** Opens the port in the profile, reloads the proxy, and tests again — the point was to reach the server. */
    private void openPort(Button test, Button fetch) {
        int port = LocalModelConfig.port(url.getText());
        if (port <= 0) return;
        working(MessageFormat.format(b.getString("gui.localmodels.opening"), port));
        config.openLocalModelPort(port, ports -> {
            wayOut.setDisable(false);
            ask(test, fetch, false);
        }, this::failed);
    }

    /**
     * Puts the server's host in the egress allowlist and tests again. The allowlist is the sandbox's own policy and
     * a model server out on the network is traffic the human has to want, so it is asked for first — and the answer
     * names the host, because that, and not the address, is what the proxy is told to let out.
     */
    private void allowHost(Button test, Button fetch) {
        String host = LocalModelConfig.host(url.getText());
        if (host.isEmpty()) return;
        // Owned by this dialog, which is modal: the question has to come up in front of the window that asked it.
        Dialogs dialogs = new Dialogs(b, getDialogPane().getScene().getWindow());
        if (!dialogs.confirm(MessageFormat.format(b.getString("gui.localmodels.allow.title"), host),
                MessageFormat.format(b.getString("gui.localmodels.allow.text"), host, agentId),
                b.getString("gui.localmodels.allow.button"))) {
            return;
        }
        working(MessageFormat.format(b.getString("gui.localmodels.allowing"), host));
        config.allowDomain(host, () -> {
            wayOut.setDisable(false);
            ask(test, fetch, false);
        }, this::failed);
    }

    private void working(String text) {
        wayOut.setDisable(true);
        busy.setVisible(true);
        busy.setManaged(true);
        status.setText(text);
        status.getStyleClass().setAll("context-label");
    }

    private void failed(Throwable t) {
        wayOut.setDisable(false);
        busy.setVisible(false);
        busy.setManaged(false);
        status.setText(t.getMessage() == null ? t.toString() : t.getMessage());
        status.getStyleClass().setAll("status-warn");
    }

    /** What the server said, and what it means for the models the user listed. */
    private void report(LocalModelConfig.Probe result, int added) {
        String text;
        boolean ok = result.reachable();
        if (!ok) {
            text = result.detail();
        } else if (added >= 0) {
            text = result.detail() + " — " + MessageFormat.format(b.getString("gui.localmodels.added"), added);
        } else {
            List<String> missing = result.missing(models);
            ok = missing.isEmpty() && !models.isEmpty();
            text = models.isEmpty() ? result.detail()
                    : missing.isEmpty() ? MessageFormat.format(b.getString("gui.localmodels.result.allok"), result.detail())
                    : MessageFormat.format(b.getString("gui.localmodels.result.missing"), result.detail(), String.join(", ", missing));
        }
        status.setText(text);
        status.getStyleClass().setAll(ok ? "status-done" : "status-warn");
    }

    private void render() {
        String selected = list.getSelectionModel().getSelectedItem() == null ? null : list.getSelectionModel().getSelectedItem().id();
        list.getItems().setAll(LocalModelChoices.rows(models, probe));
        list.getItems().stream().filter(r -> r.id().equals(selected)).findFirst()
                .ifPresent(r -> list.getSelectionModel().select(r));
    }

    /** A row: the model id as it goes on the wire, and what the last test said about it. */
    private final class RowCell extends ListCell<LocalModelChoices.Row> {

        private final Label id = new Label();
        private final Label state = new Label();
        private final HBox box;

        RowCell() {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box = new HBox(8, id, spacer, state);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(LocalModelChoices.Row item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            id.setText(item.id());
            state.setText(switch (item.state()) {
                case AVAILABLE -> b.getString("gui.localmodels.state.available");
                case MISSING -> b.getString("gui.localmodels.state.missing");
                case UNKNOWN -> "";
            });
            state.getStyleClass().setAll(switch (item.state()) {
                case AVAILABLE -> "status-done";
                case MISSING -> "status-warn";
                case UNKNOWN -> "context-label";
            });
            setGraphic(box);
        }
    }
}
