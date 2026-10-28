package no.syntakserror.lyngvi.gui.view;

import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.gui.viewmodel.SessionViewModel;
import no.syntakserror.lyngvi.gui.viewmodel.Settings;

/**
 * «Git Diff»: the session's changes since a baseline. The changed files are listed on the left; the selected one
 * is shown on the right, either as its diff alone or, with «Whole file» on, as the complete file with the changes
 * coloured in place. The baseline and repository pickers and the refresh button reload the diff.
 */
public final class DiffWindow extends Stage {

    private final ListView<DiffPaneBuilder.FileDiff> files = new ListView<>();
    private final StackPane fileHolder = new StackPane();
    private final Label status = new Label();
    private final Label empty = new Label();
    private final ResourceBundle b;
    /** Copies the selected file as it is after the change; set once the pickers it reads exist. */
    private final java.util.function.Consumer<DiffPaneBuilder.FileDiff> copyNewFile;
    /** Bumped by every load, so a slow reply from an earlier one is dropped instead of overwriting a newer one. */
    private int generation;

    /**
     * @param initialFrom the baseline to open on, empty for the newest delivery; the chat strip passes the one it
     *                    counted, so the window shows the changes the strip reported.
     */
    public DiffWindow(Window owner, ResourceBundle b, SessionViewModel sessions, Conversation c, Settings.Theme theme,
                      Optional<String> initialFrom) {
        initOwner(owner);
        this.b = b;
        setTitle(b.getString("gui.diff.title") + " — " + c.title());
        ComboBox<String> from = new ComboBox<>();
        BaselineChoices.fill(from, c, b);
        initialFrom.map(v -> BaselineChoices.canonical(v, c)).filter(v -> from.getItems().contains(v)).ifPresent(from::setValue);
        ComboBox<String> repo = new ComboBox<>();
        repo.getItems().add(b.getString("gui.diff.allrepos"));
        repo.getItems().addAll(c.repos());
        repo.setValue(repo.getItems().getFirst());
        Button refresh = new Button(b.getString("gui.button.refresh"), Icon.of("refresh"));
        ToggleButton whole = new ToggleButton(b.getString("gui.diff.wholefile"));
        whole.setTooltip(new Tooltip(b.getString("gui.diff.wholefile.tip")));
        status.getStyleClass().add("diff-status");
        empty.getStyleClass().add("diff-empty");
        empty.setVisible(false);
        Runnable load = () -> {
            int gen = ++generation;
            status.setText(b.getString("gui.diff.loading"));
            Optional<String> r = repo.getSelectionModel().getSelectedIndex() == 0 ? Optional.empty() : Optional.of(repo.getValue());
            sessions.diff(c.id(), Optional.of(from.getValue()), r, Optional.empty(), whole.isSelected(), diff -> {
                if (gen != generation) return;
                var parsed = DiffPaneBuilder.parse(diff);
                setFiles(parsed);
                int add = parsed.stream().mapToInt(DiffPaneBuilder.FileDiff::additions).sum();
                int del = parsed.stream().mapToInt(DiffPaneBuilder.FileDiff::deletions).sum();
                empty.setText(b.getString("gui.diff.empty"));
                empty.setVisible(parsed.isEmpty());
                status.setText(parsed.isEmpty() ? b.getString("gui.diff.empty")
                        : b.getString("gui.diff.summary").replace("{0}", String.valueOf(parsed.size())).replace("{1}", String.valueOf(add)).replace("{2}", String.valueOf(del))
                        + "  ·  " + java.time.LocalTime.now().withNano(0));
            });
        };
        // «Copy file» needs the file in full, which the hunks on screen are not: the core is asked again for it.
        copyNewFile = f -> {
            Optional<String> r = repo.getSelectionModel().getSelectedIndex() == 0 ? Optional.empty() : Optional.of(repo.getValue());
            sessions.diff(c.id(), Optional.of(from.getValue()), r, Optional.of(f.path()), true, text -> {
                DiffPaneBuilder.parse(text).stream().filter(x -> x.path().equals(f.path())).findFirst().ifPresent(complete -> {
                    MarkdownArea.copyToClipboard(DiffPaneBuilder.newFileText(complete.lines()));
                    status.setText(b.getString("gui.diff.copied").replace("{0}", f.name()));
                });
            });
        };
        refresh.setOnAction(e -> load.run());
        from.valueProperty().addListener((o, a, v) -> load.run());
        repo.valueProperty().addListener((o, a, v) -> load.run());
        whole.selectedProperty().addListener((o, a, v) -> load.run());
        Label fromLabel = new Label(b.getString("gui.deliver.from"));
        fromLabel.getStyleClass().add("field-label");
        fromLabel.setMinWidth(Region.USE_PREF_SIZE);
        Label repoLabel = new Label(b.getString("gui.diff.repo"));
        repoLabel.getStyleClass().add("field-label");
        repoLabel.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, fromLabel, from, repoLabel, repo, refresh, whole, spacer, status);
        top.getStyleClass().add("diff-toolbar");
        top.setAlignment(Pos.CENTER_LEFT);

