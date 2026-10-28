package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import no.syntakserror.lyngvi.core.api.ApplyOptions;
import no.syntakserror.lyngvi.core.contract.ApplyResult;
import no.syntakserror.lyngvi.core.contract.FileChange;
import no.syntakserror.lyngvi.core.contract.ReviewResult;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.gui.viewmodel.ConfigViewModel;
import no.syntakserror.lyngvi.gui.viewmodel.Errors;
import no.syntakserror.lyngvi.gui.viewmodel.SessionViewModel;

/**
 * «Deliver changes»: baseline, the three buckets (apply / held / rejected with reason) beside the diff of the
 * selected file, held files that may be included, branch (with a suggestion), message, commit or stage, then apply
 * (spec §9.3, §5.6–5.8). The diff is the same view as «Git Diff», so the decision is made on the change itself and
 * not on a line count.
 */
public final class DeliverDialog extends Dialog<ApplyResult> {

    private final SessionViewModel sessions;
    private final ResourceBundle b;
    private final String sessionId;
    private final ComboBox<String> from = new ComboBox<>();
    private final ListView<DeliverFiles.Entry> applyList = new ListView<>();
    private final ListView<DeliverFiles.Entry> holdList = new ListView<>();
    private final ListView<DeliverFiles.Entry> rejectList = new ListView<>();
    private final List<ListView<DeliverFiles.Entry>> lists = List.of(applyList, holdList, rejectList);
    private final TitledPane applyPane;
    private final TitledPane holdPane;
    private final TitledPane rejectPane;
    private final VBox altBox = new VBox(4);
    private final VBox hostBox = new VBox(2);
    /** The standing warning about everything the delivery would leave behind; empty and hidden when it leaves none. */
    private final VBox warnBox = new VBox(4);
    /** The held files that are ticked, by {@link DeliverFiles.Entry#key()} so a reload keeps the ticks and two
     * repositories with the same path are told apart. */
    private final Set<String> included = new LinkedHashSet<>();
    /** The parsed patches, by {@link DeliverFiles.Entry#key()}; what the right-hand pane draws. */
    private final Map<String, DiffPaneBuilder.FileDiff> diffs = new HashMap<>();
    private final StackPane diffHolder = new StackPane();
    private final Label diffHint = new Label();
    private final ToggleButton whole;
    private final ComboBox<String> branch = new ComboBox<>();
    private final Label branchHint = new Label();
    private final ConfigViewModel config;
    private final Conversation conversation;
    private final TextField message = new TextField();
    private final RadioButton commit;
    private final RadioButton stage;
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label summary = new Label();
    private final Dialogs dialogs;
    private ReviewResult review;
    private final ButtonType applyType;
    /** Bumped by every load, so a slow reply from an earlier one is dropped instead of overwriting a newer one. */
    private int generation;
    private int diffGeneration;
    private int pendingDiffs;
    private boolean syncing;
    private DeliverFiles.Entry selected;

    /**
     * @param initialFrom the baseline to open on, empty for the newest delivery; the chat strip passes the one it
     *                    counted, so the dialog shows the same changes the button offered to deliver.
     */
    public DeliverDialog(Window owner, ResourceBundle b, SessionViewModel sessions, ConfigViewModel config, Conversation c,
                         Dialogs dialogs, Optional<String> initialFrom) {
        this.sessions = sessions;
        this.config = config;
        this.b = b;
        this.sessionId = c.id();
        this.conversation = c;
        this.dialogs = dialogs;
        initOwner(owner);
        setTitle(b.getString("gui.deliver.title"));
        setHeaderText(b.getString("gui.deliver.header"));
        setResizable(true);
        applyType = new ButtonType(b.getString("gui.deliver.apply"), ButtonType.OK.getButtonData());
        getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);
        getDialogPane().setPrefSize(1120, 760);
        matchOwner(owner);

