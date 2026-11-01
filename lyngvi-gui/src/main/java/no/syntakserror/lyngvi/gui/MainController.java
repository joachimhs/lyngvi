package no.syntakserror.lyngvi.gui;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.config.ResourcePreset;
import no.syntakserror.lyngvi.core.contract.AgentInfo;
import no.syntakserror.lyngvi.core.contract.GatewayInstanceInfo;
import no.syntakserror.lyngvi.core.contract.SkillInfo;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.stack.BlockedRequest;
import no.syntakserror.lyngvi.core.stack.DomainEntry;
import no.syntakserror.lyngvi.core.stack.ResourceAdvice;
import no.syntakserror.lyngvi.core.stack.ResourceFit;
import no.syntakserror.lyngvi.core.contract.SessionContainerInfo;
import javafx.scene.control.TableRow;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import no.syntakserror.lyngvi.core.stack.StackStatus;
import no.syntakserror.lyngvi.core.version.Version;
import no.syntakserror.lyngvi.gui.view.AddSystemDialog;
import no.syntakserror.lyngvi.gui.view.AgentChoices;
import no.syntakserror.lyngvi.gui.view.ChangelogDialog;
import no.syntakserror.lyngvi.gui.view.ChatController;
import no.syntakserror.lyngvi.gui.view.ConsoleView;
import no.syntakserror.lyngvi.gui.view.Dialogs;
import no.syntakserror.lyngvi.gui.view.FilesController;
import no.syntakserror.lyngvi.gui.view.GatewayPane;
import no.syntakserror.lyngvi.gui.view.CodeView;
import no.syntakserror.lyngvi.gui.view.Icon;
import no.syntakserror.lyngvi.gui.view.Languages;
import no.syntakserror.lyngvi.gui.view.Syntax;
import no.syntakserror.lyngvi.gui.view.LocalModelsDialog;
import no.syntakserror.lyngvi.gui.view.LoginDialog;
import no.syntakserror.lyngvi.gui.view.PresetChoices;
import no.syntakserror.lyngvi.gui.view.SandboxChoices;
import no.syntakserror.lyngvi.gui.view.SandboxWizard;
import no.syntakserror.lyngvi.gui.view.SandboxLabel;
import no.syntakserror.lyngvi.gui.view.Theme;
import no.syntakserror.lyngvi.gui.view.UpgradeDialog;
import no.syntakserror.lyngvi.gui.view.UsagePane;
import no.syntakserror.lyngvi.gui.view.WelcomeDialog;
import no.syntakserror.lyngvi.gui.viewmodel.Changelog;
import no.syntakserror.lyngvi.gui.viewmodel.Errors;
import no.syntakserror.lyngvi.gui.viewmodel.ProblemReport;
import no.syntakserror.lyngvi.gui.viewmodel.Settings;

/**
 * The main window: toolbar, nav rail, tabs, console (spec §9.3). Every action calls a viewmodel. The files of
 * the conversation it has open are a window of their own, {@link FilesWindow}, built and toggled from here.
 */
public final class MainController {

    private final Services s;
    private final Stage stage;
    private final ResourceBundle b;
    private final Messages m;
    private Dialogs dialogs;
    private ConsoleView console;
    private Timeline containerPoll;
    /** Built with the window, shown when «Files» is pressed; the pane itself is unchanged by living there. */
    private FilesWindow filesWindow;
    private FilesController filesController;

    // toolbar
    /** The open sandbox's path, and the drop-down that switches to another one or opens it in its own window. */
    @FXML private MenuButton sandboxSwitcher;
    @FXML private Button versionButton;
    /** The hint shown instead of a status message while no sandbox is open; clicking it opens the folder chooser. */
    @FXML private Hyperlink chooseSandboxLink;
    @FXML private Label statusLabel;
    @FXML private MenuButton sandboxMenu;
    @FXML private ToggleButton filesToggle;
    @FXML private Button upgradeButton;
    @FXML private MenuButton helpMenu;
    @FXML private Button themeToggleButton;
    // nav rail
    @FXML private ToggleButton navSandbox;
    @FXML private ToggleButton navEgress;
    @FXML private ToggleButton navChat;
    @FXML private ProgressIndicator navChatSpinner;
    @FXML private Label navChatDoneBadge;
    @FXML private Label navChatErrorBadge;
    @FXML private ToggleButton navAsk;
    @FXML private ToggleButton navSetup;
    @FXML private ToggleButton navGateway;
    @FXML private ToggleButton navAgents;
    @FXML private ToggleButton navSkills;
    @FXML private ToggleButton navSyntax;
    // content
    @FXML private SplitPane contentSplit;
    @FXML private TabPane mainTabs;
    @FXML private Tab sandboxTab;
    @FXML private Tab egressTab;
    @FXML private Tab chatTab;
    @FXML private Tab askTab;
    @FXML private Tab setupTab;
    @FXML private Tab gatewayTab;
    @FXML private Tab agentsTab;
    @FXML private Tab skillsTab;
    @FXML private Tab syntaxTab;
    @FXML private ChatController chatController;
    @FXML private ChatController askController;
    @FXML private VBox consoleHolder;
    @FXML private CheckBox onlyImportantCheck;
    // sandbox: run
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private TableView<StackStatus.Service> containerTable;
    @FXML private TableColumn<StackStatus.Service, String> containerServiceColumn;
    @FXML private TableColumn<StackStatus.Service, String> containerNameColumn;
    @FXML private TableColumn<StackStatus.Service, String> containerStateColumn;
    @FXML private TableColumn<StackStatus.Service, String> containerHealthColumn;
    @FXML private TableView<SessionContainerInfo> sessionContainerTable;
    @FXML private TableColumn<SessionContainerInfo, String> sessionConversationColumn;
    @FXML private TableColumn<SessionContainerInfo, String> sessionContainerColumn;
    @FXML private TableColumn<SessionContainerInfo, String> sessionStateColumn;
    @FXML private TableColumn<SessionContainerInfo, String> sessionImageColumn;
    private Timeline cleanupTimer;
    @FXML private Button followProxyLogButton;
    @FXML private Button followGatewayLogButton;
    @FXML private Button stopLogButton;
    // sandbox: resources
    @FXML private ComboBox<ResourcePreset> resourcePresetChoice;
    @FXML private TextField memLimitField;
    @FXML private TextField memswapLimitField;
    @FXML private TextField cpusField;
    @FXML private TextField pidsLimitField;
    @FXML private Label machineInfoLabel;
    @FXML private Label machineWarningLabel;
    /** True while picker and fields are being brought in line with each other, so neither counts as a change by the user. */
    private boolean showingPreset;
    // traffic
    @FXML private TableView<BlockedRequest> blockedTable;
    @FXML private TableColumn<BlockedRequest, String> blockedHostColumn;
    @FXML private TableColumn<BlockedRequest, String> blockedStatusColumn;
    @FXML private TableColumn<BlockedRequest, String> blockedCountColumn;
    @FXML private Label blockedStatusLabel;
    @FXML private Button allowBlockedButton;
    @FXML private Button followBlockedButton;
    @FXML private Button stopBlockedButton;
    // repos
    @FXML private TableView<no.syntakserror.lyngvi.core.config.RepoEntry> repoTable;
    @FXML private TableColumn<no.syntakserror.lyngvi.core.config.RepoEntry, String> repoPathColumn;
    @FXML private TableColumn<no.syntakserror.lyngvi.core.config.RepoEntry, String> repoMountColumn;
    @FXML private TableColumn<no.syntakserror.lyngvi.core.config.RepoEntry, String> repoHistoryColumn;
    @FXML private Button removeRepoButton;
    // domains
    @FXML private TableView<DomainEntry> domainTable;
    @FXML private TableColumn<DomainEntry, String> domainColumn;
    @FXML private TableColumn<DomainEntry, String> domainSourceColumn;
    @FXML private TableColumn<DomainEntry, String> domainNoteColumn;
    @FXML private TextField domainInput;
    @FXML private Button removeDomainButton;
    // profile / toolchain
    @FXML private Label profileNameLabel;
    @FXML private Label profilePackLabel;
    @FXML private Label profileVersionLabel;
    @FXML private ListView<String> protectedBranchesList;
    @FXML private TextField protectedBranchInput;
    @FXML private Button removeProtectedBranchButton;
    @FXML private VBox toolchainBox;
    @FXML private Button rebuildImageButton;
    @FXML private ProgressIndicator toolchainProgress;
    @FXML private Label toolchainStatusLabel;
    // gateway
    @FXML private TabPane gatewayTabs;
    @FXML private Button removeSystemButton;
    @FXML private Label gatewayStatusLabel;
    @FXML private Label gatewayCertLabel;
    @FXML private ListView<String> pluginList;
    // agents
    @FXML private TableView<AgentInfo> agentTable;
    @FXML private TableColumn<AgentInfo, String> agentIdColumn;
    @FXML private TableColumn<AgentInfo, String> agentLabelColumn;
    @FXML private TableColumn<AgentInfo, String> agentStatusColumn;
    @FXML private TableColumn<AgentInfo, String> agentAccountColumn;
    @FXML private TableColumn<AgentInfo, String> agentModelsColumn;
    @FXML private Button loginButton;
    @FXML private Button logoutButton;
    @FXML private Button localModelsButton;
    @FXML private TabPane agentsTabs;
    // skills
    @FXML private Label skillsAgentsLabel;
    @FXML private TableView<SkillInfo> skillTable;
    @FXML private TableColumn<SkillInfo, SkillInfo> skillEnabledColumn;
    @FXML private TableColumn<SkillInfo, String> skillNameColumn;
    @FXML private TableColumn<SkillInfo, String> skillDescriptionColumn;
    @FXML private Button removeSkillButton;
    @FXML private Label skillNameLabel;
    @FXML private Label skillStatusLabel;
    @FXML private Label skillProblemLabel;
    @FXML private Button skillSaveButton;
    @FXML private Button skillDiscardButton;
    @FXML private TextArea skillEditor;
    /** The skill in the editor, the text it was loaded with, and whether the user has changed it since. */
    private String editingSkill;
    private String editingOriginal = "";
    private boolean skillDirty;
    /** True while the selection is being put back after a discard was declined, so the listener skips that round. */
    private boolean restoringSkillSelection;
    // syntax colours
    @FXML private Label syntaxDirLabel;
    @FXML private TableView<Languages.Entry> languageTable;
    @FXML private TableColumn<Languages.Entry, String> languageNameColumn;
    @FXML private TableColumn<Languages.Entry, String> languageFilesColumn;
    @FXML private TableColumn<Languages.Entry, String> languageSourceColumn;
    @FXML private Button removeLanguageButton;
    @FXML private Label languageNameLabel;
    @FXML private Label languageStatusLabel;
    @FXML private Label languageProblemLabel;
    @FXML private Button languageSaveButton;
    @FXML private Button languageDiscardButton;
    @FXML private TextArea languageEditor;
    @FXML private StackPane languagePreviewBox;
    /** The language in the editor, the text it was loaded with, and whether the user has changed it since. */
    private String editingLanguage;
    private String editingLanguageOriginal = "";
    private boolean languageDirty;
    private boolean restoringLanguageSelection;
    private CodeView languagePreview;

