package no.syntakserror.lyngvi.gui.view;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.Node;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;
import no.syntakserror.lyngvi.core.api.NewSessionOptions;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.config.RepoEntry;
import no.syntakserror.lyngvi.core.contract.SessionInfo;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.session.SessionExport;
import no.syntakserror.lyngvi.core.stack.ResourceSizes;
import no.syntakserror.lyngvi.gui.Services;
import no.syntakserror.lyngvi.gui.viewmodel.AgentRunBinder;
import no.syntakserror.lyngvi.gui.viewmodel.ConversationGroups;
import no.syntakserror.lyngvi.gui.viewmodel.ConversationRepos;
import no.syntakserror.lyngvi.gui.viewmodel.Drafts;
import no.syntakserror.lyngvi.gui.viewmodel.Errors;
import no.syntakserror.lyngvi.gui.viewmodel.LiveTurn;
import no.syntakserror.lyngvi.gui.viewmodel.SessionTitles;

/**
 * The chat: conversation list, transcript with live bubble, composer with attachments and model, stop, diff and
 * deliver (spec §9.4). In ephemeral mode («Ask the agent») there is no list and the session is removed on discard.
 *
 * <p>Any number of conversations can run at once. The viewmodel keeps a {@link LiveTurn} per running conversation;
 * this controller only renders the one that is open, and rebuilds its live bubble from that state when the user
 * switches back. Unsent text and attachments are kept per conversation in {@link Drafts}.
 */
