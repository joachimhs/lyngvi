package no.syntakserror.lyngvi.gui.view;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import no.syntakserror.lyngvi.core.contract.AgentInfo;
import no.syntakserror.lyngvi.core.doctor.Check;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.gui.Services;
import no.syntakserror.lyngvi.gui.viewmodel.Errors;

/**
 * «New sandbox…» as a wizard: it creates the sandbox, builds the base image, starts the shared stack, runs the
 * checks and offers the agent login — the first run the README describes, one page at a time — so what it leaves
 * behind is a sandbox a conversation can be started in rather than an empty folder.
 *
 * <p>One page is shown at a time with one forward button, which runs the page's own work while it has any and
 * moves on once it is done ({@link WizardFlow}). A page cannot be left while its work runs, so a call that comes
 * back always finds the page that started it on screen. Optional pages (the checks and the login) can be passed
 * by: the sandbox exists either way, and both are reachable afterwards from «Sandbox» and «Agents».
 */
public final class SandboxWizard extends Dialog<SandboxWizard.Result> {

    /** What the finished wizard leaves to the window: the sandbox it made, and whether to open a conversation in it. */
    public record Result(Path dir, boolean newConversation) {}

    private final Services s;
    private final ResourceBundle b;
    private final Messages m;
    private final Window owner;
    private final WizardFlow flow;
    private final List<Page> pages;
    private final DonePage donePage = new DonePage();

    private final Label countLabel = new Label();
    private final Label titleLabel = new Label();
    private final Label explanationLabel = new Label();
    private final VBox body = new VBox();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label statusLabel = new Label();
    private final VBox rail = new VBox(2);
    private final ButtonType backType = new ButtonType("", ButtonBar.ButtonData.BACK_PREVIOUS);
    private final ButtonType forwardType = new ButtonType("", ButtonBar.ButtonData.NEXT_FORWARD);
    private Button backButton;
    private Button forwardButton;
    private Button cancelButton;
    private Path created;

    public SandboxWizard(Window owner, ResourceBundle b, Messages m, Services s) {
        this.owner = owner;
        this.b = b;
        this.m = m;
        this.s = s;
        this.pages = List.of(new FolderPage(), new BuildPage(), new StartPage(), new CheckPage(), new AgentPage(), donePage);
        this.flow = new WizardFlow(pages.stream().map(p -> new WizardFlow.Step(p.key, p.optional())).toList());
        initOwner(owner);
        setTitle(b.getString("gui.wizard.title"));
        setResizable(true);
        getDialogPane().getButtonTypes().addAll(backType, forwardType, ButtonType.CANCEL);
        backButton = (Button) getDialogPane().lookupButton(backType);
        forwardButton = (Button) getDialogPane().lookupButton(forwardType);
        cancelButton = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        backButton.setText(b.getString("gui.wizard.back"));
        forwardButton.getStyleClass().add("primary");
        forwardButton.setDefaultButton(true);
        // The buttons steer the wizard rather than close it: only the last press, the one that finishes, reaches the dialog.
        backButton.addEventFilter(ActionEvent.ACTION, e -> { e.consume(); onBack(); });
        forwardButton.addEventFilter(ActionEvent.ACTION, e -> { if (!onForward()) e.consume(); });
        getDialogPane().setContent(buildContent());
        getDialogPane().setPrefWidth(780);
        // Cancel (and the window's close button) leaves whatever the wizard has already made behind, open in the window.
        setResultConverter(bt -> bt == forwardType && created != null ? new Result(created, donePage.openConversation()) : null);
        show(0);
    }

    // ---- frame ----