        files.getStyleClass().add("diff-file-list");
        files.setCellFactory(v -> new FileCell());
        files.getSelectionModel().selectedItemProperty().addListener((o, a, f) -> show(f));
        files.setMinWidth(160);
        fileHolder.setPadding(new Insets(12));
        fileHolder.getStyleClass().add("diff-area");
        SplitPane split = new SplitPane(files, fileHolder);
        split.setDividerPositions(0.3);
        SplitPane.setResizableWithParent(files, false);
        StackPane center = new StackPane(split, empty);
        StackPane.setAlignment(empty, Pos.CENTER);
        empty.setMouseTransparent(true);
        BorderPane root = new BorderPane(center);
        root.setTop(top);
        root.getStyleClass().add("main-root");
        Scene scene = new Scene(root, 1100, 720);
        Theme.apply(scene, theme);
        setScene(scene);
        load.run();
    }

    /** Replaces the file list, keeping the selected path when it is still there and falling back to the first file. */
    private void setFiles(List<DiffPaneBuilder.FileDiff> parsed) {
        DiffPaneBuilder.FileDiff before = files.getSelectionModel().getSelectedItem();
        String keep = before == null ? null : before.path();
        files.getItems().setAll(parsed);
        DiffPaneBuilder.FileDiff again = parsed.stream().filter(f -> f.path().equals(keep)).findFirst().orElse(null);
        if (again != null) {
            files.getSelectionModel().select(again);
        } else if (!parsed.isEmpty()) {
            files.getSelectionModel().selectFirst();
        } else {
            files.getSelectionModel().clearSelection();
            show(null);
        }
    }

    private void show(DiffPaneBuilder.FileDiff f) {
        fileHolder.getChildren().clear();
        if (f != null) fileHolder.getChildren().add(DiffArea.fileView(f, b, () -> copyNewFile.accept(f)));
    }

    /** One changed file: badge, file name and +/− counts on the first line, the directory muted beneath. */
    private static final class FileCell extends ListCell<DiffPaneBuilder.FileDiff> {
        @Override
        protected void updateItem(DiffPaneBuilder.FileDiff f, boolean empty) {
            super.updateItem(f, empty);
            setText(null);
            if (empty || f == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            Label name = new Label(f.name());
            name.getStyleClass().add("diff-file-name");
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            HBox line = new HBox(6, DiffArea.changeBadge(f.change()), name, DiffArea.addCount(f.additions()), DiffArea.delCount(f.deletions()));
            line.setAlignment(Pos.CENTER_LEFT);
            VBox box = new VBox(2, line);
            box.getStyleClass().add("diff-file-cell");
            if (!f.directory().isEmpty()) {
                Label dir = new Label(f.directory());
                dir.getStyleClass().add("diff-file-dir");
                dir.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
                dir.setMaxWidth(Double.MAX_VALUE);
                box.getChildren().add(dir);
            }
            setGraphic(box);
            setTooltip(new Tooltip(f.change() == DiffPaneBuilder.Change.RENAMED ? f.oldPath() + " → " + f.path() : f.path()));
        }
    }
}