public final class ChatController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Services s;
    private final Stage stage;
    private final ResourceBundle b;
    private final Messages m;
    private Dialogs dialogs;
    private FilesController files;
    private boolean ephemeral;
    private Conversation current;
    private final Drafts drafts = new Drafts();
    private final List<Path> attachments = new ArrayList<>();
    /**
     * The files of the turn just sent, by name: the core copies them into the session in the background, so for a
     * moment the bubble on screen can only show them from where they were attached.
     */
    private final Map<String, Path> sentAttachments = new HashMap<>();
    /** Where pasted images are written, made on the first paste and gone when the app exits. */
    private Path pasteDir;
    private TranscriptSelection selection;
    /** The open conversation's agent's models, as last read; the picker may replace it with a freshly fetched one. */
    private List<Model> catalogue = List.of();
    /**
     * The baseline the strip counted, and the one the diff window and the deliver dialog open on: empty for the
     * default (since the last delivery), {@code start} when that had nothing and the conversation's own changes
     * were counted from the session start instead.
     */
    private Optional<String> pendingFrom = Optional.empty();

    /** The nodes of the live bubble shown for the open conversation's running turn. */
    private record Live(VBox bubble, MarkdownArea markdown, WorkLogPane work, WorkLogModel model, Label usage) {}

    /** The conversation list as grouped rows; rebuilt whenever the sessions, the activity or the filter change. */
    private final ObservableList<ConversationGroups.Row> rows = FXCollections.observableArrayList();
    /** Set by «show all», cleared when the user starts a new conversation or types in the filter. */
    private boolean showAllConversations;

    @FXML private HBox sidebarBox;
    @FXML private VBox sidebar;
    @FXML private Region sidebarResizer;
    @FXML private ListView<ConversationGroups.Row> conversationList;
    @FXML private TextField conversationFilter;
    @FXML private Button newConversationButton;
    @FXML private Button newQuestionButton;
    @FXML private Label sessionTitleLabel;
    @FXML private Label containerStateLabel;
    @FXML private ScrollPane transcriptScroll;
    @FXML private VBox transcriptBox;
    @FXML private Pane transcriptHighlight;
    @FXML private Button scrollToBottomButton;
    @FXML private StackPane creatingOverlay;
    @FXML private Label creatingDetail;
    @FXML private Label creatingElapsed;
    @FXML private VBox composerBox;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private Label busyLabel;
    @FXML private HBox attachmentBar;
    @FXML private TextArea input;
    @FXML private Button attachButton;
    @FXML private ComboBox<AgentChoices.Choice> agentSelector;
    @FXML private ComboBox<ModelChoices.Choice> modelSelector;
    @FXML private Button favouriteButton;
    @FXML private Button browseModelsButton;
    @FXML private Button sendButton;
    @FXML private Button stopButton;
    @FXML private HBox changesStrip;
    @FXML private Label changesLabel;
    @FXML private Button exportButton;
    @FXML private Button showDiffButton;
    @FXML private Button deliverButton;
    @FXML private Label contextLabel;
    @FXML private Label costLabel;

    public ChatController(Services services, Stage stage) {
        this.s = services;
        this.stage = stage;
        this.b = services.bundle();
        this.m = services.messages();
    }

    @FXML
    private void initialize() {
        dialogs = new Dialogs(b, stage);
        initSidebarResize();
        conversationList.setItems(rows);
        conversationList.setCellFactory(v -> new SessionCell());
        // The list is refreshed every few seconds, and a running conversation's SessionInfo differs every time
        // (busy, tokens, lastActiveAt), so this fires without the selection having moved. Re-opening then would
        // throw away the composer's state, including the model the user picked for the next turn.
        conversationList.getSelectionModel().selectedItemProperty().addListener((o, a, row) -> {
            if (row instanceof ConversationGroups.More) {
                showAllConversations = true;
                rebuildRows();
            } else if (row instanceof ConversationGroups.Item item
                    && (current == null || !current.id().equals(item.info().id()))) {
                open(item.info().id());
            }
        });
        // the grouping itself depends on the sessions and on what is running or unseen, so both rebuild the rows
        s.sessions().sessions().addListener((ListChangeListener<SessionInfo>) c -> rebuildRows());
        s.sessions().activity().addListener(() -> s.async().onUi(this::rebuildRows));
        conversationFilter.textProperty().addListener((o, a, v) -> rebuildRows());
        rebuildRows();
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                onSend();
                e.consume();
            } else if (e.getCode() == KeyCode.V && e.isShortcutDown() && !e.isAltDown() && pasteAttachment()) {
                e.consume();
            }
        });
        input.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        input.setOnDragDropped(e -> {
            if (e.getDragboard().hasFiles()) e.getDragboard().getFiles().forEach(f -> addAttachment(f.toPath()));
            e.setDropCompleted(true);
            e.consume();
        });
        // the composer follows the prompt's height, so a long one is readable while it is written or pasted; the width
        // matters because the text wraps, and the font because the stylesheet sets it after the first measurement
        input.textProperty().addListener((o, a, v) -> fitInputToPrompt());
        input.widthProperty().addListener((o, a, v) -> fitInputToPrompt());
        input.fontProperty().addListener((o, a, v) -> fitInputToPrompt());
        transcriptBox.setMinWidth(0);
        selection = new TranscriptSelection(transcriptScroll, transcriptBox, transcriptHighlight);
        transcriptScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        transcriptBox.heightProperty().addListener((o, a, v) -> {
            if (pinnedOffset >= 0) {
                // a pane below the viewport's top changed height: keep that top line where it is
                transcriptScroll.setVvalue(TranscriptScroll.vvalue(pinnedOffset, v.doubleValue(), transcriptScroll.getViewportBounds().getHeight()));
                pinnedOffset = -1;
            } else if (autoScroll) {
                transcriptScroll.setVvalue(1.0);
            }
        });
        transcriptScroll.vvalueProperty().addListener((o, a, v) -> {
            autoScroll = TranscriptScroll.follows(v.doubleValue());
            scrollToBottomButton.setVisible(!autoScroll);
        });
        ModelDropdown.wire(modelSelector);
        modelSelector.valueProperty().addListener((o, a, v) -> updateFavouriteButton());
        agentSelector.setConverter(new StringConverter<>() {
            @Override public String toString(AgentChoices.Choice c) {
                return AgentChoices.label(c, b.getString("gui.session.new.agent.local"), b.getString("gui.agents.notloggedin"));
            }
            @Override public AgentChoices.Choice fromString(String s) { return null; }
        });
        agentSelector.valueProperty().addListener((o, a, v) -> onAgentChosen());
        updateFavouriteButton();
        // hint the send shortcut on the button: ⌘↵ on macOS, Ctrl+↵ on Windows/Linux (same key as the composer's filter)
        String shortcut = s.ctx().platform().os() == no.syntakserror.lyngvi.core.runtime.Platform.Os.MACOS ? "\u2318\u21B5" : "Ctrl+\u21B5";
        sendButton.setText(sendButton.getText() + "  " + shortcut);
        setRunning(false);
        showDiffButton.setDisable(true);
        deliverButton.setDisable(true);
        setSandboxOpen(false);
    }

    private boolean autoScroll = true;
    private double pinnedOffset = -1;

    /** Remembers where the viewport's top edge is, so the next height change restores it instead of following. */
    private void pinViewport() {
        pinnedOffset = TranscriptScroll.offset(transcriptScroll.getVvalue(), transcriptBox.getHeight(), transcriptScroll.getViewportBounds().getHeight());
    }

    /** Lays the prompt out off-scene, wrapped as the composer wraps it, to see how many rows it takes. */
    private final Text promptMeasure = new Text();
    private boolean measuringPrompt;

    /** How much narrower than the composer the text wraps: the content's padding (app.css) on both sides, and the caret. */
    private static final double INPUT_TEXT_INSETS = 22;

    /**
     * Sizes the composer to the prompt, between {@link ComposerRows#MIN} and {@link ComposerRows#MAX} rows, on the next
     * pulse: the width changes while the composer is being laid out, and {@code Parent.requestParentLayout} drops a
     * layout asked for then. Several changes at once (text and width) settle into one measurement.
     */
    private void fitInputToPrompt() {
        if (measuringPrompt) return;
        measuringPrompt = true;
        Platform.runLater(() -> {
            measuringPrompt = false;
            double wrapAt = input.getWidth() - INPUT_TEXT_INSETS;
            if (wrapAt <= 0) return; // not laid out yet: the width listener measures again once it is
            promptMeasure.setFont(input.getFont());
            promptMeasure.setWrappingWidth(0);
            promptMeasure.setText("X");
            double rowHeight = promptMeasure.getLayoutBounds().getHeight();
            String prompt = input.getText();
            // a text ending in a newline lays out without its empty last row, which is the row the caret is on
            promptMeasure.setWrappingWidth(wrapAt);
            promptMeasure.setText(prompt.endsWith("\n") ? prompt + " " : prompt);
            int rows = ComposerRows.of(promptMeasure.getLayoutBounds().getHeight(), rowHeight);
            if (rows == input.getPrefRowCount()) return;
            input.setPrefRowCount(rows);
            // TextArea turns the row count into a viewport height without asking for a layout, and the text area's own
            // layout requests stop at the skin's unmanaged viewport, so the composer would keep the height it has until
            // something else laid the window out
            input.requestLayout();
        });
    }

    // ---- the sidebar's width ----

    /** Where the pointer was when the drag started, and how wide the list was then; both in scene coordinates. */
    private double resizeFromX;
    private double resizeFromWidth;

    /**
     * Makes the handle to the right of the conversation list drag the list wider and narrower, within
     * {@link SidebarWidth}'s bounds, and remembers the width the user settles on. A double-click restores the default,
     * which is the way back when the list has been dragged to one of the bounds.
     */
    private void initSidebarResize() {
        sidebar.setMinWidth(SidebarWidth.MIN);
        setSidebarWidth(s.settings().sidebarWidth().orElse(SidebarWidth.DEFAULT));
        sidebarResizer.setOnMousePressed(e -> {
            resizeFromX = e.getSceneX();
            resizeFromWidth = sidebar.getWidth();
        });
        sidebarResizer.setOnMouseDragged(e -> setSidebarWidth(resizeFromWidth + e.getSceneX() - resizeFromX));
        sidebarResizer.setOnMouseReleased(e -> rememberSidebarWidth());
        sidebarResizer.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                setSidebarWidth(SidebarWidth.DEFAULT);
                rememberSidebarWidth();
            }
        });
    }

    private void setSidebarWidth(double width) {
        sidebar.setPrefWidth(SidebarWidth.clamp(width));
    }

    /** Written on release rather than on every drag event: one settings file write per drag, not one per pixel. */
    private void rememberSidebarWidth() {
        s.settings().setSidebarWidth(sidebar.getPrefWidth());
        s.settings().save();
    }

    public void setFilesController(FilesController files) {
        this.files = files;
    }

    public void setEphemeral(boolean ephemeral) {
        this.ephemeral = ephemeral;
        sidebarBox.setVisible(!ephemeral);
        sidebarBox.setManaged(!ephemeral);
        conversationFilter.setVisible(!ephemeral);
        conversationFilter.setManaged(!ephemeral);
        // an ephemeral conversation is discarded, so it has nothing to deliver
        if (ephemeral) showChangesStrip(false);
        // The conversation list is hidden here, and with it the button that starts one: «Ask the agent» has its
        // own in the header. Who answers is picked next to the composer instead of in a dialog, so a question can
        // be asked without a conversation existing first — the send makes one.
        newQuestionButton.setVisible(ephemeral);
        newQuestionButton.setManaged(ephemeral);
        agentSelector.setVisible(ephemeral);
        agentSelector.setManaged(ephemeral);
        setRunning(false);
    }

    public void setSandboxOpen(boolean open) {
        composerBox.setDisable(!open);
        updateStartButtons();
        // The agents are this sandbox's; the composer offers them as soon as it has one.
        if (open) loadAgents();
    }

    /**
     * «New conversation» and «New question»: out while there is no sandbox and while one is being made, and — in
     * the Ask composer, which has only ever the one conversation — while a question is being answered.
     */
    private void updateStartButtons() {
        boolean out = !s.ctx().isOpen() || creatingTimer != null || (ephemeral && answering);
        newConversationButton.setDisable(out);
        newQuestionButton.setDisable(out);
    }

    /**
     * The window is about to show another sandbox. The open conversation is let go of while the sandbox it
     * belongs to is still the open one — an ephemeral «Ask the agent» conversation is thrown away here, since
     * nothing in the other sandbox will ever show it again.
     */
    public void onSandboxLeaving() {
        if (current != null) {
            String id = current.id();
            s.sessions().unwatch(id);
            if (ephemeral && s.ctx().isOpen()) s.sessions().discard(id);
        }
        // Emptied here and not only when the new sandbox is open: if it cannot be opened, this window keeps the
        // one it has, and must not go on showing a conversation it has just let go of.
        onSandboxChanged();
    }

    /** The window now shows another sandbox: nothing on screen belongs to it, so the view starts empty. */
    public void onSandboxChanged() {
        // A conversation still being made belongs to the sandbox being left; this window will never show it.
        endCreating();
        current = null;
        selection.clear();
        transcriptBox.getChildren().clear();
        sessionTitleLabel.setText("");
        containerStateLabel.setText("");
        sentAttachments.clear();
        attachments.clear();
        attachmentBar.getChildren().clear();
        input.clear();
        drafts.clearAll(); // the drafts belong to conversations of a sandbox this window no longer has open
        catalogue = List.of();
        // The agents belong to the sandbox too; setSandboxOpen loads the new one's.
        agentSelector.getItems().clear();
        agentSelector.setValue(null);
        modelSelector.getItems().clear();
        showAllConversations = false;
        conversationFilter.clear();
        pendingFrom = Optional.empty();
        showChangesStrip(false);
        showDiffButton.setDisable(true);
        deliverButton.setDisable(true);
        setRunning(false);
        rebuildRows();
    }

    // ---- conversation list ----

    /**
     * Rebuilds the grouped rows from the sessions, what is running and what the user has not seen. Replacing the
     * items clears the selection, which fires the listener with {@code null}; the listener ignores that, and the
     * open conversation is selected again below without re-opening it.
     */
    private void rebuildRows() {
        var activity = s.sessions().activity();
        rows.setAll(ConversationGroups.rows(s.sessions().sessions(), activity::isRunning,
                id -> activity.isUnreadDone(id) || activity.isUnreadError(id), System.currentTimeMillis(),
                conversationFilter.getText(), showAllConversations));
        selectOpenConversation();
    }

    private void selectOpenConversation() {
        if (current == null) return;
        for (ConversationGroups.Row row : rows) {
            if (row instanceof ConversationGroups.Item item && item.info().id().equals(current.id())) {
                if (conversationList.getSelectionModel().getSelectedItem() != row) conversationList.getSelectionModel().select(row);
                return;
            }
        }
    }

    @FXML
    public void onNewConversation() {
        if (ephemeral) {
            // Nothing is made here: the question itself makes the conversation, so «New question» only clears
            // the board. Making one up front would start a container for a question that may never be asked.
            clearQuestion();
            return;
        }
        s.sessions().agents(agents -> {
            NewSessionDialog d = new NewSessionDialog(stage, b, m, s.sessions(), s.settings(), s.ctx().sandbox().paths().root(), s.config().repos(), agents,
                    s.ctx().sandbox().config().defaultAgent(), List.copyOf(s.config().resourcePresets()));
            d.showAndWait().ifPresent(options -> {
                beginCreating(chosenRepos(options));
                s.sessions().create(options, c -> {
                    conversationFilter.clear();
                    open(c.id(), this::endCreating);
                    rebuildRows();
                }, this::failedToCreate);
            });
        });
    }

    /**
     * The repositories the dialog asked for, by name: no list at all means every registered one, which is what
     * the core reads it as, and «no repositories» means none.
     */
    private List<String> chosenRepos(NewSessionOptions options) {
        if (options.noRepos()) return List.of();
        return options.repos().orElseGet(() -> s.config().repos().stream().map(RepoEntry::name).toList());
    }

    // ---- while a conversation is being created ----

    /** Reveals the overlay a second into the wait and then counts the seconds on it; null when nothing is pending. */
    private Timeline creatingTimer;
    private long creatingSince;

    /**
     * A conversation is being made. Creating one is a clone and a copy per repository, so it takes from a moment
     * to the better part of a minute, and until now nothing on screen said so. The card is held back for a second —
     * a conversation without working copies is made faster than that, and a spinner that flashes is worse than
     * none — and the seconds are counted on it afterwards, so a long wait is visibly a wait and not a hang.
     */
    private void beginCreating(List<String> repos) {
        creatingSince = System.currentTimeMillis();
        creatingDetail.setText(repos.isEmpty() ? b.getString("gui.chat.creating.norepos")
                : m.get("gui.chat.creating.repos", String.join(", ", repos)));
        creatingElapsed.setText("");
        stopCreatingTimer();
        creatingTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            showCreating(true);
            creatingElapsed.setText(m.get("gui.chat.creating.elapsed", (System.currentTimeMillis() - creatingSince) / 1000));
        }));
        creatingTimer.setCycleCount(Animation.INDEFINITE);
        creatingTimer.play();
        // one at a time: the buttons are the only way in here, and they stay out until the conversation is open
        updateStartButtons();
    }

    /** The conversation is created and open — or the sandbox changed under it, or it failed. */
    private void endCreating() {
        stopCreatingTimer();
        showCreating(false);
        updateStartButtons();
    }

    private void failedToCreate(Throwable t) {
        endCreating();
        s.log().warn("could not create the conversation: " + t.getMessage());
        dialogs.error(Errors.map(t, m));
    }

    private void stopCreatingTimer() {
        if (creatingTimer != null) creatingTimer.stop();
        creatingTimer = null;
    }

    private void showCreating(boolean show) {
        creatingOverlay.setVisible(show);
        creatingOverlay.setManaged(show);
    }

    /** Removes the ephemeral session, if any. */
    public void discardEphemeral() {
        if (ephemeral && current != null) {
            String id = current.id();
            drafts.clear(id);
            current = null;
            transcriptBox.getChildren().clear();
            s.sessions().remove(id, false);
        }
    }

    /** «New question»: the question on screen goes, the composer empties, and the agent and model stay as they are. */
    private void clearQuestion() {
        discardEphemeral();
        sessionTitleLabel.setText("");
        containerStateLabel.setText("");
        updateUsageLabels(null);
        input.clear();
        attachments.clear();
        attachmentBar.getChildren().clear();
        setRunning(false);
        input.requestFocus();
    }

    private void open(String id) {
        open(id, () -> {});
    }

    /** Opens the conversation and runs {@code onOpened} once it is on screen. */
    private void open(String id, Runnable onOpened) {
        // Re-opening the conversation that is already open (a finished turn does it) must not undo a model the
        // user has already picked for the next turn.
        String keepModel = current != null && current.id().equals(id) ? ModelDropdown.selected(modelSelector).orElse(null) : null;
        leaveCurrent();
        s.sessions().activity().seen(id);
        s.sessions().show(id, c -> {
            if (current != null && !current.id().equals(id)) leaveCurrent();
            current = c;
            sessionTitleLabel.setText(c.title());
            selectOpenConversation();
            renderTranscript(c);
            refreshPendingChanges();
            loadModels(c, keepModel);
            if (files != null && !ephemeral) files.showSession(c);
            restoreDraft(c.id());
            Optional<LiveTurn> turn = s.sessions().liveTurn(c.id());
            if (turn.isPresent()) {
                transcriptBox.getChildren().add(bubble(turn.get().userMessage()));
                Live live = startLiveBubble(turn.get());
                s.sessions().watch(c.id(), liveListener(c.id(), live));
            }
            setRunning(turn.isPresent());
            s.sessions().sessions().stream().filter(x -> x.id().equals(c.id())).findFirst().ifPresent(this::renderContainerState);
            onOpened.run();
        }, t -> {
            // The conversation could not be read: say so, and let the caller finish whatever it was waiting for —
            // a view left waiting for a conversation that will never arrive has nothing to take its spinner down.
            onOpened.run();
            s.log().warn("could not open session " + id + ": " + t.getMessage());
            dialogs.error(Errors.map(t, m));
        });
    }

    /** Keeps the draft of the conversation being left and stops rendering its turn; the turn itself keeps running. */
    private void leaveCurrent() {
        if (current == null) return;
        drafts.save(current.id(), input.getText(), attachments);
        // the transcript is rebuilt from the conversation, where the files sent are already the session's own
        sentAttachments.clear();
        s.sessions().unwatch(current.id());
    }

    private void restoreDraft(String id) {
        Drafts.Draft d = drafts.restore(id);
        input.setText(d.text());
        attachments.clear();
        attachmentBar.getChildren().clear();
        d.attachments().forEach(this::addAttachment);
    }

    private void renderContainerState(SessionInfo info) {
        String state = info.container() == null || info.container().state().isEmpty() ? m.get("stack.absent") : info.container().state();
        containerStateLabel.setText(info.agent() + "  ·  " + state + (info.busy() ? "  ·  " + b.getString("gui.chat.busy") : ""));
    }

    // ---- agent and model ----

    /**
     * The agents the «Ask the agent» composer offers: one row each, and a second row for an agent that has a model
     * server of the user's own, which answers with those models alone ({@link AgentChoices}). The chat tab has no
     * such selector — there the agent is settled when the conversation is made — and this is what lets a question
     * be asked here without one existing first.
     */
    private void loadAgents() {
        if (!ephemeral || !s.ctx().isOpen()) return;
        String wanted = agentSelector.getValue() != null ? agentSelector.getValue().id() : s.ctx().sandbox().config().defaultAgent();
        s.sessions().agents(agents -> {
            List<AgentChoices.Choice> rows = AgentChoices.of(agents.stream().filter(a -> a.enabled() && a.available()).toList());
            AgentChoices.Choice was = agentSelector.getValue();
            agentSelector.getItems().setAll(rows);
            agentSelector.setValue(AgentChoices.initial(rows, wanted));
            // Setting the value loads that agent's models; an unchanged (or still absent) one fires nothing.
            if (Objects.equals(was, agentSelector.getValue())) onAgentChosen();
        });
    }

    /** The agent the composer works against: the one picked in the Ask composer, else the open conversation's. */
    private String chosenAgent() {
        AgentChoices.Choice row = ephemeral ? agentSelector.getValue() : null;
        if (row != null) return row.id();
        return current == null ? null : current.agent();
    }

    /** The models the composer offers: the agent's whole catalogue, or — on its own server's row — only those. */
    private List<Model> offered() {
        return ephemeral ? AgentChoices.models(catalogue, agentSelector.getValue()) : catalogue;
    }

    /** Another agent to ask: its catalogue is read and the list starts on the model that agent was last asked with. */
    private void onAgentChosen() {
        AgentChoices.Choice row = agentSelector.getValue();
        // The server's own row already lists every model it has, so there is no wider catalogue to browse.
        browseModelsButton.setDisable(row == null || row.localOnly());
        catalogue = List.of();
        modelSelector.getItems().clear();
        if (row == null) {
            updateFavouriteButton();
            return;
        }
        s.sessions().models(row.id(), models -> {
            AgentChoices.Choice now = agentSelector.getValue();
            if (now == null || !now.id().equals(row.id())) return;  // the user moved on while this was being read
            catalogue = models;
            showModels(now.id(), initialModel(now));
        });
    }

    /**
     * Where the list starts for an agent row: the model that agent was last asked with, else the one it calls its
     * default. On the row for the user's own server the last model counts only when that server has it — anything
     * else is a model the row cannot answer with — and the server's first model stands in otherwise.
     */
    private String initialModel(AgentChoices.Choice row) {
        List<Model> models = offered();
        Optional<String> last = s.settings().lastModel(row.id());
        if (row.localOnly()) {
            return last.filter(id -> models.stream().anyMatch(x -> x.id().equals(id)))
                    .or(() -> models.stream().findFirst().map(Model::id)).orElse(null);
        }
        return last.orElseGet(() -> models.stream().filter(Model::isDefault).findFirst().map(Model::id).orElse(null));
    }

    /**
     * What is already picked when the same conversation is re-opened, else the conversation's own model, else the
     * one last used with this agent, else the agent's default. The list itself holds the starred models — the whole
     * catalogue is one click away in {@link ModelPickerDialog}.
     */
    private void loadModels(Conversation c, String keep) {
        // In the Ask composer the agent selector owns the list: the conversation was made from what it says, and
        // re-opening it after a turn must not pull the next question's choice back to what the last one used.
        if (ephemeral) return;
        s.sessions().models(c.agent(), models -> {
            catalogue = models;
            String wanted = keep != null ? keep
                    : c.model() != null ? c.model()
                      : s.settings().lastModel(c.agent())
                        .orElseGet(() -> models.stream().filter(Model::isDefault).findFirst().map(Model::id).orElse(null));
            showModels(c.agent(), wanted);
        });
    }

    private void showModels(String agent, String id) {
        ModelChoices.Groups groups = new ModelChoices.Groups(b.getString("gui.model.group.local"), b.getString("gui.model.group.other"));
        modelSelector.getItems().setAll(ModelChoices.dropdown(offered(), s.settings().favouriteModels(agent), id, groups));
        modelSelector.setValue(modelSelector.getItems().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null));
        updateFavouriteButton();
    }

    private void updateFavouriteButton() {
        ModelChoices.Choice c = modelSelector.getValue();
        boolean pickable = c != null && !c.heading() && chosenAgent() != null;
        boolean starred = pickable && c.favourite();
        favouriteButton.setDisable(!pickable);
        favouriteButton.setGraphic(Icon.of(starred ? "star-filled" : "star"));
        favouriteButton.setTooltip(new Tooltip(b.getString(starred ? "gui.model.unstar" : "gui.model.star")));
    }

    @FXML private void onToggleFavourite() {
        String id = ModelDropdown.selected(modelSelector).orElse(null);
        String agent = chosenAgent();
        if (agent == null || id == null) return;
        s.settings().setFavouriteModels(agent, ModelChoices.toggle(s.settings().favouriteModels(agent), id));
        s.settings().save();
        showModels(agent, id);
    }

    @FXML private void onBrowseModels() {
        String agent = chosenAgent();
        AgentChoices.Choice row = ephemeral ? agentSelector.getValue() : null;
        if (agent == null || (row != null && row.localOnly())) return;
        String selected = ModelDropdown.selected(modelSelector).orElse(null);
        ModelPickerDialog d = new ModelPickerDialog(stage, b, s.sessions(), agent, catalogue, s.settings().favouriteModels(agent), selected);
        Optional<String> chosen = d.showAndWait();
        catalogue = d.catalogue();
        s.settings().setFavouriteModels(agent, d.favourites());
        s.settings().save();
        showModels(agent, chosen.orElse(selected));
    }

    // ---- transcript ----

    private void renderTranscript(Conversation c) {
        transcriptBox.getChildren().clear();
        // An ephemeral «Ask the agent» conversation has no working copies and is not kept, so there is nothing
        // about its setup worth a line.
        if (!ephemeral) transcriptBox.getChildren().add(setupLog(c));
        for (Conversation.Message msg : c.messages()) transcriptBox.getChildren().add(bubble(msg));
        updateUsageLabels(c.messages().isEmpty() ? null : c.messages().getLast().usage());
        Platform.runLater(() -> transcriptScroll.setVvalue(1.0));
    }

    /**
     * What creating the conversation did, above the first message: when the session was made, which agent answers
     * in it, and which working copy each repository got. Read from the conversation, so it says the same thing
     * every time it is opened, and part of the transcript's text, so «Select all» copies it with the rest.
     */
    private VBox setupLog(Conversation c) {
        List<String> lines = SessionSetupLog.lines(c, m, ZoneId.systemDefault());
        VBox box = new VBox(2);
        box.getStyleClass().add("setup-log");
        for (String line : lines) {
            Label l = new Label(line);
            l.setWrapText(true);
            l.setMinWidth(0);
            box.getChildren().add(l);
        }
        VBox.setMargin(box, new Insets(2, 8, 8, 8));
        TranscriptSelection.setText(box, () -> String.join("\n", lines));
        return box;
    }

    private VBox bubble(Conversation.Message msg) {
        boolean user = "user".equals(msg.role());
        Label author = new Label((user ? b.getString("gui.chat.you") : (current == null ? "" : current.agent())) + " · " + TIME.format(Instant.ofEpochMilli(msg.at()).atZone(ZoneId.systemDefault())));
        author.getStyleClass().add("msg-author");
        VBox body = new VBox(4, author);
        String stop = "error".equals(msg.stopReason()) || "cancelled".equals(msg.stopReason()) ? b.getString("gui.chat.stop." + msg.stopReason()) : null;
        body.getStyleClass().addAll("msg-bubble", user ? "msg-user" : "msg-assistant");
        if (user) {
            Label text = new Label(msg.text());
            text.setWrapText(true);
            text.getStyleClass().add("msg-text");
            body.getChildren().add(text);
            if (msg.attachments() != null && !msg.attachments().isEmpty()) {
                body.getChildren().add(MessageAttachments.node(msg.attachments(), this::attachmentFile, this::openImage, b));
            }
        } else {
            WorkLogModel model = WorkLogModel.of(msg.work());
            if (!model.hasText()) {
                // conversations recorded before the timeline: text and tools were stored apart
                MarkdownArea md = new MarkdownArea();
                md.setMarkdown(msg.text());
                body.getChildren().add(md);
            }
            if (!model.isEmpty()) {
                WorkLogPane work = new WorkLogPane(b);
                work.setOnToggle(this::pinViewport);
                work.setModel(model);
                body.getChildren().add(work);
            }
            if (msg.usage() != null) {
                Label u = new Label(UsageFormatter.summary(msg.usage()));
                u.getStyleClass().add("msg-usage");
                body.getChildren().add(u);
            }
            if (stop != null) {
                Label st = new Label(stop);
                st.getStyleClass().addAll("msg-system", "error".equals(msg.stopReason()) ? "error" : "warning");
                body.getChildren().add(st);
            }
        }
        String copy = TranscriptText.message(author.getText(), msg.text(), msg.attachments(), msg.work(), stop);
        TranscriptSelection.setText(body, () -> copy);
        installMenu(body);
        VBox.setMargin(body, new Insets(4, user ? 8 : 60, 4, user ? 60 : 8));
        body.setMinWidth(0);
        body.setMaxWidth(Double.MAX_VALUE);
        return body;
    }

    /**
     * The attachment of this name on this machine: the copy the core keeps with the session, or — for the turn
     * just sent, whose files are still being copied there — the file the human attached. Null when neither is
     * there, which leaves the attachment as a chip.
     */
    private Path attachmentFile(String name) {
        Path sent = sentAttachments.get(name);
        if (sent != null && Files.isReadable(sent)) return sent;
        if (current == null) return null;
        return s.sessions().attachments(current.id()).map(dir -> dir.resolve(name)).orElse(null);
    }

    private void openImage(Path file) {
        new ImageWindow(stage, file, s.settings().theme(), b).show();
    }

    /**
     * Right-click on a bubble: copy the selected text when the bubble is part of a selection, or the whole message.
     * A selected bubble takes the event before its markdown and tool panes, so their own copy menus apply only to a
     * bubble the selection does not touch.
     */
    private void installMenu(VBox bubble) {
        bubble.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
            if (selection.covers(bubble)) { menu(bubble).show(bubble, e.getScreenX(), e.getScreenY()); e.consume(); }
        });
        bubble.setOnContextMenuRequested(e -> { menu(bubble).show(bubble, e.getScreenX(), e.getScreenY()); e.consume(); });
    }

    private ContextMenu menu(VBox bubble) {
        ContextMenu menu = new ContextMenu();
        if (selection.covers(bubble)) {
            MenuItem copySelection = new MenuItem(b.getString("gui.chat.copy.selection"));
            copySelection.setOnAction(e -> selection.copy());
            menu.getItems().add(copySelection);
        }
        MenuItem copyOne = new MenuItem(b.getString("gui.chat.copy.message"));
        copyOne.setOnAction(e -> selection.copyMessage(bubble));
        menu.getItems().add(copyOne);
        MenuItem all = new MenuItem(b.getString("gui.chat.selectall"));
        all.setOnAction(e -> { selection.selectAll(); transcriptScroll.requestFocus(); });
        menu.getItems().add(all);
        return menu;
    }

    @FXML private void onScrollToBottom() {
        transcriptScroll.setVvalue(1.0);
        autoScroll = true;
    }

    // ---- composer ----

    /**
     * Sends what is in the composer. In the Ask composer a question whose agent is not the one answering the
     * conversation on screen — including the first question, where there is no conversation at all — gets one of
     * its own, since a conversation is tied to the agent that answers it.
     */
    @FXML private void onSend() {
        if (input.getText().isBlank()) return;
        if (current != null && s.sessions().isRunning(current.id())) return;
        boolean fresh = ephemeral && !AgentChoices.answers(agentSelector.getValue(), current == null ? null : current.agent());
        if (current == null && !fresh) return;
        String prompt = input.getText().strip();
        List<Path> files = List.copyOf(attachments);
        // Emptied before the conversation is made and not inside send(): opening the new one restores its own
        // (empty) draft into the composer, so what the user typed is carried along rather than read back from it.
        input.clear();
        attachments.clear();
        attachmentBar.getChildren().clear();
        if (current != null) drafts.clear(current.id());
        if (fresh) askFreshly(prompt, files); else send(prompt, files);
    }

    /**
     * The question goes to a conversation of its own, asking the agent picked next to the composer: the one on
     * screen is thrown away first — «Ask the agent» keeps nothing — and what was typed goes back into the composer
     * if the conversation cannot be made.
     */
    private void askFreshly(String prompt, List<Path> files) {
        discardEphemeral();
        AgentChoices.Choice row = agentSelector.getValue();
        beginCreating(List.of());
        s.sessions().create(new NewSessionOptions(Optional.of(b.getString("gui.ask.title")), Optional.empty(),
                        row == null ? Optional.empty() : Optional.of(row.id()), ModelDropdown.selected(modelSelector),
                        Optional.empty(), Optional.empty(), Optional.empty(), true),
                c -> open(c.id(), () -> { endCreating(); send(prompt, files); }),
                t -> {
                    // Unless the user has started typing the next one in the meantime: their text wins over a
                    // question that was not asked.
                    if (input.getText().isBlank()) input.setText(prompt);
                    files.forEach(this::addAttachment);
                    failedToCreate(t);
                });
    }

    private void send(String prompt, List<Path> files) {
        String id = current.id();
        Optional<String> model = ModelDropdown.selected(modelSelector);
        model.ifPresent(chosen -> {
            s.settings().setLastModel(current.agent(), chosen);
            s.settings().save();
        });
        // the first prompt names a conversation the user did not name; a later rename still wins
        if (current.messages().isEmpty() && SessionTitles.isGenerated(current.title())) {
            String title = SessionTitles.fromPrompt(prompt);
            if (!title.isBlank()) {
                sessionTitleLabel.setText(title);
                s.sessions().rename(id, title);
            }
        }
        files.forEach(p -> sentAttachments.put(p.getFileName().toString(), p));
        Conversation.Message userMsg = new Conversation.Message("live-user", "user", System.currentTimeMillis(), prompt,
                files.stream().map(p -> p.getFileName().toString()).toList(), List.of(), null, null);
        transcriptBox.getChildren().add(bubble(userMsg));
        Live live = startLiveBubble(null);
        setRunning(true);
        AgentRunBinder binder = s.sessions().ask(id, prompt, files, model, liveListener(id, live));
        if (binder == null || binder.isFinished()) setRunning(false);
    }

    /** Renders one running turn into the given live nodes; every callback carries the full state so far. */
    private AgentRunBinder.Listener liveListener(String id, Live live) {
        return new AgentRunBinder.Listener() {
            @Override public void onText(String full) { if (!live.model().hasText()) live.markdown().setMarkdown(full); }
            @Override public void onReasoning(String full) { live.model().reasoning(full); live.work().setModel(live.model()); }
            @Override public void onWork(List<Conversation.WorkItem> work) {
                live.model().work(work);
                live.work().setModel(live.model());
                boolean inline = live.model().hasText();
                live.markdown().setVisible(!inline);
                live.markdown().setManaged(!inline);
            }
            @Override public void onNotice(String level, String text) { live.model().notice(level, text); live.work().setModel(live.model()); }
            @Override public void onUsage(Conversation.Usage usage) { live.usage().setText(UsageFormatter.summary(usage)); updateUsageLabels(usage); }
            @Override public void onDone(AgentRunBinder.Outcome outcome) {
                if (current != null && current.id().equals(id)) { setRunning(false); open(id); } else s.sessions().refresh();
                if (outcome.error() != null && !outcome.cancelled()) s.log().warn("agent run failed: " + outcome.error());
            }
        };
    }

    /** Adds the live bubble to the transcript; with a {@link LiveTurn} it starts from that turn's state so far. */
    private Live startLiveBubble(LiveTurn turn) {
        long at = turn == null ? System.currentTimeMillis() : turn.startedAt();
        Label author = new Label((current == null ? "" : current.agent()) + " · " + TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())));
        author.getStyleClass().add("msg-author");
        MarkdownArea markdown = new MarkdownArea();
        WorkLogModel model = new WorkLogModel();
        WorkLogPane work = new WorkLogPane(b);
        work.setOnToggle(this::pinViewport);
        Label usage = new Label();
        usage.getStyleClass().add("msg-usage");
        VBox bubble = new VBox(4, author, markdown, work, usage);
        bubble.getStyleClass().addAll("msg-bubble", "msg-assistant", "msg-live");
        bubble.setMinWidth(0);
        bubble.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(bubble, new Insets(4, 60, 4, 8));
        transcriptBox.getChildren().add(bubble);
        autoScroll = true;
        TranscriptSelection.setText(bubble, () -> TranscriptText.message(author.getText(), markdown.getMarkdown(), List.of(), model.work(), null));
        installMenu(bubble);
        Live live = new Live(bubble, markdown, work, model, usage);
        if (turn != null) {
            AgentRunBinder.Listener replay = liveListener("", live);
            if (!turn.reasoning().isEmpty()) replay.onReasoning(turn.reasoning());
            turn.notices().forEach(n -> replay.onNotice(n.level(), n.text()));
            if (!turn.work().isEmpty()) replay.onWork(turn.work());
            if (!turn.text().isEmpty()) replay.onText(turn.text());
            if (turn.usage() != null) replay.onUsage(turn.usage());
        }
        return live;
    }

    private void setRunning(boolean running) {
        answering = running;
        busyIndicator.setVisible(running);
        busyIndicator.setManaged(running);
        busyLabel.setVisible(running);
        busyLabel.setManaged(running);
        stopButton.setVisible(running);
        stopButton.setManaged(running);
        // The Ask composer writes to a conversation it has not made yet, so it is usable before there is one.
        boolean canAsk = current != null || ephemeral;
        sendButton.setDisable(running || !canAsk);
        input.setDisable(!canAsk);
        updateStartButtons();
    }

    /** Whether the open conversation is answering; the Ask tab has only the one, so «New question» waits for it. */
    private boolean answering;

    @FXML private void onStop() {
        if (current != null) s.sessions().stop(current.id());
    }

    @FXML private void onAttach() {
        FileChooser fc = new FileChooser();
        fc.setTitle(b.getString("gui.chat.attach"));
        List<File> chosen = fc.showOpenMultipleDialog(stage);
        if (chosen != null) chosen.forEach(f -> addAttachment(f.toPath()));
    }

    /**
     * ⌘/Ctrl+V in the composer: an image on the clipboard is written as a PNG and attached, and copied files are
     * attached as they are — the agent reads attachments as files, not as pixels in the prompt. Text wins whenever
     * the clipboard holds any (several apps offer an image flavour alongside the text they copy), so ordinary
     * pasting is untouched; {@code false} then leaves the event to the text area.
     */
    private boolean pasteAttachment() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) return false;
        if (clipboard.hasFiles()) {
            clipboard.getFiles().forEach(f -> addAttachment(f.toPath()));
            return true;
        }
        if (!clipboard.hasImage()) return false;
        Image image = clipboard.getImage();
        if (image == null) return false;
        try {
            if (pasteDir == null) {
                pasteDir = Files.createTempDirectory("lyngvi-paste");
                pasteDir.toFile().deleteOnExit();
            }
            Path file = ClipboardImage.write(image, pasteDir, Instant.now());
            file.toFile().deleteOnExit();  // the send copies it into the session; the original is scratch
            addAttachment(file);
        } catch (IOException e) {
            s.log().warn("could not paste the clipboard image: " + e);
            dialogs.error(Errors.map(e, m));
        }
        return true;
    }

    private void addAttachment(Path p) {
        if (attachments.contains(p)) return;
        attachments.add(p);
        Label chip = new Label("📎 " + p.getFileName());
        chip.getStyleClass().add("attachment-chip");
        Button remove = new Button(null, Icon.of("x"));
        remove.getStyleClass().addAll("ghost", "icon-button", "attachment-remove");
        HBox box = new HBox(2, chip, remove);
        box.setAlignment(Pos.CENTER_LEFT);
        remove.setOnAction(e -> { attachments.remove(p); attachmentBar.getChildren().remove(box); });
        attachmentBar.getChildren().add(box);
    }

    private void updateUsageLabels(Conversation.Usage usage) {
        contextLabel.setText(usage == null ? "" : b.getString("gui.chat.context") + " " + UsageFormatter.context(usage.contextUsed(), usage.contextMax()));
        contextLabel.getStyleClass().removeAll("context-warn", "context-danger");
        double ratio = usage == null ? 0 : UsageFormatter.contextRatio(usage.contextUsed(), usage.contextMax());
        if (ratio >= 0.9) contextLabel.getStyleClass().add("context-danger");
        else if (ratio >= 0.75) contextLabel.getStyleClass().add("context-warn");
        costLabel.setText(usage == null || usage.costUsd() == null ? "" : UsageFormatter.cost(usage.costUsd()));
    }

    // ---- diff / deliver ----

    private void showChangesStrip(boolean show) {
        changesStrip.setVisible(show);
        changesStrip.setManaged(show);
    }

    /**
     * Counts what is waiting for delivery and shows it above the composer. This is the same review the deliver
     * dialog runs, so the two can never disagree; «no changes» comes back as an error from the core, which here
     * means an empty strip rather than a dialog.
     */
    private void refreshPendingChanges() {
        if (ephemeral || current == null || current.repos().isEmpty()) {
            showChangesStrip(false);
            return;
        }
        showChangesStrip(true);
        changesLabel.setText(b.getString("gui.chat.changes.checking"));
        deliverButton.setDisable(true);
        pendingFrom = Optional.empty();
        // The default baseline is the last delivery. When nothing has happened since it, fall back to the session
        // start: a turn that changed no files must not take delivery away from what the conversation has already
        // produced, and a delivery can be wanted again — on another branch, or after the host repository moved.
        reviewPending(current.id(), Optional.empty(), !current.deliveries().isEmpty());
    }

    /** One review from {@code from}; when it comes back empty and {@code fallBackToStart}, repeats it from the start. */
    private void reviewPending(String id, Optional<String> from, boolean fallBackToStart) {
        s.sessions().review(id, from, r -> {
            if (current == null || !current.id().equals(id)) return;
            PendingChanges pending = PendingChanges.of(r);
            if (pending.isEmpty() && fallBackToStart) reviewPending(id, Optional.of("start"), false);
            else renderPendingChanges(pending, from);
        }, t -> {
            if (current == null || !current.id().equals(id)) return;
            // «nothing to deliver» arrives as an error from the core, and is the normal case for a fresh
            // conversation; anything else is said plainly rather than shown as an empty strip.
            Errors.UiError e = Errors.map(t, m);
            if (e.kind() != Errors.Kind.NO_CHANGES) {
                deliverButton.setDisable(true);
                showDiffButton.setDisable(false);
                changesLabel.setText(e.message());
            } else if (fallBackToStart) {
                reviewPending(id, Optional.of("start"), false);
            } else {
                renderPendingChanges(PendingChanges.NONE, from);
            }
        });
    }

    private void renderPendingChanges(PendingChanges pending, Optional<String> from) {
        pendingFrom = pending.isEmpty() ? Optional.empty() : from;
        deliverButton.setDisable(pending.isEmpty());
        showDiffButton.setDisable(pending.isEmpty());
        if (pending.isEmpty()) {
            changesLabel.setText(b.getString("gui.chat.changes.none"));
            return;
        }
        String text = m.get("gui.chat.changes", pending.files(), pending.additions(), pending.deletions());
        if (pending.held() > 0) text += "  ·  " + m.get("gui.chat.changes.held", pending.held());
        // Only said when the counts are not the usual «since the last delivery», so the number is never ambiguous.
        if (from.isPresent()) text += "  ·  " + b.getString("gui.chat.changes.fromstart");
        changesLabel.setText(text);
    }

    @FXML private void onShowDiff() {
        if (current != null) new DiffWindow(stage, b, s.sessions(), current, s.settings().theme(), pendingFrom).show();
    }

    /**
     * «Export ZIP»: the whole session written to one archive on this machine (spec §5.9). The way out when a
     * delivery cannot be made to work, so it is never disabled by what a review found — a review that says
     * «no changes», or fails, is one of the reasons to reach for it — and it leaves the session untouched.
     */
    @FXML private void onExportZip() {
        if (current == null) return;
        String id = current.id();
        new ExportDialog(stage, b, exportDirectory(), SessionExport.fileName(current.title(), System.currentTimeMillis()))
                .showAndWait().ifPresent(options -> {
                    exportButton.setDisable(true);
                    changesLabel.setText(b.getString("gui.export.running"));
                    s.sessions().export(id, options, r -> {
                        exportButton.setDisable(false);
                        StringBuilder sb = new StringBuilder(m.get("gui.export.done", r.files(), ResourceSizes.format(r.bytes()), r.path()));
                        if (!r.excluded().isEmpty()) sb.append('\n').append(m.get("gui.export.done.excluded", String.join(", ", r.excluded())));
                        if (r.partial()) sb.append('\n').append(b.getString("gui.export.done.partial"));
                        refreshPendingChanges();
                        dialogs.info(b.getString("gui.export.done.title"), sb.toString());
                    }, t -> {
                        exportButton.setDisable(false);
                        refreshPendingChanges();
                        dialogs.error(Errors.map(t, m));
                    });
                });
    }

    /** Where the file dialog starts: the downloads folder when there is one, the home directory otherwise. */
    private Path exportDirectory() {
        Path home = s.ctx().platform().home();
        Path downloads = home.resolve("Downloads");
        return Files.isDirectory(downloads) ? downloads : home;
    }

    @FXML private void onDeliver() {
        if (current == null) return;
        new DeliverDialog(stage, b, s.sessions(), s.config(), current, dialogs, pendingFrom).showAndWait().ifPresent(result -> {
            StringBuilder sb = new StringBuilder();
            result.repos().forEach((repo, out) -> sb.append(m.get("apply.repo", repo, out.files(), out.branch(),
                    out.hostCommit() == null ? m.get("apply.staged") : out.hostCommit())).append('\n'));
            result.notices().forEach(n -> sb.append(n).append('\n'));
            dialogs.info(b.getString("gui.deliver.done.title"), sb.toString().strip());
            open(current.id());
        });
    }

    /**
     * «Add repository…» from a conversation's context menu: the registered repositories that conversation
     * does not have a working copy of yet. The copy is built into the session's volume, which is the whole
     * of {@code /work} in its container, so it can be added to a conversation that is already running — the
     * core refuses only while the agent is in the middle of a turn.
     */
    private void onAddRepos(SessionInfo info) {
        List<RepoEntry> available = s.config().repos().stream().filter(r -> !info.repos().contains(r.name())).toList();
        if (available.isEmpty()) {
            dialogs.info(b.getString("gui.chat.addrepo"), b.getString("gui.chat.addrepo.none"));
            return;
        }
        new AddReposDialog(stage, b, m, info.title(), available).showAndWait().ifPresent(options ->
                s.sessions().addRepos(info.id(), options, c -> {
                    // The open conversation's file tree and repository tags both come from the conversation.
                    if (current != null && current.id().equals(c.id())) open(c.id());
                    rebuildRows();
                    dialogs.info(b.getString("gui.chat.addrepo"), m.get("gui.chat.addrepo.done", String.join(", ", options.repos())));
                }));
    }

    // ---- list cell with context menu ----

    private final class SessionCell extends ListCell<ConversationGroups.Row> {
        @Override
        protected void updateItem(ConversationGroups.Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().remove("conv-static");
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            setText(null);
            switch (row) {
                case ConversationGroups.Header h -> header(h);
                case ConversationGroups.More more -> more(more);
                case ConversationGroups.Item item -> item(item);
            }
        }

        private void header(ConversationGroups.Header h) {
            getStyleClass().add("conv-static");
            Label name = new Label(b.getString(h.group().key()).toUpperCase(java.util.Locale.ROOT));
            name.getStyleClass().add("conv-group");
            Label count = new Label(String.valueOf(h.count()));
            count.getStyleClass().addAll("conv-group", "conv-group-count");
            HBox box = new HBox(6, name);
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().add(count);
            box.setAlignment(Pos.CENTER_LEFT);
            setGraphic(box);
            setContextMenu(null);
        }

        private void more(ConversationGroups.More more) {
            getStyleClass().add("conv-static");
            Label label = new Label(m.get("gui.chat.showall", more.hidden()));
            label.getStyleClass().add("conv-more");
            setGraphic(label);
            setContextMenu(null);
        }

        private void item(ConversationGroups.Item it) {
            SessionInfo info = it.info();
            Label dot = new Label();
            dot.getStyleClass().addAll("conv-dot", dotStyle(info, it.group()));
            Label title = new Label(info.title());
            title.getStyleClass().add("session-title");
            title.setMinWidth(0);
            title.setMaxWidth(Double.MAX_VALUE);
            HBox head = new HBox(6, dot, title);
            head.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(title, Priority.ALWAYS);
            status(info.id()).ifPresent(head.getChildren()::add);
            Label sub = new Label(meta(info, it.group()));
            sub.getStyleClass().add("session-subtitle");
            sub.setMinWidth(0);
            VBox box = new VBox(1, head);
            repos(info).ifPresent(box.getChildren()::add);
            box.getChildren().add(sub);
            setGraphic(box);
            setContextMenu(menuFor(info));
        }

        /**
         * Which repositories the conversation works in: the same title means a different job in each of them, and
         * the list is the only place the user can see which without opening the conversation. Nothing for a
         * conversation that has none, so «Ask the agent» keeps its two lines.
         */
        private Optional<Node> repos(SessionInfo info) {
            ConversationRepos.Tags tags = ConversationRepos.of(info.repos());
            if (tags.isEmpty()) return Optional.empty();
            HBox row = new HBox(4);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinWidth(0);
            for (String repo : tags.shown()) row.getChildren().add(repoTag(repo));
            if (tags.more() > 0) {
                Label more = repoTag(m.get("gui.chat.repos.more", tags.more()));
                more.getStyleClass().add("conv-repo-more");
                row.getChildren().add(more);
            }
            // names are cut to the width of the sidebar, so the whole list is one hover away
            Tooltip.install(row, new Tooltip(String.join(", ", info.repos())));
            return Optional.of(row);
        }

        private Label repoTag(String text) {
            Label tag = new Label(text);
            tag.getStyleClass().add("conv-repo");
            // a name wider than the sidebar is ellipsized rather than widening the list
            tag.setMinWidth(0);
            return tag;
        }

        /**
         * One line under the title, and only one number on it: what the conversation is doing, then what the user
         * would look for in that state — time for a finished one, what it cost for one that ran.
         */
        private String meta(SessionInfo info, ConversationGroups.Group group) {
            String spend = UsageFormatter.spend(info.usage());
            String state = switch (group) {
                case RUNNING -> b.getString("gui.chat.busy");
                case WAITING -> s.sessions().activity().isUnreadError(info.id())
                        ? b.getString("gui.chat.list.failed") : b.getString("gui.chat.list.done");
                case RECENT, OLDER -> {
                    RelativeTime.Age age = RelativeTime.of(System.currentTimeMillis() - info.lastActiveAt());
                    yield age.value() == 0 ? b.getString(age.key()) : m.get(age.key(), age.value());
                }
            };
            String deliveries = info.deliveries() > 0 ? info.deliveries() + " " + b.getString("gui.chat.deliveries") : "";
            return String.join(" · ", List.of(state, deliveries.isEmpty() ? spend : deliveries).stream().filter(x -> !x.isBlank()).toList());
        }

        private String dotStyle(SessionInfo info, ConversationGroups.Group group) {
            return switch (group) {
                case RUNNING -> "running";
                case WAITING -> s.sessions().activity().isUnreadError(info.id()) ? "failed" : "waiting";
                case RECENT, OLDER -> info.deliveries() > 0 ? "delivered" : "idle";
            };
        }

        private ContextMenu menuFor(SessionInfo info) {
            ContextMenu menu = new ContextMenu();
            MenuItem rename = new MenuItem(b.getString("gui.chat.rename"));
            rename.setOnAction(e -> dialogs.textInput(b.getString("gui.chat.rename"), b.getString("gui.chat.rename.header"), info.title()).ifPresent(t -> s.sessions().rename(info.id(), t)));
            // A conversation often turns out to need one more repository than it started with. The working
            // copy can be added while it runs — /work is the session's volume — so this is here and not
            // only in «New conversation».
            MenuItem addRepo = new MenuItem(b.getString("gui.chat.addrepo"));
            addRepo.setOnAction(e -> onAddRepos(info));
            MenuItem stopContainer = new MenuItem(b.getString("gui.chat.stopcontainer"));
            stopContainer.setOnAction(e -> s.sessions().stopContainer(info.id()));
            MenuItem remove = new MenuItem(b.getString("gui.chat.remove"));
            remove.setOnAction(e -> {
                if (dialogs.confirm(b.getString("gui.chat.remove"), m.get("gui.chat.remove.text", info.title()), b.getString("gui.button.remove"))) {
                    if (current != null && current.id().equals(info.id())) { current = null; transcriptBox.getChildren().clear(); }
                    s.sessions().remove(info.id(), false);
                }
            });
            menu.getItems().addAll(rename, addRepo, stopContainer, remove);
            return menu;
        }

        /** A spinner while the agent works; «Done» or «Failed» once it has finished, until the user opens the conversation. */
        private Optional<Node> status(String id) {
            var activity = s.sessions().activity();
            if (activity.isRunning(id)) {
                ProgressIndicator spinner = new ProgressIndicator();
                spinner.setPrefSize(14, 14);
                spinner.setMinSize(14, 14);
                spinner.setMaxSize(14, 14);
                spinner.getStyleClass().add("session-spinner");
                return Optional.of(spinner);
            }
            if (activity.isUnreadDone(id)) return Optional.of(statusLabel(b.getString("gui.chat.list.done"), "check", "done"));
            if (activity.isUnreadError(id)) return Optional.of(statusLabel(b.getString("gui.chat.list.failed"), "x", "failed"));
            return Optional.empty();
        }

        private Label statusLabel(String text, String icon, String style) {
            Label l = new Label(text, Icon.of(icon));
            l.getStyleClass().addAll("session-status", style);
            return l;
        }
    }
}