    private Node buildContent() {
        rail.getStyleClass().add("wizard-rail");
        rail.setPadding(new Insets(14, 16, 14, 4));
        for (int i = 0; i < pages.size(); i++) rail.getChildren().add(railRow(i));
        countLabel.getStyleClass().add("wizard-count");
        titleLabel.getStyleClass().add("section-title");
        explanationLabel.getStyleClass().add("section-help");
        explanationLabel.setWrapText(true);
        explanationLabel.setMinHeight(Region.USE_PREF_SIZE);
        VBox.setVgrow(body, Priority.ALWAYS);
        body.setFillWidth(true);
        spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        spinner.setPrefSize(16, 16);
        spinner.setMinSize(16, 16);
        statusLabel.setWrapText(true);
        HBox status = new HBox(8, spinner, statusLabel);
        status.setAlignment(Pos.CENTER_LEFT);
        status.setMinHeight(28);
        VBox centre = new VBox(8, countLabel, titleLabel, explanationLabel, body, status);
        centre.setPadding(new Insets(14, 4, 4, 14));
        centre.setPrefSize(560, 380);
        BorderPane root = new BorderPane(centre);
        root.setLeft(rail);
        return root;
    }

    /** One rail row: the step's mark and its title, both restyled by {@link #update()} as the wizard moves. */
    private Node railRow(int step) {
        Label mark = new Label();
        mark.getStyleClass().add("wizard-mark");
        Label title = new Label(b.getString("gui.wizard." + pages.get(step).key + ".title"));
        title.getStyleClass().add("wizard-step-title");
        title.setWrapText(true);
        title.setMaxWidth(150);
        HBox row = new HBox(8, mark, title);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("wizard-rail-row");
        return row;
    }

    private Page page() {
        return pages.get(flow.index());
    }

    /** Shows a step: its texts, its content, and the work it starts by itself the first time it is seen. */
    private void show(int step) {
        Page page = pages.get(step);
        countLabel.setText(m.get("gui.wizard.step", step + 1, pages.size()));
        titleLabel.setText(b.getString("gui.wizard." + page.key + ".title"));
        explanationLabel.setText(b.getString("gui.wizard." + page.key + ".text"));
        body.getChildren().setAll(page.content());
        say("", null);
        page.shown();
        if (page.autoStart() && flow.state() == WizardFlow.State.PENDING) page.start();
        update();
    }

    /** Brings rail, buttons and spinner in line with where the wizard is and what the step's work has done. */
    private void update() {
        for (int i = 0; i < pages.size(); i++) {
            HBox row = (HBox) rail.getChildren().get(i);
            Label mark = (Label) row.getChildren().get(0);
            Label title = (Label) row.getChildren().get(1);
            mark.setText(flow.marker(i));
            mark.getStyleClass().removeAll("wizard-mark-done", "wizard-mark-failed", "wizard-mark-current");
            switch (flow.state(i)) {
                case DONE -> mark.getStyleClass().add("wizard-mark-done");
                case FAILED -> mark.getStyleClass().add("wizard-mark-failed");
                default -> { if (i == flow.index()) mark.getStyleClass().add("wizard-mark-current"); }
            }
            row.getStyleClass().remove("wizard-rail-row-current");
            title.getStyleClass().remove("wizard-step-title-current");
            if (i == flow.index()) {
                row.getStyleClass().add("wizard-rail-row-current");
                title.getStyleClass().add("wizard-step-title-current");
            }
        }
        backButton.setDisable(!flow.canBack());
        relabel(forwardButton, b.getString(flow.forwardKey(page().actionKey())));
        forwardButton.setDisable(!flow.canForward(page().ready()));
        // Leaving now would keep the sandbox the wizard has already made, so the way out stops being «Cancel».
        relabel(cancelButton, b.getString(created == null ? "gui.button.cancel" : "gui.button.close"));
        boolean running = flow.state() == WizardFlow.State.RUNNING;
        spinner.setVisible(running);
        spinner.setManaged(running);
    }

    /**
     * The forward button changes its label with every step. The dialog's button bar gives all its buttons one
     * width and holds on to it, so a longer label would be cut short («Try again» as «Try a…») unless the button
     * is handed back to the bar to be measured again.
     */
    private static void relabel(Button button, String text) {
        button.setText(text);
        button.setPrefWidth(Region.USE_COMPUTED_SIZE);
    }

    private void onBack() {
        if (!flow.canBack()) return;
        flow.back();
        show(flow.index());
    }