    // agents: usage
    @FXML private Tab usageTab;
    @FXML private ChoiceBox<String> usageAgentChoice;
    @FXML private Label usageStatusLabel;
    @FXML private VBox usageBox;
    private UsagePane usagePane;
    private String loadingUsageFor;

    public MainController(Services services, Stage stage) {
        this.s = services;
        this.stage = stage;
        this.b = services.bundle();
        this.m = services.messages();
    }

    @FXML
    private void initialize() {
        dialogs = new Dialogs(b, stage);
        console = new ConsoleView(b);
        console.bind(s.log(), s.async().ui());
        VBox.setVgrow(console, javafx.scene.layout.Priority.ALWAYS);
        consoleHolder.getChildren().add(console);
        onlyImportantCheck.selectedProperty().addListener((o, a, v) -> console.setOnlyImportant(v));
        setupErrorHandlers();
        setupNavRail();
        setupTables();
        setupSkills();
        setupSyntax();
        setupUsage();
        setupFilesWindow();
        setupChat();
        // Built on every opening rather than kept in step: another window may have opened, closed or switched
        // sandbox since the last time this menu was looked at.
        sandboxSwitcher.setOnShowing(e -> buildSandboxMenu());
        versionButton.setText("v" + Version.current());
        showTheme(s.settings().theme());
        s.ctx().addListener(() -> s.async().onUi(this::onSandboxChanged));
        onSandboxChanged();
    }

    private void setupErrorHandlers() {
        java.util.function.Consumer<Throwable> handler = t -> dialogs.error(Errors.map(t, m));
        s.stack().setErrorHandler(handler);
        s.config().setErrorHandler(handler);
        s.sessions().setErrorHandler(handler);
        s.gateway().setErrorHandler(handler);
        s.agents().setErrorHandler(handler);
        s.skills().setErrorHandler(handler);
    }

    private void setupNavRail() {
        ToggleGroup group = new ToggleGroup();
        Map<ToggleButton, Tab> map = Map.of(navSandbox, sandboxTab, navEgress, egressTab, navChat, chatTab, navAsk, askTab,
                navSetup, setupTab, navGateway, gatewayTab, navAgents, agentsTab, navSkills, skillsTab,
                navSyntax, syntaxTab);
        map.forEach((btn, tab) -> {
            btn.setToggleGroup(group);
            btn.selectedProperty().addListener((o, a, v) -> { if (v) mainTabs.getSelectionModel().select(tab); });
        });
        mainTabs.getSelectionModel().selectedItemProperty().addListener((o, a, tab) -> {
            map.forEach((btn, t) -> { if (t == tab) btn.setSelected(true); });
            if (tab == chatTab) s.sessions().refresh();
            if (tab == egressTab) { s.config().refreshBlocked(); s.config().refreshDomains(); }
            if (tab == agentsTab) s.agents().refresh();
            if (tab == gatewayTab) s.gateway().refresh();
            if (tab == sandboxTab) s.stack().refresh();
            if (tab == skillsTab) s.skills().refresh();
            if (tab == syntaxTab) refreshLanguages();
        });
        navChat.setSelected(true);
        // Each rail item is an icon over a one-word label. Without the label the rail still looks deliberate —
        // icons only, at the rail's minimum width — so a window built from an older main.fxml is easy to mistake
        // for a design choice. Name the items that arrived without a label instead.
        List<String> unlabelled = map.keySet().stream()
                .filter(btn -> btn.getText() == null || btn.getText().isBlank())
                .map(ToggleButton::getId).sorted().toList();
        if (!unlabelled.isEmpty()) {
            s.log().warn("nav rail items without a label: " + String.join(", ", unlabelled)
                    + " — this window is running an older main.fxml than the source tree (mvn clean install)");
        }
        s.sessions().activity().addListener(() -> s.async().onUi(this::updateChatBadges));
        updateChatBadges();
    }

    private void updateChatBadges() {
        var a = s.sessions().activity();
        navChatSpinner.setVisible(a.running() > 0);
        navChatSpinner.setManaged(a.running() > 0);
        navChatDoneBadge.setText(String.valueOf(a.unreadDone()));
        navChatDoneBadge.setVisible(a.unreadDone() > 0);
        navChatDoneBadge.setManaged(a.unreadDone() > 0);
        navChatErrorBadge.setText(String.valueOf(a.unreadErrors()));
        navChatErrorBadge.setVisible(a.unreadErrors() > 0);
        navChatErrorBadge.setManaged(a.unreadErrors() > 0);
        // the rail label is one short word, so the tooltip names the page before it reports the activity
        navChat.setTooltip(new Tooltip(m.get("gui.nav.chat") + " — " + a.tooltip(m)));
    }