        BaselineChoices.fill(from, c, b);
        // set before the listener is added: the first load happens at the end of the constructor, once
        initialFrom.map(v -> BaselineChoices.canonical(v, c)).filter(v -> from.getItems().contains(v)).ifPresent(from::setValue);
        from.valueProperty().addListener((o, a, v) -> load());
        Button refresh = new Button(b.getString("gui.button.refresh"));
        refresh.setOnAction(e -> load());
        whole = new ToggleButton(b.getString("gui.diff.wholefile"));
        whole.setTooltip(new Tooltip(b.getString("gui.diff.wholefile.tip")));
        whole.selectedProperty().addListener((o, a, v) -> loadDiffs());
        HBox fromRow = new HBox(8, new Label(b.getString("gui.deliver.from")), from, refresh, whole, spinner, summary);
        fromRow.setAlignment(Pos.CENTER_LEFT);
        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);

        applyPane = new TitledPane(b.getString("gui.deliver.bucket.apply"), bucketList(applyList, 240, false));
        holdPane = new TitledPane(b.getString("gui.deliver.bucket.hold"), bucketList(holdList, 140, true));
        holdPane.setExpanded(false);
        rejectPane = new TitledPane(b.getString("gui.deliver.bucket.reject"), bucketList(rejectList, 140, false));
        rejectPane.setExpanded(false);
        for (ListView<DeliverFiles.Entry> list : lists) {
            list.getSelectionModel().selectedItemProperty().addListener((o, a, v) -> pick(list, v));
        }
        altBox.setPadding(new Insets(0, 0, 4, 0));
        warnBox.getStyleClass().add("deliver-warning");
        visible(altBox, false);
        visible(hostBox, false);
        visible(warnBox, false);

        diffHolder.getStyleClass().add("diff-area");
        diffHolder.setPadding(new Insets(12));
        diffHolder.setMinWidth(260);
        diffHint.getStyleClass().add("diff-empty");
        diffHint.setWrapText(true);
        VBox buckets = new VBox(6, altBox, applyPane, holdPane, rejectPane, hostBox);
        buckets.setMinWidth(220);
        VBox.setVgrow(applyPane, Priority.ALWAYS);
        SplitPane split = new SplitPane(buckets, diffHolder);
        split.setDividerPositions(0.42);
        SplitPane.setResizableWithParent(buckets, false);
        showDiff();

        branch.setEditable(true);
        branch.setPromptText(b.getString("gui.deliver.branch.prompt"));
        branch.setMaxWidth(Double.MAX_VALUE);
        branchHint.getStyleClass().add("section-help");
        sessions.branches(sessionId, list -> {
            branch.getItems().setAll(list);
            if (!list.isEmpty() && branchText().isBlank()) branch.getEditor().setText(list.get(0));
            updateBranchHint();
        });
        branch.getEditor().textProperty().addListener((o, a, v) -> updateBranchHint());
        branch.valueProperty().addListener((o, a, v) -> updateBranchHint());
        Button suggest = new Button(b.getString("gui.deliver.suggest"));
        ProgressIndicator suggestSpinner = new ProgressIndicator();
        suggestSpinner.setPrefSize(18, 18);
        suggestSpinner.setVisible(false);
        suggest.setOnAction(e -> {
            suggestSpinner.setVisible(true);
            sessions.suggest(sessionId, s -> {
                suggestSpinner.setVisible(false);
                if (branchText().isBlank()) branch.getEditor().setText(s.branch());
                if (message.getText().isBlank()) message.setText(s.message());
            }, t -> suggestSpinner.setVisible(false));
        });
        message.setPromptText(b.getString("gui.deliver.message.prompt"));
        ToggleGroup mode = new ToggleGroup();
        commit = new RadioButton(b.getString("gui.deliver.mode.commit"));
        commit.setToggleGroup(mode);
        stage = new RadioButton(b.getString("gui.deliver.mode.stage"));
        stage.setToggleGroup(mode);
        stage.setSelected(true);
        HBox branchRow = new HBox(8, new Label(b.getString("gui.deliver.branch")), branch, suggest, suggestSpinner);
        HBox.setHgrow(branch, Priority.ALWAYS);
        Label branchHelp = new Label(b.getString("gui.deliver.branch.help"));
        branchHelp.getStyleClass().add("section-help");
        HBox messageRow = new HBox(8, new Label(b.getString("gui.deliver.message")), message);
        HBox.setHgrow(message, Priority.ALWAYS);
        Label note = new Label(b.getString("gui.deliver.note"));
        note.setWrapText(true);
        note.getStyleClass().add("section-help");
        VBox content = new VBox(8, fromRow, warnBox, split, branchRow, branchHelp, branchHint, messageRow, new HBox(12, commit, stage), note);
        VBox.setVgrow(split, Priority.ALWAYS);
        content.setPadding(new Insets(6));
        getDialogPane().setContent(content);

        // Apply runs asynchronously; the dialog closes with the result on success and stays open on failure.
        Button applyButton = (Button) getDialogPane().lookupButton(applyType);
        applyButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            apply();
        });
        setResultConverter(bt -> null);
        load();
    }

    /** One bucket: the files as rows, the held ones with a tick box, and the selection that drives the diff pane. */
    private ListView<DeliverFiles.Entry> bucketList(ListView<DeliverFiles.Entry> list, double height, boolean hold) {
        list.getStyleClass().add("diff-file-list");
        list.setCellFactory(v -> new FileCell(hold));
        list.setPrefHeight(height);
        list.setMinHeight(0);
        return list;
    }

    /** The three lists share one selection: the file shown on the right is the one last clicked, wherever it sits. */
    private void pick(ListView<DeliverFiles.Entry> list, DeliverFiles.Entry entry) {
        if (syncing || entry == null) return;
        syncing = true;
        for (ListView<DeliverFiles.Entry> other : lists) {
            if (other != list) other.getSelectionModel().clearSelection();
        }
        syncing = false;
        selected = entry;
        showDiff();
    }

    /**
     * The dialog opens over the window it was asked from, with that window's size and position: the buckets and
     * the diff beside them are read the way the conversation behind them was, and a box in the middle of a large
     * window would only have to be dragged bigger first. The geometry is taken when the dialog opens — the
     * window has its own by then, maximised or not — and the dialog keeps it from there, so it can be moved and
     * resized afterwards like any other window. The pane's preferred size stands for the case with no owner.
     */
    private void matchOwner(Window owner) {
        if (owner == null) return;
        setOnShowing(e -> {
            if (!owner.isShowing()) return;
            setWidth(owner.getWidth());
            setHeight(owner.getHeight());
            setX(owner.getX());
            setY(owner.getY());
        });
    }

    /** Hidden notes take no room: the buckets get the height instead. */
    private static void visible(Node node, boolean on) {
        node.setVisible(on);
        node.setManaged(on);
    }

    private void load() {
        int gen = ++generation;
        spinner.setVisible(true);
        summary.setText("");
        sessions.review(sessionId, Optional.of(from.getValue()), r -> {
            if (gen != generation) return;
            spinner.setVisible(false);
            review = r;
            render(r);
            loadDiffs();
        }, t -> {
            if (gen != generation) return;
            spinner.setVisible(false);
            Errors.UiError e = Errors.map(t, no.syntakserror.lyngvi.core.i18n.Messages.forLanguage(b.getLocale().getLanguage()));
            summary.setText(e.message());
            clear();
            if (e.kind() == Errors.Kind.NO_CHANGES && !e.alternatives().isEmpty()) {
                Set<String> offered = new LinkedHashSet<>();
                for (Errors.Alternative alt : e.alternatives()) {
                    // The core offers «last» as well as the delivery it points at; both are the same baseline here.
                    String value = BaselineChoices.canonical(alt.from(), conversation);
                    if (!offered.add(value)) continue;
                    Button pick = new Button(b.getString("gui.deliver.alternative") + " "
                            + BaselineChoices.label(value, conversation, b) + " (" + alt.files() + ")");
                    pick.setOnAction(x -> from.setValue(value));
                    altBox.getChildren().add(pick);
                }
                visible(altBox, true);
            }
        });
    }

    /**
     * The patches behind the review, one request per repository, so every row has a real diff to show. The review
     * itself only counts lines; the diff text is what the developer decides on.
     */
    private void loadDiffs() {
        if (review == null) return;
        int gen = ++diffGeneration;
        diffs.clear();
        pendingDiffs = review.repos().size();
        showDiff();
        for (String repo : review.repos().keySet()) {
            sessions.diff(sessionId, Optional.of(from.getValue()), Optional.of(repo), Optional.empty(), whole.isSelected(), text -> {
                if (gen != diffGeneration) return;
                diffs.putAll(DeliverFiles.index(repo, DiffPaneBuilder.parse(text)));
                pendingDiffs--;
                showDiff();
            });
        }
    }

    /** The selected file's diff, or why there is none to show yet. */
    private void showDiff() {
        diffHolder.getChildren().clear();
        DiffPaneBuilder.FileDiff file = selected == null ? null : diffs.get(selected.key());
        if (file != null && !file.lines().isEmpty()) {
            DeliverFiles.Entry entry = selected;
            diffHolder.getChildren().add(DiffArea.fileView(file, b, () -> copyNewFile(entry)));
            return;
        }
        diffHint.setText(selected == null ? b.getString("gui.deliver.diff.pick")
                : pendingDiffs > 0 ? b.getString("gui.diff.loading")
                : b.getString("gui.deliver.diff.none"));
        diffHolder.getChildren().add(diffHint);
    }

    /**
     * «Copy file» on the diff header: the file as it is after the change. The pane shows hunks, which are not the
     * whole file, so the core is asked for it in full — for this file in this repository — and the new side of
     * that patch is what lands on the clipboard.
     */
    private void copyNewFile(DeliverFiles.Entry entry) {
        sessions.diff(sessionId, Optional.of(from.getValue()), Optional.of(entry.repo()), Optional.of(entry.path()), true,
                text -> DiffPaneBuilder.parse(text).stream().filter(f -> f.path().equals(entry.path())).findFirst()
                        .ifPresent(whole -> MarkdownArea.copyToClipboard(DiffPaneBuilder.newFileText(whole.lines()))));
    }

    /** Empties the three buckets and the diff pane, and drops whatever patches are still on their way. */
    private void clear() {
        for (ListView<DeliverFiles.Entry> list : lists) list.getItems().clear();
        altBox.getChildren().clear();
        visible(altBox, false);
        hostBox.getChildren().clear();
        visible(hostBox, false);
        warnBox.getChildren().clear();
        visible(warnBox, false);
        diffGeneration++;
        diffs.clear();
        pendingDiffs = 0;
        selected = null;
        showDiff();
    }

    private void render(ReviewResult r) {
        String keep = selected == null ? null : selected.key();
        clear();
        List<DeliverFiles.Entry> apply = DeliverFiles.bucket(r, DeliverFiles.APPLY);
        List<DeliverFiles.Entry> hold = DeliverFiles.bucket(r, DeliverFiles.HOLD);
        List<DeliverFiles.Entry> reject = DeliverFiles.bucket(r, DeliverFiles.REJECT);
        // Ticks survive a reload, but only for files that are still held back.
        included.retainAll(hold.stream().map(DeliverFiles.Entry::key).collect(java.util.stream.Collectors.toSet()));
        fill(applyList, applyPane, "gui.deliver.bucket.apply", apply, false);
        fill(holdList, holdPane, "gui.deliver.bucket.hold", hold, true);
        fill(rejectList, rejectPane, "gui.deliver.bucket.reject", reject, true);
        // Files kept out of the delivery are the ones worth opening: the collapsed pane is where they go unnoticed.
        if (!hold.isEmpty()) holdPane.setExpanded(true);
        if (apply.isEmpty() && hold.isEmpty() && !reject.isEmpty()) rejectPane.setExpanded(true);
        for (Map.Entry<String, ReviewResult.RepoReview> e : r.repos().entrySet()) {
            ReviewResult.HostInfo host = e.getValue().host();
            if (host == null || !(host.isProtected() || host.dirty())) continue;
            Label label = new Label(e.getKey() + ": " + b.getString("gui.deliver.host.branch") + " " + host.branch()
                    + (host.dirty() ? "  (" + b.getString("gui.deliver.host.dirty") + ")" : ""));
            label.getStyleClass().add("section-help");
            label.setWrapText(true);
            hostBox.getChildren().add(label);
        }
        visible(hostBox, !hostBox.getChildren().isEmpty());
        int total = apply.size() + hold.size() + reject.size();
        summary.setText(msg("gui.deliver.summary", String.valueOf(total), r.from().describe()));
        select(keep, apply, hold, reject);
        updateExclusions();
    }

    /** A bucket that keeps files out of the delivery says so in its own title, open or collapsed. */
    private void fill(ListView<DeliverFiles.Entry> list, TitledPane pane, String titleKey, List<DeliverFiles.Entry> entries, boolean warn) {
        list.getItems().setAll(entries);
        pane.setText((warn && !entries.isEmpty() ? "⚠ " : "") + b.getString(titleKey) + " (" + entries.size() + ")");
    }

    /**
     * What this delivery would leave behind, said where it cannot be missed: above the buckets, counted, with the
     * reasons named and a button that opens the files themselves. Nothing here is a dead end — a held file is one
     * tick away from going out with the rest.
     */
    private void updateExclusions() {
        warnBox.getChildren().clear();
        DeliverFiles.Excluded ex = review == null ? null : DeliverFiles.excluded(review, included);
        if (ex == null || !ex.any()) {
            visible(warnBox, false);
            return;
        }
        Label title = new Label("⚠ " + msg("gui.deliver.excluded.summary", String.valueOf(ex.count()), String.valueOf(ex.total())));
        title.getStyleClass().add("deliver-warning-title");
        title.setWrapText(true);
        HBox head = new HBox(8, title);
        head.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button show = new Button(b.getString("gui.deliver.excluded.show"));
        show.setOnAction(e -> reveal(ex.heldOut().isEmpty() ? ex.rejected().get(0) : ex.heldOut().get(0)));
        head.getChildren().addAll(spacer, show);
        warnBox.getChildren().add(head);
        if (!ex.heldOut().isEmpty()) warnBox.getChildren().add(warnLine("gui.deliver.excluded.hold", ex.heldOut()));
        if (!ex.rejected().isEmpty()) warnBox.getChildren().add(warnLine("gui.deliver.excluded.reject", ex.rejected()));
        if (!ex.heldIn().isEmpty()) {
            warnBox.getChildren().add(warnLabel(msg("gui.deliver.excluded.included", String.valueOf(ex.heldIn().size()))));
        }
        visible(warnBox, true);
    }

    /** «3 held back by policy (executable (2), binary)» — the count, then why, so the reason is never a guess. */
    private Label warnLine(String key, List<DeliverFiles.Entry> entries) {
        String why = reasonList(entries);
        return warnLabel(why.isBlank() ? msg(key + ".plain", String.valueOf(entries.size()))
                : msg(key, String.valueOf(entries.size()), why));
    }

    private Label warnLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("deliver-warning-line");
        l.setWrapText(true);
        return l;
    }

    /** The reasons of a bucket in the user's language, commonest first, each with its count when it is not alone. */
    private String reasonList(List<DeliverFiles.Entry> entries) {
        List<String> parts = new ArrayList<>();
        DeliverFiles.reasons(entries).forEach((reason, n) -> {
            String text = reason(reason);
            if (!text.isBlank()) parts.add(n > 1 ? text + " (" + n + ")" : text);
        });
        return String.join(", ", parts);
    }

    /** Opens the bucket a file sits in and selects it, so «show them» lands on the file and its diff. */
    private void reveal(DeliverFiles.Entry entry) {
        ListView<DeliverFiles.Entry> list = listOf(entry);
        if (list == holdList) holdPane.setExpanded(true);
        if (list == rejectList) rejectPane.setExpanded(true);
        list.getSelectionModel().select(entry);
        list.scrollTo(entry);
        list.requestFocus();
    }

    /** {@code {0}}, {@code {1}}, … in a bundle string, the way the rest of the dialog fills them in. */
    private String msg(String key, String... args) {
        String text = b.getString(key);
        for (int i = 0; i < args.length; i++) text = text.replace("{" + i + "}", args[i]);
        return text;
    }

    /** Keeps the file that was open when the lists are rebuilt, and otherwise opens the first one there is. */
    @SafeVarargs
    private void select(String keep, List<DeliverFiles.Entry>... buckets) {
        for (ListView<DeliverFiles.Entry> list : lists) {
            for (DeliverFiles.Entry e : list.getItems()) {
                if (e.key().equals(keep)) {
                    list.getSelectionModel().select(e);
                    return;
                }
            }
        }
        for (List<DeliverFiles.Entry> bucket : buckets) {
            if (bucket.isEmpty()) continue;
            ListView<DeliverFiles.Entry> list = listOf(bucket.get(0));
            list.getSelectionModel().select(bucket.get(0));
            return;
        }
        selected = null;
        showDiff();
    }

    private ListView<DeliverFiles.Entry> listOf(DeliverFiles.Entry entry) {
        return switch (DeliverFiles.bucketOf(entry.file())) {
            case DeliverFiles.APPLY -> applyList;
            case DeliverFiles.HOLD -> holdList;
            default -> rejectList;
        };
    }

    /** One file: a change badge, the name, green/red counts, the repository and directory, then conflict and reason. */
    private Node fileRow(DeliverFiles.Entry entry, CheckBox include) {
        FileChange f = entry.file();
        String change = f.change() == null ? "modified" : f.change().toLowerCase(java.util.Locale.ROOT);
        Label badge = new Label(change);
        badge.getStyleClass().addAll("diff-change-badge", change);
        badge.setMinWidth(Region.USE_PREF_SIZE);
        Label name = new Label(entry.name());
        name.getStyleClass().add("diff-file-name");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox first = new HBox(6, badge, name, DiffArea.addCount(f.additions()), DiffArea.delCount(f.deletions()));
        first.setAlignment(Pos.CENTER_LEFT);
        Label where = new Label(entry.location());
        where.getStyleClass().add("diff-file-dir");
        where.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        where.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(2, first, where);
        box.getStyleClass().add("diff-file-cell");
        box.setMinWidth(0);
        String reason = reason(f.reason());
        if (f.conflict() || !reason.isBlank()) {
            HBox notes = new HBox(8);
            if (f.conflict()) {
                Label conflict = new Label("⚠ " + b.getString("gui.deliver.conflict"));
                conflict.getStyleClass().add("diff-conflict");
                notes.getChildren().add(conflict);
            }
            if (!reason.isBlank()) {
                Label why = new Label(reason);
                why.getStyleClass().add("section-help");
                notes.getChildren().add(why);
            }
            box.getChildren().add(notes);
        }
        if (include == null) return box;
        HBox.setHgrow(box, Priority.ALWAYS);
        HBox row = new HBox(8, include, box);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A row of a bucket list; the held bucket carries the tick that includes the file in the delivery. */
    private final class FileCell extends ListCell<DeliverFiles.Entry> {

        private final CheckBox include;

        FileCell(boolean hold) {
            include = hold ? new CheckBox() : null;
            if (include != null) {
                include.setOnAction(e -> {
                    DeliverFiles.Entry item = getItem();
                    if (item == null) return;
                    if (include.isSelected()) included.add(item.key());
                    else included.remove(item.key());
                    updateExclusions();
                });
            }
        }

        @Override
        protected void updateItem(DeliverFiles.Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            setText(null);
            if (empty || entry == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            if (include != null) include.setSelected(included.contains(entry.key()));
            setGraphic(fileRow(entry, include));
            setTooltip(new Tooltip(entry.file().renamedFrom() == null ? entry.key()
                    : entry.key() + "  ←  " + entry.repo() + "/" + entry.file().renamedFrom()));
        }
    }

    private String reason(String reason) {
        if (reason == null) return "";
        try {
            return b.getString("gui.deliver.reason." + reason.toLowerCase());
        } catch (java.util.MissingResourceException e) {
            return reason;
        }
    }

    private String branchText() {
        String typed = branch.getEditor().getText();
        return typed == null || typed.isBlank() ? (branch.getValue() == null ? "" : branch.getValue()) : typed.strip();
    }

    private void updateBranchHint() {
        String name = branchText();
        if (name.isBlank()) {
            branchHint.setText("");
        } else if (config.protectedBranches().contains(name)) {
            branchHint.setText(b.getString("gui.deliver.branch.isprotected"));
        } else if (branch.getItems().contains(name)) {
            branchHint.setText(b.getString("gui.deliver.branch.existing"));
        } else {
            branchHint.setText(b.getString("gui.deliver.branch.new"));
        }
    }

    private void apply() {
        if (branchText().isBlank()) {
            dialogs.info(b.getString("gui.deliver.title"), b.getString("gui.deliver.branch.required"));
            return;
        }
        if (!confirmExclusions()) return;
        applyNow();
    }

    /**
     * Nothing is left behind silently: when the delivery would drop files, they are named one by one, and the
     * held-back ones carry their tick box here too — so a file is taken along from the confirmation itself
     * rather than by going back to the buckets. The ticks it comes back with are the dialog's from then on,
     * whether the delivery then succeeds or fails.
     */
    private boolean confirmExclusions() {
        DeliverFiles.Excluded ex = review == null ? null : DeliverFiles.excluded(review, included);
        if (ex == null || !ex.any()) return true;
        ExcludedDialog dialog = new ExcludedDialog(getOwner(), b, ex, DeliverFiles.bucket(review, DeliverFiles.HOLD),
                included, this::reason);
        Optional<Set<String>> ticked = dialog.showAndWait();
        ticked.ifPresent(keys -> {
            included.clear();
            included.addAll(keys);
            holdList.refresh();
            updateExclusions();
        });
        return ticked.isPresent();
    }

    private void applyNow() {
        // The apply bucket goes along with the ticks: an explicit list is the whole delivery to the core.
        List<String> includes = review == null ? List.of()
                : DeliverFiles.includes(review, included, conversation.repos().size() > 1);
        spinner.setVisible(true);
        getDialogPane().lookupButton(applyType).setDisable(true);
        String name = branchText();
        ApplyOptions options = new ApplyOptions(name, message.getText().isBlank() ? Optional.empty() : Optional.of(message.getText().strip()),
                stage.isSelected() ? "stage" : "commit", List.of(), includes, Optional.of(from.getValue()));
        sessions.apply(sessionId, options, result -> {
            spinner.setVisible(false);
            setResult(result);
            close();
        }, t -> {
            spinner.setVisible(false);
            getDialogPane().lookupButton(applyType).setDisable(false);
            Errors.UiError e = Errors.map(t, no.syntakserror.lyngvi.core.i18n.Messages.forLanguage(b.getLocale().getLanguage()));
            if (e.kind() == Errors.Kind.PROTECTED_BRANCH) {
                // The user decides per sandbox: allowing the branch edits this sandbox's profile and retries.
                String text = b.getString("gui.deliver.branch.protected.text").replace("{0}", name);
                if (dialogs.confirm(b.getString("gui.deliver.branch.protected.title"), text, b.getString("gui.deliver.branch.protected.allow"))) {
                    List<String> remaining = new ArrayList<>(config.protectedBranches());
                    remaining.remove(name);
                    config.setProtectedBranches(remaining, this::applyNow);
                }
                return;
            }
            dialogs.error(e);
        });
    }
}