    /** The forward button; true when the press is the one that finishes the wizard and may close the dialog. */
    private boolean onForward() {
        if (!flow.canForward(page().ready())) return false;
        if (flow.forward() == WizardFlow.Forward.RUN) {
            page().start();
            return false;
        }
        if (flow.last()) return true;
        flow.advance();
        show(flow.index());
        return false;
    }

    // ---- what a step reports back ----

    private void running(String status) {
        flow.setState(WizardFlow.State.RUNNING);
        say(status, null);
    }

    private void done(String status) {
        flow.setState(WizardFlow.State.DONE);
        say(status, "status-done");
    }

    /** A step that did not get there: the page stays where it is, the console keeps the detail, the rail marks it. */
    private void failed(Throwable t) {
        Errors.UiError e = Errors.map(t, m);
        s.log().error(e.message(), null);
        if (e.details() != null && !e.details().isBlank()) s.log().error(e.details(), null);
        failed(e.message());
    }

    private void failed(String status) {
        flow.setState(WizardFlow.State.FAILED);
        say(status, "status-error");
    }

    /** The line under the page, in the colour of what it reports, and everything the new state changes with it. */
    private void say(String status, String styleClass) {
        statusLabel.getStyleClass().removeAll("status-done", "status-error");
        if (styleClass != null) statusLabel.getStyleClass().add(styleClass);
        statusLabel.setText(status);
        update();
    }

    // ---- the pages ----

    /** One page: its content, the work behind it, and what the forward button makes of both. */
    private abstract class Page {

        final String key;
        private Node content;

        Page(String key) {
            this.key = key;
        }

        Node content() {
            if (content == null) content = build();
            return content;
        }

        abstract Node build();

        /** True when the wizard may be left behind without this step. */
        boolean optional() {
            return false;
        }

        /** What the forward button reads while the page still has work to do; null: «Next», the page has none. */
        String actionKey() {
            return null;
        }

        /** True when the page's own input is complete, so its work may start. */
        boolean ready() {
            return true;
        }

        /** True when the work starts by itself the first time the page is shown. */
        boolean autoStart() {
            return false;
        }

        /** Called every time the page is shown, before any work starts. */
        void shown() {
        }

        /** Starts the page's work; a page with none is done as soon as it is asked. */
        void start() {
            done("");
        }
    }

    /** Where the sandbox goes, which profile pack it uses and which repositories it may copy into a conversation. */
    private final class FolderPage extends Page {

        private final TextField dir = new TextField();
        private final TextField profile = new TextField();
        private final ListView<String> repos = new ListView<>();
        private final GridPane form = new GridPane();

        FolderPage() {
            super("folder");
        }

        @Override
        Node build() {
            dir.textProperty().addListener((o, a, v) -> update());
            Button chooseDir = new Button(b.getString("gui.button.browse"));
            chooseDir.setOnAction(e -> choose(b.getString("gui.sandbox.new.dir")).ifPresent(f -> dir.setText(f.toString())));
            profile.setPromptText(b.getString("gui.sandbox.new.profile.prompt"));
            Button chooseProfile = new Button(b.getString("gui.button.browse"));
            chooseProfile.setOnAction(e -> choose(b.getString("gui.sandbox.new.profile")).ifPresent(f -> profile.setText(f.toString())));
            repos.setPrefHeight(120);
            Button addRepo = new Button(b.getString("gui.repos.add"));
            addRepo.setOnAction(e -> choose(b.getString("gui.sandbox.new.repos")).ifPresent(f -> repos.getItems().add(f.toString())));
            Button removeRepo = new Button(b.getString("gui.button.remove.selected"));
            removeRepo.disableProperty().bind(repos.getSelectionModel().selectedItemProperty().isNull());
            removeRepo.setOnAction(e -> repos.getItems().remove(repos.getSelectionModel().getSelectedItem()));
            HBox.setHgrow(dir, Priority.ALWAYS);
            HBox.setHgrow(profile, Priority.ALWAYS);
            form.setHgap(8);
            form.setVgap(8);
            ColumnConstraints label = new ColumnConstraints();
            ColumnConstraints field = new ColumnConstraints();
            field.setHgrow(Priority.ALWAYS);   // the fields take the width the page has, the labels only what they need
            form.getColumnConstraints().addAll(label, field);
            Label reposLabel = new Label(b.getString("gui.sandbox.new.repos"));
            GridPane.setValignment(reposLabel, VPos.TOP);
            form.addRow(0, new Label(b.getString("gui.sandbox.new.dir")), new HBox(6, dir, chooseDir));
            form.addRow(1, new Label(b.getString("gui.sandbox.new.profile")), new HBox(6, profile, chooseProfile));
            form.addRow(2, reposLabel, new VBox(6, repos, new HBox(6, addRepo, removeRepo)));
            return form;
        }