    private void setupTables() {
        containerServiceColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        containerNameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().container()));
        containerStateColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().state().isEmpty() ? m.get("stack.absent") : c.getValue().state()));
        containerHealthColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().health()));
        containerTable.setItems(s.stack().services());
        sessionConversationColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().orphan() ? m.get("gui.sandbox.orphan") : c.getValue().title()));
        sessionContainerColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().container()));
        sessionStateColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().state() + (c.getValue().busy() ? "  ·  " + m.get("gui.chat.busy") : "")));
        sessionImageColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().image()));
        sessionContainerTable.setItems(s.stack().sessionContainers());
        sessionContainerTable.setRowFactory(t -> {
            TableRow<SessionContainerInfo> row = new TableRow<>();
            row.itemProperty().addListener((o, a, item) -> {
                row.getStyleClass().remove("orphan-row");
                if (item == null) { row.setContextMenu(null); return; }
                if (item.orphan()) row.getStyleClass().add("orphan-row");
                ContextMenu menu = new ContextMenu();
                if (item.orphan()) {
                    MenuItem remove = new MenuItem(m.get("gui.sandbox.container.remove"));
                    remove.setOnAction(e -> s.stack().removeOrphan(item.container()));
                    menu.getItems().add(remove);
                } else if (item.running()) {
                    MenuItem stop = new MenuItem(m.get("gui.sandbox.container.stop"));
                    stop.setDisable(item.busy());
                    stop.setOnAction(e -> s.stack().stopSession(item.sessionId()));
                    menu.getItems().add(stop);
                }
                row.setContextMenu(menu.getItems().isEmpty() ? null : menu);
            });
            return row;
        });
        s.stack().logsRunningProperty().addListener((o, a, v) -> stopLogButton.setDisable(!v));
        stopLogButton.setDisable(true);

        blockedHostColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().host()));
        blockedStatusColumn.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().status())));
        blockedCountColumn.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().count())));
        blockedTable.setItems(s.config().blocked());
        allowBlockedButton.disableProperty().bind(blockedTable.getSelectionModel().selectedItemProperty().isNull());
        s.config().followingBlockedProperty().addListener((o, a, v) -> { followBlockedButton.setDisable(v); stopBlockedButton.setDisable(!v); });
        stopBlockedButton.setDisable(true);

        repoPathColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().path().toString()));
        repoMountColumn.setCellValueFactory(c -> new SimpleStringProperty("/work/" + c.getValue().name()));
        repoHistoryColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().history().map(h -> h.name().toLowerCase()).orElse(m.get("gui.repos.history.profile"))));
        repoTable.setItems(s.config().repos());
        removeRepoButton.disableProperty().bind(repoTable.getSelectionModel().selectedItemProperty().isNull());

        domainColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().domain()));
        domainSourceColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().source()));
        domainNoteColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().note()));
        domainTable.setItems(s.config().domains());
        removeDomainButton.disableProperty().bind(domainTable.getSelectionModel().selectedItemProperty().isNull());

        agentIdColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().id()));
        agentLabelColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().label()));
        agentStatusColumn.setCellValueFactory(c -> new SimpleStringProperty(!c.getValue().available() ? m.get("gui.agents.noadapter")
                : !c.getValue().enabled() ? m.get("gui.agents.disabled") : c.getValue().loggedIn() ? m.get("gui.agents.loggedin") + (c.getValue().authMode() == null ? "" : " (" + c.getValue().authMode() + ")") : m.get("gui.agents.notloggedin")));
        agentAccountColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().account() == null ? "" : c.getValue().account()));
        // A model server of your own is the other way an agent answers, so the page says where each one stands with
        // it — «not set up» is an invitation, and an empty cell is an agent the offer does not apply to.
        agentModelsColumn.setCellValueFactory(c -> new SimpleStringProperty(switch (AgentChoices.ownModels(c.getValue())) {
            case UNSUPPORTED -> "";
            case NONE -> m.get("gui.agents.localmodels.none");
            case CONFIGURED -> m.get("gui.agents.localmodels.count", c.getValue().localModels());
        }));
        agentTable.setItems(s.agents().agents());
        loginButton.disableProperty().bind(agentTable.getSelectionModel().selectedItemProperty().isNull());
        logoutButton.disableProperty().bind(agentTable.getSelectionModel().selectedItemProperty().isNull());
        // Offered only where it can work: an agent whose descriptor says nothing about a model server of its own
        // has no file to write, and the dialog would open only to fail on save.
        localModelsButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> AgentChoices.ownModels(agentTable.getSelectionModel().getSelectedItem()) == AgentChoices.OwnModels.UNSUPPORTED,
                agentTable.getSelectionModel().selectedItemProperty()));

        resourcePresetChoice.setItems(s.config().resourcePresets());
        resourcePresetChoice.setPromptText(m.get("resources.preset.custom"));
        resourcePresetChoice.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ResourcePreset p) { return p == null ? m.get("resources.preset.custom") : PresetChoices.describe(p, m); }
            @Override public ResourcePreset fromString(String s) { return null; }
        });
        s.config().resourcePresets().addListener((javafx.collections.ListChangeListener<ResourcePreset>) c ->
                showPreset(s.config().resources().get()));
        // Editing a field by hand is what "no preset" means, so the picker follows the fields rather than the other way round.
        for (TextField f : List.of(memLimitField, memswapLimitField, cpusField, pidsLimitField)) {
            f.textProperty().addListener((o, a, v) -> { if (!showingPreset) showPreset(editedLimits()); });
        }
        s.config().resources().addListener((o, a, r) -> {
            if (r == null) return;
            memLimitField.setText(r.mem());
            memswapLimitField.setText(r.memswap());
            cpusField.setText(r.cpus());
            pidsLimitField.setText(r.pids());
            showPreset(r);
        });
        s.config().profile().addListener((o, a, p) -> {
            if (p == null) return;
            profileNameLabel.setText(p.label() + " (" + p.name() + ")");
            profilePackLabel.setText(p.pack());
        });
        s.config().toolchains().addListener((javafx.collections.ListChangeListener<String>) c -> renderToolchains());
        protectedBranchesList.setItems(s.config().protectedBranches());
        removeProtectedBranchButton.disableProperty().bind(protectedBranchesList.getSelectionModel().selectedItemProperty().isNull());
        s.gateway().instances().addListener((javafx.collections.ListChangeListener<GatewayInstanceInfo>) c -> renderGatewayTabs());
        s.gateway().plugins().addListener((javafx.collections.ListChangeListener<no.syntakserror.lyngvi.core.contract.GatewayPluginInfo>) c ->
                pluginList.getItems().setAll(s.gateway().plugins().stream().map(p -> p.error() != null ? p.source() + "  ✗ " + p.error()
                        : p.type() + "  " + p.label() + "  (" + p.source() + ")").toList()));
        removeSystemButton.disableProperty().bind(gatewayTabs.getSelectionModel().selectedItemProperty().isNull());
    }

    // ---- skills ----

    /**
     * The skills page: the list with its tick per skill, and one {@code SKILL.md} in the editor at a time.
     * The tick is saved the moment it is clicked — switching a skill off is a decision in itself, not something
     * to be saved afterwards — while the text is the user's to save or discard.
     */
    private void setupSkills() {
        skillNameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        skillDescriptionColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().valid() ? c.getValue().description() : m.get("gui.skills.problem.short")));
        // The whole row is the value, so the cell can both show the flag and name the skill it changes.
        skillEnabledColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        skillEnabledColumn.setCellFactory(column -> new TableCell<>() {
            private final CheckBox box = new CheckBox();
            {
                box.setOnAction(e -> {
                    SkillInfo skill = getItem();
                    if (skill != null) s.skills().setEnabled(skill.name(), box.isSelected(), () -> {});
                });
            }

            @Override
            protected void updateItem(SkillInfo skill, boolean empty) {
                super.updateItem(skill, empty);
                if (empty || skill == null) {
                    setGraphic(null);
                    return;
                }
                box.setSelected(skill.enabled());
                box.setAccessibleText(m.get("gui.skills.col.active") + " " + skill.name());
                setGraphic(box);
            }
        });
        skillTable.setItems(s.skills().skills());
        removeSkillButton.disableProperty().bind(skillTable.getSelectionModel().selectedItemProperty().isNull());
        skillTable.getSelectionModel().selectedItemProperty().addListener((o, previous, selected) -> {
            if (restoringSkillSelection) return;
            // A refresh replaces every row, which drops the selection and brings it back as a new record. Neither
            // is the user picking another skill, and neither may take the editor away from them.
            if (selected == null || selected.name().equals(editingSkill)) return;
            if (!confirmSkillSwitch(previous)) return;
            openSkill(selected);
        });
        s.skills().skills().addListener((javafx.collections.ListChangeListener<SkillInfo>) c -> afterSkillsRefresh());
        s.skills().agentsProperty().addListener((o, a, agents) -> skillsAgentsLabel.setText(
                agents == null || agents.isBlank() ? m.get("gui.skills.agents.none") : m.get("gui.skills.agents", agents)));
        skillEditor.textProperty().addListener((o, a, v) -> {
            if (editingSkill != null) markSkillDirty(!v.equals(editingOriginal));
        });
        KeyCombination save = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);
        skillEditor.setOnKeyPressed(e -> { if (save.match(e)) onSaveSkill(); });
        clearSkillEditor();
    }

    /** Loads a skill into the editor. */
    private void openSkill(SkillInfo skill) {
        editingSkill = skill.name();
        skillNameLabel.setText(skill.name());
        skillEditor.setDisable(false);
        s.skills().load(skill.name(), content -> {
            if (!skill.name().equals(editingSkill)) return; // another skill was picked while this one loaded
            editingOriginal = content;
            skillEditor.setText(content);
            markSkillDirty(false);
            showSkillProblem();
        });
    }

    private void clearSkillEditor() {
        editingSkill = null;
        editingOriginal = "";
        skillEditor.clear();
        skillEditor.setDisable(true);
        skillNameLabel.setText(m.get("gui.skills.none"));
        markSkillDirty(false);
        showSkillProblem();
    }

    private void markSkillDirty(boolean dirty) {
        skillDirty = dirty;
        boolean editing = editingSkill != null;
        skillSaveButton.setDisable(!editing || !dirty);
        skillDiscardButton.setDisable(!editing || !dirty);
        skillStatusLabel.setText(!editing ? "" : dirty ? m.get("gui.files.modified") : "");
    }

    /**
     * After the list came back: the open skill is marked in it again — the rows are new records, so the table
     * lost the selection — and the editor is left alone unless the skill it holds is gone from the sandbox.
     */
    private void afterSkillsRefresh() {
        showSkillProblem();
        if (editingSkill == null) return;
        SkillInfo again = s.skills().skills().stream().filter(sk -> sk.name().equals(editingSkill)).findFirst().orElse(null);
        if (again == null) {
            clearSkillEditor();
            return;
        }
        // Not while the list is being replaced: the selection is set once the table has the new rows.
        javafx.application.Platform.runLater(() -> {
            if (again.name().equals(editingSkill) && skillTable.getItems().contains(again)) {
                restoringSkillSelection = true;
                skillTable.getSelectionModel().select(again);
                restoringSkillSelection = false;
            }
        });
    }

    /** The warning under the editor: why the open skill would not be loaded by the agent as it stands. */
    private void showSkillProblem() {
        String problem = s.skills().skills().stream()
                .filter(sk -> sk.name().equals(editingSkill)).findFirst()
                .map(SkillInfo::problem).orElse(null);
        skillProblemLabel.setText(problem == null ? "" : problem);
        skillProblemLabel.setVisible(problem != null);
        skillProblemLabel.setManaged(problem != null);
    }

    /**
     * Asks before unsaved text is left behind, and puts the selection back when the answer is no. Returns true
     * when the switch may go ahead.
     */
    private boolean confirmSkillSwitch(SkillInfo leaving) {
        if (confirmSkillDiscard()) return true;
        if (leaving != null) {
            restoringSkillSelection = true;
            javafx.application.Platform.runLater(() -> {
                skillTable.getSelectionModel().select(leaving);
                restoringSkillSelection = false;
            });
        }
        return false;
    }

    /** True when the editor holds nothing unsaved, or the user says the unsaved text may go. */
    private boolean confirmSkillDiscard() {
        if (!skillDirty || editingSkill == null) return true;
        return dialogs.confirm(m.get("gui.skills.discard.title"), m.get("gui.skills.discard.text", editingSkill),
                m.get("gui.files.discard"));
    }

    @FXML private void onRefreshSkills() { s.skills().refresh(); }

    @FXML private void onSaveSkill() {
        if (editingSkill == null || !skillDirty) return;
        String name = editingSkill;
        String content = skillEditor.getText();
        s.skills().save(name, content, () -> {
            if (!name.equals(editingSkill)) return;
            editingOriginal = content;
            markSkillDirty(false);
            skillStatusLabel.setText(m.get("gui.files.saved"));
            showSkillProblem();
        });
    }

    @FXML private void onDiscardSkill() {
        if (editingSkill == null) return;
        skillEditor.setText(editingOriginal);
        markSkillDirty(false);
    }

    @FXML private void onNewSkill() {
        if (!confirmSkillDiscard()) return;
        dialogs.textInput(m.get("gui.skills.new.title"), m.get("gui.skills.new.header"), "")
                .map(String::strip)
                .filter(name -> !name.isBlank())
                // A new skill is made to be written, so it opens in the editor; the list selects it when it arrives.
                .ifPresent(name -> s.skills().create(name, created -> {
                    openSkill(created);
                    skillEditor.requestFocus();
                }));
    }

    @FXML private void onRemoveSkill() {
        SkillInfo skill = skillTable.getSelectionModel().getSelectedItem();
        if (skill == null) return;
        if (!dialogs.confirm(m.get("gui.skills.remove.title"), m.get("gui.skills.remove.text", skill.name(), skill.path()),
                m.get("gui.button.remove"))) {
            return;
        }
        s.skills().remove(skill.name(), () -> {
            if (skill.name().equals(editingSkill)) clearSkillEditor();
        });
    }

    // ---- syntax colours ----

    /**
     * The syntax page: the languages on the left, one {@code .syntax} file in the editor on the right, and the
     * colours it gives underneath — the sample is re-coloured on every keystroke, so what a rule does is seen
     * before it is saved. Built like the skills page, and for the same reason: a file the user writes by hand
     * needs the list, the editor and the reason it does not work in one place.
     *
     * <p>Saving writes to the user's own folder. A language that came with Lyngvi is not touched: a file of the
     * same name takes its place, and «Remove» deletes that file and brings the shipped rules back.
     */
    private void setupSyntax() {
        languagePreview = new CodeView(b);
        languagePreviewBox.getChildren().add(languagePreview);
        languageNameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        languageFilesColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().problem() == null ? c.getValue().extensions() : m.get("gui.syntax.problem.short")));
        languageSourceColumn.setCellValueFactory(c -> new SimpleStringProperty(m.get(
                c.getValue().source() == Languages.Source.BUILT_IN ? "gui.syntax.source.builtin"
                        : c.getValue().overrides() ? "gui.syntax.source.replaces" : "gui.syntax.source.user")));
        languageTable.getSelectionModel().selectedItemProperty().addListener((o, previous, selected) -> {
            if (restoringLanguageSelection) return;
            // A refresh replaces every row: the selection comes back as a new record for the same language,
            // which is not the user picking another one and may not take the editor away from them.
            if (selected == null || selected.id().equals(editingLanguage)) return;
            if (!confirmLanguageSwitch(previous)) return;
            openLanguage(selected);
        });
        languageEditor.textProperty().addListener((o, a, v) -> {
            if (editingLanguage == null) return;
            markLanguageDirty(!v.equals(editingLanguageOriginal));
            showPreview(v);
        });
        KeyCombination save = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);
        languageEditor.setOnKeyPressed(e -> { if (save.match(e)) onSaveLanguage(); });
        syntaxDirLabel.setText(m.get("gui.syntax.folder", s.languages().directory().toString()));
        clearLanguageEditor();
        refreshLanguages();
    }

    /** Reads the folder again and lists what is there, keeping the language that is open open. */
    private void refreshLanguages() {
        s.languages().reload();
        String open = editingLanguage;
        restoringLanguageSelection = true;
        languageTable.getItems().setAll(s.languages().list());
        if (open != null) {
            languageTable.getItems().stream().filter(e -> e.id().equals(open)).findFirst()
                    .ifPresent(e -> languageTable.getSelectionModel().select(e));
        }
        restoringLanguageSelection = false;
        if (open != null && languageTable.getItems().stream().noneMatch(e -> e.id().equals(open))) clearLanguageEditor();
        else showLanguageProblem();
        updateRemoveLanguage();
    }

    private void openLanguage(Languages.Entry entry) {
        editingLanguage = entry.id();
        languageNameLabel.setText(entry.name() + "  ·  " + entry.id() + ".syntax");
        languageEditor.setDisable(false);
        editingLanguageOriginal = s.languages().text(entry.id());
        languageEditor.setText(editingLanguageOriginal);
        markLanguageDirty(false);
        showPreview(editingLanguageOriginal);
        updateRemoveLanguage();
    }

    private void clearLanguageEditor() {
        editingLanguage = null;
        editingLanguageOriginal = "";
        languageEditor.clear();
        languageEditor.setDisable(true);
        languageNameLabel.setText(m.get("gui.syntax.none"));
        languagePreview.show("", null);
        markLanguageDirty(false);
        showLanguageProblem();
        updateRemoveLanguage();
    }

    private void markLanguageDirty(boolean dirty) {
        languageDirty = dirty;
        boolean editing = editingLanguage != null;
        languageSaveButton.setDisable(!editing || !dirty);
        languageDiscardButton.setDisable(!editing || !dirty);
        languageStatusLabel.setText(!editing ? "" : dirty ? m.get("gui.files.modified") : "");
    }

    /** «Remove» is for the user's own file: a language that came with Lyngvi has none to delete. */
    private void updateRemoveLanguage() {
        Languages.Entry selected = languageTable.getSelectionModel().getSelectedItem();
        removeLanguageButton.setDisable(selected == null || !s.languages().isUser(selected.id()));
    }

    /**
     * Colours the sample by the rules as they stand in the editor, saved or not. A file that cannot be read says
     * why instead, which is the whole feedback loop for writing one: type, see, fix.
     */
    private void showPreview(String text) {
        if (editingLanguage == null) return;
        try {
            Syntax syntax = Syntax.parse(editingLanguage, text);
            languagePreview.show(syntax.preview(), syntax);
            showProblemText(null);
        } catch (Syntax.Problem p) {
            languagePreview.show("", null);
            showProblemText(p.getMessage());
        }
    }

    /** The warning under the editor: why the language in it colours nothing as the file stands on disk. */
    private void showLanguageProblem() {
        showProblemText(languageTable.getItems().stream()
                .filter(e -> e.id().equals(editingLanguage)).findFirst()
                .map(Languages.Entry::problem).orElse(null));
    }

    private void showProblemText(String problem) {
        languageProblemLabel.setText(problem == null ? "" : problem);
        languageProblemLabel.setVisible(problem != null);
        languageProblemLabel.setManaged(problem != null);
    }

    /** Asks before unsaved text is left behind, and puts the selection back when the answer is no. */
    private boolean confirmLanguageSwitch(Languages.Entry leaving) {
        if (confirmLanguageDiscard()) return true;
        if (leaving != null) {
            restoringLanguageSelection = true;
            javafx.application.Platform.runLater(() -> {
                languageTable.getSelectionModel().select(leaving);
                restoringLanguageSelection = false;
            });
        }
        return false;
    }

    private boolean confirmLanguageDiscard() {
        if (!languageDirty || editingLanguage == null) return true;
        return dialogs.confirm(m.get("gui.skills.discard.title"), m.get("gui.syntax.discard.text", editingLanguage),
                m.get("gui.files.discard"));
    }

    @FXML private void onRefreshLanguages() {
        refreshLanguages();
    }

    @FXML private void onSaveLanguage() {
        if (editingLanguage == null || !languageDirty) return;
        String id = editingLanguage;
        String text = languageEditor.getText();
        try {
            s.languages().save(id, text);
        } catch (java.io.IOException e) {
            dialogs.error(Errors.map(e, m));
            return;
        }
        editingLanguageOriginal = text;
        markLanguageDirty(false);
        languageStatusLabel.setText(m.get("gui.files.saved"));
        s.log().info("saved syntax " + s.languages().fileOf(id));
        refreshLanguages();
        // the file on disk is what the pane colours by now, so the warning is the one the file earns
        showLanguageProblem();
    }

    @FXML private void onDiscardLanguage() {
        if (editingLanguage == null) return;
        languageEditor.setText(editingLanguageOriginal);
        markLanguageDirty(false);
    }

    @FXML private void onNewLanguage() {
        if (!confirmLanguageDiscard()) return;
        dialogs.textInput(m.get("gui.syntax.new.title"), m.get("gui.syntax.new.header"), "")
                .map(name -> name.strip().toLowerCase(java.util.Locale.ROOT))
                .filter(name -> !name.isBlank())
                .ifPresent(name -> {
                    try {
                        s.languages().create(name);
                    } catch (java.io.IOException e) {
                        dialogs.error(Errors.map(e, m));
                        return;
                    }
                    // a new language is made to be written, so it opens in the editor with the template in it
                    refreshLanguages();
                    languageTable.getItems().stream().filter(e -> e.id().equals(name)).findFirst().ifPresent(e -> {
                        restoringLanguageSelection = true;
                        languageTable.getSelectionModel().select(e);
                        restoringLanguageSelection = false;
                        openLanguage(e);
                    });
                    languageEditor.requestFocus();
                });
    }

    @FXML private void onRemoveLanguage() {
        Languages.Entry entry = languageTable.getSelectionModel().getSelectedItem();
        if (entry == null || !s.languages().isUser(entry.id())) return;
        boolean shipped = entry.overrides();
        if (!dialogs.confirm(m.get("gui.syntax.remove.title"),
                m.get(shipped ? "gui.syntax.remove.replaced" : "gui.syntax.remove.text", entry.name(),
                        s.languages().fileOf(entry.id()).toString()),
                m.get("gui.button.remove"))) {
            return;
        }
        try {
            s.languages().remove(entry.id());
        } catch (java.io.IOException e) {
            dialogs.error(Errors.map(e, m));
            return;
        }
        if (entry.id().equals(editingLanguage)) {
            // a replaced language is still there, with the shipped rules back in it; one of the user's own is gone
            refreshLanguages();
            languageTable.getItems().stream().filter(e -> e.id().equals(entry.id())).findFirst()
                    .ifPresentOrElse(this::openLanguage, this::clearLanguageEditor);
        } else {
            refreshLanguages();
        }
    }

    /** The usage sub-tab: an agent picker that loads on change, and a pane that draws the report. */
    private void setupUsage() {
        usagePane = new UsagePane(m);
        usageBox.getChildren().add(usagePane);
        usagePane.show(null);
        usageAgentChoice.getSelectionModel().selectedItemProperty().addListener((o, a, id) -> loadUsage(id));
        agentsTabs.getSelectionModel().selectedItemProperty().addListener((o, a, tab) -> { if (tab == usageTab) onRefreshUsage(); });
        s.agents().agents().addListener((javafx.collections.ListChangeListener<AgentInfo>) c -> updateUsageAgents());
    }

    /** Keeps the picker in step with the agent list, holding on to the chosen agent while it is still listed. */
    private void updateUsageAgents() {
        List<String> ids = s.agents().agents().stream().map(AgentInfo::id).toList();
        if (ids.equals(usageAgentChoice.getItems())) return;
        String selected = usageAgentChoice.getValue();
        usageAgentChoice.getItems().setAll(ids);
        if (selected != null && ids.contains(selected)) usageAgentChoice.setValue(selected);
        else if (!ids.isEmpty()) usageAgentChoice.setValue(ids.getFirst());
    }

    /** Asking the agent starts a process, so one load per agent at a time: picking and switching tab both land here. */
    private void loadUsage(String agentId) {
        if (agentId == null || !s.ctx().isOpen()) {
            loadingUsageFor = null;
            usageStatusLabel.setText("");
            usagePane.show(null);
            return;
        }
        if (agentId.equals(loadingUsageFor)) return;
        loadingUsageFor = agentId;
        usageStatusLabel.setText(m.get("gui.usage.loading"));
        s.agents().usageReport(agentId, r -> {
            loadingUsageFor = null;
            usageStatusLabel.setText("");
            usagePane.show(r);
        }, t -> {
            loadingUsageFor = null;
            usageStatusLabel.setText("");
            usagePane.show(null);
            dialogs.error(Errors.map(t, m));
        });
    }

    /**
     * The files window is built with this one — the chat hands it every conversation that is selected, open or
     * not — but shown no earlier than the toolbar button or the last session's setting asks for it.
     */
    private void setupFilesWindow() {
        filesWindow = new FilesWindow(stage, s);
        filesController = filesWindow.controller();
        filesToggle.setSelected(s.settings().filesWindowOpen());
        // Closed from the window itself: the toolbar button is what says whether it is up, so it follows along.
        filesWindow.setOnCloseRequest(e -> {
            filesToggle.setSelected(false);
            s.settings().setFilesWindowOpen(false);
            s.settings().save();
        });
        KeyCombination toggle = new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN);
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> shortcut = e -> {
            if (toggle.match(e)) {
                filesToggle.setSelected(!filesToggle.isSelected());
                onToggleFiles();
            }
        };
        // On both windows: the shortcut that opens the files closes them again from inside them.
        stage.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, shortcut);
        filesWindow.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, shortcut);
    }

    private void applyFilesVisible(boolean visible) {
        if (visible) {
            filesWindow.open();
        } else {
            filesWindow.hide();
        }
    }

    private void setupChat() {
        chatController.setFilesController(filesController);
        askController.setEphemeral(true);
    }

    private void onSandboxChanged() {
        boolean open = s.ctx().isOpen();
        Optional<Path> dir = s.ctx().dir();
        sandboxSwitcher.setText(dir.map(d -> SandboxLabel.path(d, s.ctx().platform().home())).orElse(m.get("gui.sandbox.none")));
        // Several windows can be open at once, one sandbox each, so the title says which sandbox this window is.
        stage.setTitle(dir.map(d -> "Lyngvi — " + SandboxLabel.name(d)).orElse("Lyngvi"));
        filesWindow.showSandbox(dir.map(SandboxLabel::name));
        statusLabel.setText("");
        // The hint and the transient status messages share the toolbar slot, so only one of them is ever present.
        chooseSandboxLink.setVisible(!open);
        chooseSandboxLink.setManaged(!open);
        // Whatever was on screen belongs to the sandbox this window had before, if any: conversation, files, logs.
        chatController.onSandboxChanged();
        askController.onSandboxChanged();
        filesController.clearSession();
        // The skills on screen are the ones of the sandbox this window is leaving; the new one brings its own.
        s.skills().clear();
        clearSkillEditor();
        // Every page here belongs to a sandbox — except the syntax colours, which are the user's own and the same
        // in every sandbox, so that one stays open when none is.
        for (Tab t : List.of(sandboxTab, egressTab, chatTab, askTab, setupTab, gatewayTab, agentsTab, skillsTab)) t.setDisable(!open);
        if (!open) return;
        s.settings().setSandboxDir(dir.orElseThrow());
        s.settings().addRecentSandbox(dir.orElseThrow());
        s.settings().save();
        profileVersionLabel.setText(m.get("version.line", Version.current().toString()));
        s.stack().rebuildConfig(r -> {
            s.stack().refresh();
            s.config().refreshAll();
            s.sessions().refresh();
            s.agents().refresh();
            s.gateway().refresh();
            s.skills().refresh();
            refreshMachine();
            refreshCert();
            refreshUpgradeButton();
        });
        chatController.setSandboxOpen(true);
        askController.setSandboxOpen(true);
    }

    /** Called once the window is visible: the container poll and, unless suppressed, the first-run dialogs. */
    void afterShow(boolean dialogs) {
        // Not before now: the files window is placed beside this one, which has no position until it is up.
        if (filesToggle.isSelected()) applyFilesVisible(true);
        containerPoll = new Timeline(new KeyFrame(Duration.seconds(5), e -> {
            if (s.ctx().isOpen() && mainTabs.getSelectionModel().getSelectedItem() == sandboxTab) s.stack().refresh();
            if (s.ctx().isOpen() && mainTabs.getSelectionModel().getSelectedItem() == chatTab) s.sessions().refresh();
        }));
        containerPoll.setCycleCount(Animation.INDEFINITE);
        containerPoll.play();
        // The profile's idle limit (session.idleStopMinutes) is enforced while the GUI runs; orphan containers go too.
        cleanupTimer = new Timeline(new KeyFrame(Duration.minutes(5), e -> { if (s.ctx().isOpen()) s.stack().cleanup(r -> {}); }));
        cleanupTimer.setCycleCount(Animation.INDEFINITE);
        cleanupTimer.play();
        if (s.ctx().isOpen()) s.stack().cleanup(r -> {});
        if (!s.ctx().isOpen()) {
            s.log().warn(m.get("gui.sandbox.none.hint"));
        } else if (!dialogs) {
            return;
        } else if (!s.settings().welcomeShown()) {
            onShowWelcome();
        }
        String latest = Changelog.bundled().latestVersion();
        if (!latest.isEmpty() && !latest.equals(s.settings().lastChangelogSeen()) && s.settings().welcomeShown()) {
            onShowChangelog();
        }
    }

    void shutdown() {
        if (containerPoll != null) containerPoll.stop();
        if (cleanupTimer != null) cleanupTimer.stop();
        s.stack().stopLogs();
        s.config().stopFollowingBlocked();
        askController.discardEphemeral();
        // The files window has no owner to take it with it, so this window closes it; its place is kept as it goes.
        if (filesWindow != null) filesWindow.close();
    }

    // ---- toolbar ----

    @FXML private void onRefreshAll() {
        if (!s.ctx().isOpen()) return;
        s.stack().refresh();
        s.sessions().refresh();
        s.config().refreshAll();
    }

    @FXML private void onChooseSandboxDir() {
        chooseSandboxFolder().ifPresent(this::switchSandbox);
    }

    private Optional<Path> chooseSandboxFolder() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(m.get("gui.sandbox.choose"));
        s.ctx().dir().map(Path::toFile).filter(File::isDirectory).ifPresent(dc::setInitialDirectory);
        return Optional.ofNullable(dc.showDialog(stage)).map(File::toPath);
    }

    /**
     * The switcher's menu, built when it opens: every sandbox this machine has worked with, the open one marked,
     * and the same list again under «Open in a new window» for the ones no window shows yet. Two windows on one
     * sandbox would be two sets of timers and two cleanup runs over the same containers, so a sandbox that is
     * already on screen somewhere is raised instead of opened again.
     */
    private void buildSandboxMenu() {
        sandboxSwitcher.getItems().clear();
        List<SandboxChoices.Entry> entries = SandboxChoices.of(s.ctx().dir().orElse(null), s.settings().recentSandboxes(),
                java.nio.file.Files::isDirectory, dir -> MainWindow.showing(dir).filter(w -> w.stage() != stage).isPresent());
        ToggleGroup openSandbox = new ToggleGroup();
        for (SandboxChoices.Entry entry : entries) {
            RadioMenuItem item = new RadioMenuItem(sandboxMenuLabel(entry));
            item.setToggleGroup(openSandbox);
            item.setSelected(entry.open());
            item.setOnAction(e -> switchSandbox(entry.dir()));
            sandboxSwitcher.getItems().add(item);
        }
        if (!entries.isEmpty()) sandboxSwitcher.getItems().add(new SeparatorMenuItem());
        Menu newWindow = new Menu(m.get("gui.sandbox.switch.newwindow"));
        for (SandboxChoices.Entry entry : entries) {
            if (!entry.free()) continue; // it has a window of its own already, or it is the one in this window
            MenuItem item = new MenuItem(sandboxMenuLabel(entry));
            item.setOnAction(e -> openInNewWindow(entry.dir()));
            newWindow.getItems().add(item);
        }
        if (!newWindow.getItems().isEmpty()) newWindow.getItems().add(new SeparatorMenuItem());
        MenuItem chooseForNewWindow = new MenuItem(m.get("gui.sandbox.switch.newwindow.choose"));
        chooseForNewWindow.setOnAction(e -> chooseSandboxFolder().ifPresent(this::openInNewWindow));
        newWindow.getItems().add(chooseForNewWindow);
        MenuItem choose = new MenuItem(m.get("gui.sandbox.choose"));
        choose.setOnAction(e -> onChooseSandboxDir());
        MenuItem fresh = new MenuItem(m.get("gui.sandbox.new.menu"));
        fresh.setOnAction(e -> onNewSandbox());
        sandboxSwitcher.getItems().addAll(newWindow, new SeparatorMenuItem(), choose, fresh);
    }

    /** The path, and a word about the window it is already in — clicking it goes there rather than opening it twice. */
    private String sandboxMenuLabel(SandboxChoices.Entry entry) {
        String label = SandboxLabel.path(entry.dir(), s.ctx().platform().home());
        return entry.inAnotherWindow() ? label + "   " + m.get("gui.sandbox.switch.elsewhere") : label;
    }

    /**
     * Shows another sandbox in this window — or raises the window that already shows it. The conversations running
     * here lose the window that renders them, so they are asked about first; what the window streams from the
     * sandbox it is leaving is stopped before the change.
     */
    private void switchSandbox(Path dir) {
        Path wanted = dir.toAbsolutePath().normalize();
        if (s.ctx().dir().map(d -> d.toAbsolutePath().normalize().equals(wanted)).orElse(false)) return;
        Optional<MainWindow> elsewhere = MainWindow.showing(wanted).filter(w -> w.stage() != stage);
        if (elsewhere.isPresent()) {
            elsewhere.get().focus();
            return;
        }
        int running = s.sessions().runningCount();
        if (running > 0 && !dialogs.confirm(m.get("gui.sandbox.switch.title"), switchWarning(running, m),
                m.get("gui.sandbox.switch.button"))) {
            return;
        }
        s.stack().stopLogs();
        s.config().stopFollowingBlocked();
        chatController.onSandboxLeaving();
        askController.onSandboxLeaving();
        filesController.clearSession();
        openOrMigrate(wanted);
    }

    /** The warning before a window changes sandbox while conversations of its own are still working. */
    public static String switchWarning(int running, Messages m) {
        return running == 1 ? m.get("gui.sandbox.switch.warning.one") : m.get("gui.sandbox.switch.warning.many", running);
    }

    /** Opens a sandbox in a window of its own, so that it can be worked on beside the one already open here. */
    private void openInNewWindow(Path dir) {
        Optional<MainWindow> elsewhere = MainWindow.showing(dir);
        if (elsewhere.isPresent()) {
            elsewhere.get().focus();
            return;
        }
        try {
            MainWindow.openAnother(s.ctx().platform(), s.settings(), dir, stage);
        } catch (java.io.IOException | RuntimeException e) {
            dialogs.error(Errors.map(e, m));
        }
    }

    /** Opens a sandbox; a 1.1.2 sandbox is offered for migration first (spec §12). */
    void openOrMigrate(Path dir) {
        if (s.ctx().needsMigration(dir)) {
            if (!dialogs.confirm(m.get("gui.migrate.title"), m.get("gui.migrate.text", dir.toString()), m.get("gui.migrate.button"))) return;
            s.async().call(() -> s.ctx().migrate(dir), report -> {
                StringBuilder sb = new StringBuilder(m.get("gui.migrate.done", report.migratedConversations(), report.migratedSessions(), report.gatewayInstances()));
                for (String step : report.manualSteps()) sb.append("\n\u2022 ").append(step);
                dialogs.info(m.get("gui.migrate.title"), sb.toString());
            }, t -> dialogs.error(Errors.map(t, m)));
            return;
        }
        if (!s.ctx().open(dir)) dialogs.error(Errors.map(s.ctx().lastError(), m));
    }

    /**
     * «New sandbox…» is a wizard, not a form: it creates the sandbox, builds the image, starts the stack, checks it
     * and offers the login, and hands back a sandbox that is ready to be worked in. Its own «Get started» checklist
     * has then been walked through, so the first-run dialog is not shown again.
     */
    @FXML private void onNewSandbox() {
        new SandboxWizard(stage, b, m, s).showAndWait().ifPresent(result -> {
            s.settings().setWelcomeShown(true);
            s.settings().save();
            if (result.newConversation()) {
                navChat.setSelected(true);
                chatController.onNewConversation();
            }
        });
    }

    @FXML private void onToggleFiles() {
        applyFilesVisible(filesToggle.isSelected());
        s.settings().setFilesWindowOpen(filesToggle.isSelected());
        s.settings().save();
    }

    /** The theme is one setting for the whole application, so every open window turns with the one that was asked. */
    @FXML private void onToggleTheme() {
        Settings.Theme next = s.settings().theme() == Settings.Theme.DARK ? Settings.Theme.LIGHT : Settings.Theme.DARK;
        s.settings().setTheme(next);
        s.settings().save();
        for (MainWindow w : MainWindow.open()) {
            Theme.apply(w.stage().getScene(), next);
            w.controller().showTheme(next);
        }
    }

    /** Puts the icon of the theme the button would switch to on it: a sun while dark, a moon while light. */
    private void showTheme(Settings.Theme theme) {
        themeToggleButton.setGraphic(Icon.of(theme == Settings.Theme.DARK ? "sun" : "moon"));
    }

    @FXML private void onShowWelcome() {
        WelcomeDialog d = new WelcomeDialog(stage, b, this::onBuild, this::onStart, () -> navAgents.setSelected(true), () -> { navChat.setSelected(true); chatController.onNewConversation(); });
        d.showAndWait().ifPresent(dontShow -> {
            s.settings().setWelcomeShown(true);
            s.settings().save();
        });
    }

    @FXML private void onShowChangelog() {
        Changelog c = Changelog.bundled();
        String sandboxVersion = s.ctx().isOpen() ? readSandboxVersion() : null;
        new ChangelogDialog(stage, b, Version.current().toString(), sandboxVersion, c).showAndWait();
        s.settings().setLastChangelogSeen(c.latestVersion());
        s.settings().save();
    }

    private String readSandboxVersion() {
        try {
            return java.nio.file.Files.readString(s.ctx().sandbox().paths().root().resolve("VERSION")).strip();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    @FXML private void onUpgrade() {
        if (!dialogs.confirm(m.get("gui.upgrade.title"), m.get("gui.upgrade.hint"), m.get("gui.upgrade.button"))) return;
        s.stack().upgrade(r -> {
            refreshUpgradeButton();
            s.stack().refresh();
            // The dialog that asks for the build can run it too, so the upgrade is finished in one place.
            new UpgradeDialog(stage, b, m, s, r).showAndWait();
        });
    }

    private void refreshUpgradeButton() {
        s.stack().upgradeNeeded(needed -> {
            upgradeButton.setVisible(needed);
            upgradeButton.setManaged(needed);
        });
    }

    @FXML private void onReportProblem() {
        FileChooser fc = new FileChooser();
        fc.setInitialFileName("lyngvi-report-" + java.time.LocalDate.now() + ".zip");
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        s.async().call(() -> {
            try {
                return ProblemReport.create(f.toPath(), s.ctx(), s.log());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, p -> dialogs.info(m.get("gui.report.done.title"), m.get("gui.report.done", p.toString())), t -> dialogs.error(Errors.map(t, m)));
    }

    @FXML private void onVersion() {
        onShowChangelog();
    }

    @FXML private void onToggleOnlyImportant() {
        console.setOnlyImportant(onlyImportantCheck.isSelected());
    }

    // ---- sandbox: run ----

    @FXML private void onStart() { s.stack().up(); }
    @FXML private void onStop() { s.stack().down(); }
    @FXML private void onBuild() { s.stack().build(false); }
    @FXML private void onDoctor() {
        s.stack().doctor(r -> statusLabel.setText(m.get("doctor.summary", r.ok(), r.warn(), r.fail())));
    }
    @FXML private void onRebuildConfig() { s.stack().rebuildConfig(r -> s.stack().refresh()); }
    @FXML private void onStatus() { s.stack().refresh(); }
    @FXML private void onRefreshContainers() { s.stack().refresh(); }
    /**
     * The safe sweep first — stop the idle, remove what has no conversation — and then, if anything is left
     * lying about, the user is shown exactly what and asked. Nothing that holds undelivered work is ever in
     * that list: the report is taken with the default options, which leave live conversations alone.
     */
    @FXML private void onCleanup() {
        s.stack().cleanup(r -> {
            statusLabel.setText(m.get("gui.sandbox.cleanup.done", r.stopped().size() + r.retired().size(), r.removed().size()));
            s.stack().diskReport(this::offerToReclaim);
        });
    }

    private void offerToReclaim(no.syntakserror.lyngvi.core.contract.DiskReport report) {
        if (report.reclaimable().isEmpty()) return;
        String size = no.syntakserror.lyngvi.core.stack.ResourceSizes.format(report.reclaimableBytes())
                + (report.sizesIncomplete() ? "+" : "");
        StringBuilder what = new StringBuilder();
        for (var e : report.reclaimable()) {
            what.append(e.kind()).append("  ").append(e.name()).append('\n');
        }
        boolean go = dialogs.confirm(m.get("gui.sandbox.reclaim.title"),
                m.get("gui.sandbox.reclaim.body", report.reclaimable().size(), size) + "\n\n" + what,
                m.get("gui.sandbox.reclaim.ok"));
        if (!go) return;
        s.stack().clean(new no.syntakserror.lyngvi.core.api.CleanOptions(null, 0, false, false),
                r -> statusLabel.setText(m.get("gui.sandbox.reclaim.done", r.removed().size(),
                        no.syntakserror.lyngvi.core.stack.ResourceSizes.format(r.freedBytes()))));
    }
    @FXML private void onFollowProxyLog() { s.stack().startLogs("proxy", line -> s.log().info("[proxy] " + line)); }
    @FXML private void onFollowGatewayLog() { s.stack().startLogs("gateway", line -> s.log().info("[gateway] " + line)); }
    @FXML private void onStopLog() { s.stack().stopLogs(); }

    // ---- sandbox: resources ----

    @FXML private void onSaveResources() {
        s.config().saveResources(new ResourceLimits(blank(memLimitField), blank(memswapLimitField), blank(cpusField), blank(pidsLimitField)));
        refreshMachine();
    }

    /** A preset fills the four fields; nothing is saved until the user says so, so the choice can be tried out. */
    @FXML private void onChooseResourcePreset() {
        ResourcePreset p = resourcePresetChoice.getValue();
        if (p == null || showingPreset) return;
        showingPreset = true;
        try {
            memLimitField.setText(p.limits().mem());
            memswapLimitField.setText(p.limits().memswap());
            cpusField.setText(p.limits().cpus());
            pidsLimitField.setText(p.limits().pids());
        } finally {
            showingPreset = false;
        }
        refreshMachine();
    }

    /** Shows which preset the limits correspond to, or none at all when they are the user's own. */
    private void showPreset(ResourceLimits limits) {
        showingPreset = true;
        try {
            resourcePresetChoice.setValue(PresetChoices.matching(s.config().resourcePresets(), limits).orElse(null));
        } finally {
            showingPreset = false;
        }
    }

    private static String blank(TextField f) {
        return f.getText() == null || f.getText().isBlank() ? null : f.getText().strip();
    }

    @FXML private void onRefreshMachine() { refreshMachine(); }

    /** The limits in the fields when they are complete and well formed, else the saved ones. */
    private ResourceLimits editedLimits() {
        ResourceLimits edited = new ResourceLimits(blank(memLimitField), blank(memswapLimitField), blank(cpusField), blank(pidsLimitField));
        return edited.invalidFields().isEmpty() ? edited : s.config().resources().get();
    }

    private void refreshMachine() {
        s.stack().machine(info -> {
            ResourceLimits limits = editedLimits();
            int sessions = limits == null ? ResourceFit.UNKNOWN : ResourceFit.sessions(limits, info);
            machineInfoLabel.setText(info.available()
                    ? m.get("machine.header", info.name(), info.state()) + "  " + info.cpus() + " CPU, " + (info.memoryBytes() / (1024 * 1024)) + " MiB, " + (info.diskBytes() / (1024L * 1024 * 1024)) + " GiB"
                            + (sessions == ResourceFit.UNKNOWN ? "" : "\n" + m.get("resources.preset.sessions", sessions))
                    : m.get("machine.unavailable"));
            List<String> advice = new ArrayList<>();
            if (limits != null) for (ResourceAdvice.Advice a : ResourceAdvice.evaluate(limits, info)) advice.add(a.message());
            machineWarningLabel.setText(String.join("\n", advice));
            machineWarningLabel.setVisible(!advice.isEmpty());
            machineWarningLabel.setManaged(!advice.isEmpty());
        });
    }

    @FXML private void onShowMachineHelp() {
        dialogs.info(m.get("gui.machine.help.title"), m.get("gui.machine.help.text"));
    }

    // ---- traffic ----

    @FXML private void onRefreshBlocked() { s.config().refreshBlocked(); }
    @FXML private void onFollowBlocked() { s.config().startFollowingBlocked(); }
    @FXML private void onStopBlocked() { s.config().stopFollowingBlocked(); }
    @FXML private void onAllowBlocked() {
        BlockedRequest sel = blockedTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        if (dialogs.confirm(m.get("gui.traffic.allow.title"), m.get("gui.traffic.allow.text", sel.host()), m.get("gui.traffic.allow.button"))) {
            s.config().allowBlocked(sel.host());
            blockedStatusLabel.setText(m.get("domain.added", sel.host()));
        }
    }

    // ---- repos / domains ----

    @FXML private void onAddRepo() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(m.get("gui.repos.add"));
        File f = dc.showDialog(stage);
        if (f != null) s.config().addRepo(f.toPath(), Optional.empty(), Optional.empty());
    }
    @FXML private void onRemoveRepo() {
        var sel = repoTable.getSelectionModel().getSelectedItem();
        if (sel != null) s.config().removeRepo(sel.name());
    }
    @FXML private void onAddDomain() {
        if (domainInput.getText().isBlank()) return;
        s.config().addDomain(domainInput.getText().strip(), Optional.empty());
        domainInput.clear();
    }
    @FXML private void onRemoveDomain() {
        DomainEntry sel = domainTable.getSelectionModel().getSelectedItem();
        if (sel != null) s.config().removeDomain(sel.domain());
    }
    @FXML private void onReloadProxy() {
        s.config().reloadProxy(ok -> s.log().info(m.get(ok ? "domain.reloaded" : "domain.not.running")));
    }

    // ---- profile / toolchain ----

    @FXML private void onAddProtectedBranch() {
        String name = protectedBranchInput.getText() == null ? "" : protectedBranchInput.getText().strip();
        if (name.isEmpty()) return;
        List<String> branches = new ArrayList<>(s.config().protectedBranches());
        if (!branches.contains(name)) branches.add(name);
        s.config().setProtectedBranches(branches, protectedBranchInput::clear);
    }

    @FXML private void onRemoveProtectedBranch() {
        String sel = protectedBranchesList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        List<String> branches = new ArrayList<>(s.config().protectedBranches());
        branches.remove(sel);
        s.config().setProtectedBranches(branches, () -> {});
    }

    /** One row per bundled fragment: the checkbox, and beside it what the fragment is for, when its header says. */
    private void renderToolchains() {
        if (savingToolchains) return; // the checkboxes are ahead of the saved list; they are re-read when the save lands
        toolchainBox.getChildren().clear();
        if (!s.ctx().isOpen()) return;
        List<String> selected = s.config().toolchains();
        Map<String, String> descriptions;
        try {
            descriptions = s.config().toolchainDescriptions();
        } catch (RuntimeException e) {
            descriptions = Map.of(); // descriptions only; the checkboxes must appear whatever the fragments look like
        }
        for (String t : s.config().availableToolchains()) {
            CheckBox cb = new CheckBox(t);
            cb.setSelected(selected.contains(t));
            cb.selectedProperty().addListener((o, a, v) -> saveToolchains());
            HBox row = new HBox(10, cb);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            if (descriptions.containsKey(t)) {
                Label what = new Label(descriptions.get(t));
                what.getStyleClass().add("section-help");
                what.setOnMouseClicked(e -> cb.fire()); // the description is part of the row, so it toggles too
                row.getChildren().add(what);
            }
            toolchainBox.getChildren().add(row);
        }
        refreshToolchainStatus();
    }

    private List<String> checkedToolchains() {
        List<String> chosen = new ArrayList<>();
        for (javafx.scene.Node row : toolchainBox.getChildren()) {
            if (row instanceof HBox h && h.getChildren().get(0) instanceof CheckBox cb && cb.isSelected()) chosen.add(cb.getText());
        }
        return chosen;
    }

    private boolean savingToolchains;
    private boolean toolchainsDirty;
    private Runnable afterToolchainSave;

    /**
     * Writes the ticked toolchains to the profile. Ticks arrive faster than a save returns, and the view model runs
     * each call on its own thread, so saves are serialised here: one in flight, a tick meanwhile marks the selection
     * dirty, and the next save reads the checkboxes afresh. The status follows every settled save.
     */
    private void saveToolchains() {
        if (savingToolchains) {
            toolchainsDirty = true;
            return;
        }
        savingToolchains = true;
        s.config().setToolchains(checkedToolchains(), () -> {
            savingToolchains = false;
            if (toolchainsDirty) {
                toolchainsDirty = false;
                saveToolchains();
                return;
            }
            refreshToolchainStatus();
            Runnable then = afterToolchainSave;
            afterToolchainSave = null;
            if (then != null) then.run();
        });
    }

    /** Runs {@code action} now, or after the save in flight (and any tick made during it) has landed. */
    private void whenToolchainsSaved(Runnable action) {
        if (savingToolchains) afterToolchainSave = action;
        else action.run();
    }

    /** Compares the ticks with the built image: what is missing, what is left over, or nothing. */
    private void refreshToolchainStatus() {
        if (!s.ctx().isOpen()) return;
        List<String> checked = checkedToolchains();
        s.config().imageToolchains(image -> {
            List<String> parts = new ArrayList<>();
            boolean rebuild;
            if (image.isEmpty()) {
                parts.add(m.get("gui.toolchain.status.noimage"));
                rebuild = true;
            } else {
                List<String> missing = checked.stream().filter(t -> !image.get().contains(t)).toList();
                List<String> leftover = image.get().stream().filter(t -> !checked.contains(t)).sorted().toList();
                if (!missing.isEmpty()) parts.add(m.get("gui.toolchain.status.missing", String.join(", ", missing)));
                if (!leftover.isEmpty()) parts.add(m.get("gui.toolchain.status.leftover", String.join(", ", leftover)));
                rebuild = !parts.isEmpty();
                if (rebuild) parts.add(m.get("gui.toolchain.status.rebuild"));
                else parts.add(m.get("gui.toolchain.status.current"));
            }
            toolchainStatusLabel.setText(String.join(" ", parts));
            toolchainStatusLabel.getStyleClass().removeAll("status-done", "status-warn");
            toolchainStatusLabel.getStyleClass().add(rebuild ? "status-warn" : "status-done");
            rebuildImageButton.getStyleClass().remove("primary");
            if (rebuild) rebuildImageButton.getStyleClass().add("primary");
        });
    }

    /** Waits for the ticks to be saved, re-renders the configuration and rebuilds the images; the tab shows progress and the result. */
    @FXML private void onSaveAndRebuild() {
        whenToolchainsSaved(() -> s.stack().rebuildConfig(r -> {
            rebuildImageButton.setDisable(true);
            toolchainProgress.setVisible(true);
            toolchainProgress.setManaged(true);
            toolchainStatusLabel.setText(m.get("gui.toolchain.status.building"));
            Runnable done = () -> {
                toolchainProgress.setVisible(false);
                toolchainProgress.setManaged(false);
                rebuildImageButton.setDisable(false);
                refreshToolchainStatus();
            };
            java.util.function.Consumer<Throwable> previous = t -> dialogs.error(Errors.map(t, m));
            s.stack().setErrorHandler(t -> { done.run(); previous.accept(t); s.stack().setErrorHandler(previous); });
            s.stack().build(false, () -> { s.stack().setErrorHandler(previous); done.run(); s.log().info(m.get("gui.toolchain.status.built")); });
        }));
    }

    // ---- gateway ----

    private void renderGatewayTabs() {
        String selected = gatewayTabs.getSelectionModel().getSelectedItem() == null ? null : gatewayTabs.getSelectionModel().getSelectedItem().getText();
        gatewayTabs.getTabs().clear();
        for (GatewayInstanceInfo i : s.gateway().instances()) {
            Tab t = new Tab(i.id(), new GatewayPane(s.gateway(), b, i));
            t.setClosable(false);
            gatewayTabs.getTabs().add(t);
            if (i.id().equals(selected)) gatewayTabs.getSelectionModel().select(t);
        }
        gatewayStatusLabel.setText(s.gateway().instances().isEmpty() ? m.get("gui.gateway.none") : "");
    }

    @FXML private void onAddSystem() {
        new AddSystemDialog(stage, b, s.gateway().plugins()).showAndWait().ifPresent(r -> {
            s.gateway().addInstance(r.id(), r.plugin(), r.label(), r.hostname(), r.aliases(), r.scheme(), r.mode(), r.config());
            s.log().info(m.get("gui.gateway.restart.hint"));
            refreshCert();
        });
    }

    @FXML private void onRemoveSystem() {
        Tab t = gatewayTabs.getSelectionModel().getSelectedItem();
        if (t == null) return;
        if (dialogs.confirm(m.get("gui.gateway.remove.title"), m.get("gui.gateway.remove.text", t.getText()), m.get("gui.button.remove"))) {
            s.gateway().removeInstance(t.getText());
        }
    }

    @FXML private void onGatewayStatus() {
        s.gateway().status(true, h -> {
            StringBuilder sb = new StringBuilder("gateway " + h.get("version") + ": " + (Boolean.TRUE.equals(h.get("ok")) ? "ok" : "not ok"));
            if (h.get("instances") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> i) sb.append("\n  ").append(i.get("id")).append("  ").append(i.get("status")).append(i.get("upstream") == null ? "" : "  " + i.get("upstream"));
            gatewayStatusLabel.setText(sb.toString());
            s.log().info(sb.toString());
        }, t -> dialogs.error(Errors.map(t, m)));
    }

    @FXML private void onGatewayRestart() { s.gateway().restart(); }
    @FXML private void onGatewayRebuild() { s.gateway().rebuild(false); }
    @FXML private void onRenewCert() { s.gateway().certRenew(c -> { refreshCert(); s.log().info(m.get("gui.gateway.restart.hint")); }); }
    @FXML private void onRefreshGateway() { s.gateway().refresh(); refreshCert(); }

    private void refreshCert() {
        if (!s.ctx().isOpen()) return;
        var certs = s.ctx().sandbox().gatewayService().certGenerator();
        boolean exists = java.nio.file.Files.isRegularFile(certs.cert());
        List<String> hosts = s.ctx().sandbox().gatewayService().manifest().hostnames();
        gatewayCertLabel.setText(exists ? m.get("gui.gateway.cert.ok", hosts.isEmpty() ? "gateway" : String.join(", ", hosts)) : m.get("gui.gateway.cert.missing"));
        gatewayCertLabel.getStyleClass().removeAll("status-done", "status-warn");
        gatewayCertLabel.getStyleClass().add(exists ? "status-done" : "status-warn");
    }

    @FXML private void onInstallPlugin() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Plugin jar", "*.jar"));
        File f = fc.showOpenDialog(stage);
        if (f != null) s.gateway().pluginInstall(f.toPath());
    }

    // ---- agents ----

    @FXML private void onRefreshAgents() { s.agents().refresh(); }

    @FXML private void onLogin() {
        AgentInfo a = agentTable.getSelectionModel().getSelectedItem();
        if (a == null) return;
        List<String> modes = new ArrayList<>();
        try {
            modes.addAll(s.ctx().sandbox().agentService().adapter(a.id()).descriptor().authModes());
        } catch (RuntimeException ignored) {
            // no descriptor: the dialog offers the default mode only
        }
        if (modes.isEmpty()) modes.add("subscription");
        new LoginDialog(stage, b, s.agents(), a.id(), modes).showAndWait();
        s.agents().refresh();
    }

    /** «Local models»: the agent's own configuration for a model server on this machine, loaded before the dialog opens. */
    @FXML private void onLocalModels() {
        AgentInfo a = agentTable.getSelectionModel().getSelectedItem();
        if (AgentChoices.ownModels(a) == AgentChoices.OwnModels.UNSUPPORTED) return;
        // The listing carries the count the «Own models» column shows, so it is read again once the dialog saved.
        LocalModelsDialog.open(stage, b, s, a.id(), () -> s.agents().refresh());
    }

    @FXML private void onLogout() {
        AgentInfo a = agentTable.getSelectionModel().getSelectedItem();
        if (a != null && dialogs.confirm(m.get("gui.agents.logout.title"), m.get("gui.agents.logout.text", a.id()), m.get("gui.agents.logout.button"))) {
            s.agents().logout(a.id());
        }
    }

    /** Opens the usage view for the selected agent; the sub-tab switch reloads it. */
    @FXML private void onShowUsage() {
        AgentInfo a = agentTable.getSelectionModel().getSelectedItem();
        if (a != null && usageAgentChoice.getItems().contains(a.id())) usageAgentChoice.setValue(a.id());
        agentsTabs.getSelectionModel().select(usageTab);
    }

    @FXML private void onRefreshUsage() {
        loadUsage(usageAgentChoice.getValue());
    }
}