        private Optional<Path> choose(String title) {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle(title);
            java.io.File f = dc.showDialog(owner);
            return Optional.ofNullable(f).map(java.io.File::toPath);
        }

        @Override
        String actionKey() {
            return "gui.wizard.folder.action";
        }

        @Override
        boolean ready() {
            return !dir.getText().isBlank();
        }

        @Override
        void start() {
            Path target = Path.of(dir.getText().strip());
            Optional<Path> pack = profile.getText().isBlank() ? Optional.empty() : Optional.of(Path.of(profile.getText().strip()));
            List<Path> chosen = new ArrayList<>();
            for (String r : repos.getItems()) chosen.add(Path.of(r));
            running(m.get("gui.wizard.folder.running"));
            s.async().call(() -> s.ctx().init(target, pack, chosen, Optional.empty()), report -> {
                created = Path.of(report.sandboxDir());
                form.setDisable(true); // the sandbox exists now; the fields describe what was made, not what to make
                // init writes the sandbox's files but renders nothing: the Containerfile, the compose file and the
                // proxy configuration a build needs come from rebuild-config, which the step is not done without.
                s.stack().rebuildConfig(r -> done(m.get("init.done", report.sandboxDir())), SandboxWizard.this::failed);
            }, SandboxWizard.this::failed);
        }
    }

    /** The base image the conversations run in: the first build is long, so its output is shown while it runs. */
    private final class BuildPage extends Page {

        /** How much of the output the page keeps. A long build is mostly downloads; what matters is the tail. */
        private static final int TAIL = 40_000;

        private final TextArea output = new TextArea();

        BuildPage() {
            super("build");
        }

        @Override
        Node build() {
            output.setEditable(false);
            output.getStyleClass().add("wizard-output");
            output.setPrefRowCount(12);
            VBox.setVgrow(output, Priority.ALWAYS);
            return output;
        }

        @Override
        String actionKey() {
            return "gui.wizard.build.action";
        }

        @Override
        boolean autoStart() {
            return true;
        }

        @Override
        void start() {
            output.clear();
            running(m.get("gui.wizard.build.running"));
            s.stack().build(false, this::append, () -> done(m.get("gui.wizard.build.done")), SandboxWizard.this::failed);
        }

        private void append(String line) {
            output.appendText(line + "\n");
            int over = output.getLength() - TAIL;
            if (over <= 0) return;
            int lineEnd = output.getText(0, Math.min(over + 500, output.getLength())).indexOf('\n', over);
            output.deleteText(0, lineEnd < 0 ? over : lineEnd + 1);
        }
    }

    /** The shared stack: the proxy every container reaches the network through, and the gateway. */
    private final class StartPage extends Page {

        private final ListView<String> services = new ListView<>();

        StartPage() {
            super("start");
        }

        @Override
        Node build() {
            services.setPrefHeight(140);
            services.setPlaceholder(new Label(""));
            VBox.setVgrow(services, Priority.ALWAYS);
            return services;
        }

        @Override
        String actionKey() {
            return "gui.wizard.start.action";
        }

        @Override
        boolean autoStart() {
            return true;
        }

        @Override
        void start() {
            running(m.get("gui.wizard.start.running"));
            s.stack().up(status -> {
                services.getItems().setAll(status.services().stream()
                        .map(x -> x.name() + "  —  " + (x.state().isEmpty() ? m.get("stack.absent") : x.state())
                                + (x.health() == null || x.health().isBlank() ? "" : "  (" + x.health() + ")"))
                        .toList());
                done(m.get("gui.wizard.start.done"));
            }, SandboxWizard.this::failed);
        }
    }

    /** The same checks as «Doctor». A warning is worth reading but is not a reason to stop, so the step may be passed. */
    private final class CheckPage extends Page {

        private final ListView<String> checks = new ListView<>();

        CheckPage() {
            super("check");
        }

        @Override
        Node build() {
            checks.setPrefHeight(160);
            checks.setPlaceholder(new Label(""));
            VBox.setVgrow(checks, Priority.ALWAYS);
            Button again = new Button(b.getString("gui.wizard.check.again"));
            again.setOnAction(e -> start());
            // The step is optional, so the forward button always reads «Next»: running the checks again is this one's job.
            VBox box = new VBox(8, checks, again);
            VBox.setVgrow(box, Priority.ALWAYS);
            return box;
        }

        @Override
        boolean optional() {
            return true;
        }

        @Override
        String actionKey() {
            return "gui.wizard.check.action";
        }

        @Override
        boolean autoStart() {
            return true;
        }

        @Override
        void start() {
            checks.getItems().clear();
            running(m.get("gui.wizard.check.running"));
            s.stack().doctor(report -> {
                checks.getItems().setAll(report.checks().stream().map(CheckPage::line).toList());
                String summary = m.get("doctor.summary", report.ok(), report.warn(), report.fail());
                if (report.healthy()) done(summary); else failed(summary);
            }, SandboxWizard.this::failed);
        }

        private static String line(Check c) {
            String mark = switch (c.level()) {
                case OK -> "✓";
                case WARN -> "!";
                case FAIL -> "✗";
            };
            return mark + "  " + c.id() + ": " + c.message() + (c.hint() == null || c.hint().isBlank() ? "" : "  (" + c.hint() + ")");
        }
    }

    /**
     * The agent that answers in the conversations. There are two ways to get one answering and the step offers
     * both: log in once per sandbox, or point the agent at a model server of your own — LM Studio, Ollama, vLLM —
     * which needs no login at all. Either can be done later instead, under «Agents».
     */
    private final class AgentPage extends Page {

        private final ListView<AgentInfo> agents = new ListView<>();

        AgentPage() {
            super("agent");
        }

        @Override
        Node build() {
            agents.setItems(s.agents().agents());
            agents.setPrefHeight(140);
            agents.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
                @Override
                protected void updateItem(AgentInfo item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item.label() + "  —  " + status(item));
                }
            });
            Button login = new Button(b.getString("gui.wizard.agent.login"));
            login.getStyleClass().add("primary");
            login.disableProperty().bind(agents.getSelectionModel().selectedItemProperty().isNull());
            login.setOnAction(e -> login(agents.getSelectionModel().getSelectedItem()));
            // The other way to an answer, offered here rather than only on the Agents page: a server of your own
            // is a first run just as much as a login is, and it is the one that needs no account anywhere.
            Button localModels = new Button(b.getString("gui.agents.localmodels"));
            localModels.setGraphic(Icon.of("server"));
            localModels.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                    () -> AgentChoices.ownModels(agents.getSelectionModel().getSelectedItem()) == AgentChoices.OwnModels.UNSUPPORTED,
                    agents.getSelectionModel().selectedItemProperty()));
            localModels.setOnAction(e -> localModels(agents.getSelectionModel().getSelectedItem()));
            Label help = new Label(b.getString("gui.wizard.agent.localmodels.help"));
            help.getStyleClass().add("section-help");
            help.setWrapText(true);
            // The list is the viewmodel's own, so a login anywhere — this dialog's or the Agents page's — updates the step.
            s.agents().agents().addListener((ListChangeListener<AgentInfo>) c -> read());
            HBox buttons = new HBox(8, login, localModels);
            buttons.setAlignment(Pos.CENTER_LEFT);
            VBox box = new VBox(8, agents, buttons, help);
            VBox.setVgrow(agents, Priority.ALWAYS);
            VBox.setVgrow(box, Priority.ALWAYS);
            return box;
        }

        private String status(AgentInfo a) {
            if (!a.available()) return m.get("gui.agents.noadapter");
            if (!a.enabled()) return m.get("gui.agents.disabled");
            String login = a.loggedIn() ? m.get("gui.agents.loggedin") + (a.account() == null ? "" : " (" + a.account() + ")")
                    : m.get("gui.agents.notloggedin");
            // Not logged in says nothing about an agent that answers from a server of your own, so the row says both.
            return AgentChoices.ownModels(a) == AgentChoices.OwnModels.CONFIGURED
                    ? login + ", " + m.get("gui.agents.localmodels.count", a.localModels()) : login;
        }

        private void login(AgentInfo a) {
            if (a == null) return;
            List<String> modes = new ArrayList<>();
            try {
                modes.addAll(s.ctx().sandbox().agentService().adapter(a.id()).descriptor().authModes());
            } catch (RuntimeException ignored) {
                // no descriptor: the login dialog offers the default mode only
            }
            if (modes.isEmpty()) modes.add("subscription");
            new LoginDialog(getDialogPane().getScene().getWindow(), b, s.agents(), a.id(), modes).showAndWait();
            s.agents().refresh();
        }

        /** The same dialog the Agents page opens, so a server set up here is set up for good — and starred. */
        private void localModels(AgentInfo a) {
            if (AgentChoices.ownModels(a) == AgentChoices.OwnModels.UNSUPPORTED) return;
            LocalModelsDialog.open(getDialogPane().getScene().getWindow(), b, s, a.id(), () -> s.agents().refresh());
        }

        @Override
        boolean optional() {
            return true;
        }

        @Override
        void shown() {
            s.agents().refresh();
            read();
        }

        /**
         * The step is done when an agent can answer: that is the list's business, not a call of its own. The list
         * goes on changing after the wizard has moved past the step, so only the step on screen reads itself out of it.
         *
         * <p>Answering is not the same as being logged in. An agent with models from a server of your own answers
         * without an account anywhere, so it finishes the step just as a login does — the login is only looked for
         * first, because it is the one that says whose account is paying.
         */
        private void read() {
            if (!flow.current().key().equals(key)) return;
            Optional<AgentInfo> in = s.agents().agents().stream().filter(AgentInfo::loggedIn).findFirst();
            Optional<AgentInfo> own = s.agents().agents().stream().filter(a -> a.localModels() > 0).findFirst();
            if (in.isPresent()) {
                done(m.get("gui.wizard.agent.loggedin", in.get().label()));
            } else if (own.isPresent()) {
                done(m.get("gui.wizard.agent.localmodels", own.get().label(), own.get().localModels()));
            } else {
                flow.setState(WizardFlow.State.PENDING);
                say(b.getString("gui.wizard.agent.none"), null);
            }
        }
    }

    /** What the wizard made, what it skipped, and the conversation it can open on the way out. */
    private final class DonePage extends Page {

        private final CheckBox newConversation = new CheckBox();
        private final Label summary = new Label();
        private final Label skipped = new Label();

        DonePage() {
            super("done");
        }

        @Override
        Node build() {
            newConversation.setText(b.getString("gui.wizard.done.conversation"));
            newConversation.setSelected(true);
            summary.getStyleClass().add("sandbox-dir");
            summary.setWrapText(true);
            skipped.getStyleClass().add("section-help");
            skipped.setWrapText(true);
            return new VBox(10, summary, skipped, newConversation);
        }

        boolean openConversation() {
            return newConversation.isSelected();
        }

        @Override
        boolean autoStart() {
            return true;
        }

        @Override
        void shown() {
            summary.setText(created == null ? "" : created.toString());
            List<String> left = new ArrayList<>();
            for (int i = 0; i < pages.size() - 1; i++) {
                if (flow.state(i) != WizardFlow.State.DONE) left.add(b.getString("gui.wizard." + pages.get(i).key + ".title"));
            }
            skipped.setText(left.isEmpty() ? "" : m.get("gui.wizard.done.skipped", String.join(", ", left)));
            skipped.setVisible(!left.isEmpty());
            skipped.setManaged(!left.isEmpty());
        }
    }
}
