package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Unified diff in the GitHub idiom: one card per file with the path and the +/− counts in the header, then the
 * lines with a two-column number gutter, a sign column and the code; added lines green, removed red, hunk headers
 * blue. Text in a file view is selected and copied freely ({@link DiffSelection}); copying yields code only
 * (no gutter, no +/-), and the header can offer the whole file as it is after the change.
 */
public final class DiffArea extends VBox {

    static final double LINE_HEIGHT = 22;
    private static final int MAX_VISIBLE_LINES = 28;

    public DiffArea() {
        getStyleClass().add("diff-area");
        setSpacing(12);
    }

    public void setFiles(List<DiffPaneBuilder.FileDiff> files) {
        getChildren().clear();
        for (DiffPaneBuilder.FileDiff f : files) getChildren().add(fileCard(f, false, null, null));
    }

    /**
     * One file on its own, filling the height it is given: the list scrolls itself, so a whole file of any length
     * stays one virtualised list instead of a capped card inside a scroll pane.
     *
     * @param copyNewFile what «Copy file» does — the owner knows the repository and can ask the core for the file
     *                    in full; null when there is nothing to copy, as for a deleted or binary file
     */
    public static VBox fileView(DiffPaneBuilder.FileDiff f, ResourceBundle b, Runnable copyNewFile) {
        return fileCard(f, true, b, copyNewFile);
    }

    private static VBox fileCard(DiffPaneBuilder.FileDiff f, boolean fill, ResourceBundle b, Runnable copyNewFile) {
        VBox card = new VBox();
        card.getStyleClass().add("diff-file");
        ListView<DiffPaneBuilder.Line> list = list(f.lines(), fill, b, copyNewFile);
        if (!f.path().isBlank()) card.getChildren().add(header(f, b, copyNewFile));
        if (fill) {
            VBox.setVgrow(list, Priority.ALWAYS);
            card.setMaxHeight(Double.MAX_VALUE);
        }
        card.getChildren().add(list);
        return card;
    }

    private static HBox header(DiffPaneBuilder.FileDiff f) {
        return header(f, null, null);
    }

    private static HBox header(DiffPaneBuilder.FileDiff f, ResourceBundle b, Runnable copyNewFile) {
        Label path = new Label(f.path());
        path.getStyleClass().add("diff-file-path");
        HBox header = new HBox(path);
        header.getStyleClass().add("diff-file-header");
        if (f.change() != DiffPaneBuilder.Change.MODIFIED) header.getChildren().add(changeBadge(f.change()));
        if (f.change() == DiffPaneBuilder.Change.RENAMED) {
            Label old = new Label("← " + f.oldPath());
            old.getStyleClass().add("diff-file-old");
            header.getChildren().add(old);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(spacer, addCount(f.additions()), delCount(f.deletions()));
        if (copyNewFile != null && canCopyNewFile(f)) {
            Button copy = new Button(label(b, "gui.diff.copy.newfile", "Copy file"));
            copy.getStyleClass().add("diff-copy-file");
            copy.setTooltip(new Tooltip(label(b, "gui.diff.copy.newfile.tip", "Copy the whole file as it is after the change")));
            copy.setOnAction(e -> copyNewFile.run());
            header.getChildren().add(copy);
        }
        return header;
    }

    /** A deleted file has no «after», and a binary one no lines to give: there the button would be a lie. */
    private static boolean canCopyNewFile(DiffPaneBuilder.FileDiff f) {
        return f.change() != DiffPaneBuilder.Change.DELETED && !f.lines().isEmpty();
    }

    /** A label from the bundle, or the English fallback where there is no bundle (a diff inside the chat). */
    private static String label(ResourceBundle b, String key, String fallback) {
        try {
            return b == null ? fallback : b.getString(key);
        } catch (java.util.MissingResourceException e) {
            return fallback;
        }
    }

    /** A small coloured «added» / «deleted» / «modified» / «renamed» pill, for headers and file lists. */
    public static Label changeBadge(DiffPaneBuilder.Change change) {
        Label badge = new Label(change.key());
        badge.getStyleClass().addAll("diff-change-badge", change.key());
        badge.setMinWidth(Region.USE_PREF_SIZE);
        return badge;
    }

    /** «+12» in green, for headers and file lists. */
    public static Label addCount(int n) {
        Label l = new Label("+" + n);
        l.getStyleClass().add("diff-count-add");
        return l;
    }

    /** «−3» in red, for headers and file lists. */
    public static Label delCount(int n) {
        Label l = new Label("−" + n);
        l.getStyleClass().add("diff-count-del");
        return l;
    }

    private static ListView<DiffPaneBuilder.Line> list(List<DiffPaneBuilder.Line> lines, boolean fill,
                                                       ResourceBundle b, Runnable copyNewFile) {
        ListView<DiffPaneBuilder.Line> list = new ListView<>();
        list.getItems().setAll(lines);
        list.getStyleClass().add("diff-list");
        list.setFixedCellSize(LINE_HEIGHT);
        // The diff is text, not a list of choices: with no selection model a row can never be picked out whole,
        // by mouse or by arrow key, and the only selection on screen is the one over the characters.
        list.setSelectionModel(null);
        DiffSelection selection = new DiffSelection(list);
        list.setCellFactory(v -> new LineCell(selection));
        if (fill) {
            list.setMinHeight(0);
            list.setMaxHeight(Double.MAX_VALUE);
        } else {
            double height = LINE_HEIGHT * Math.min(MAX_VISIBLE_LINES, Math.max(1, lines.size())) + 2;
            list.setPrefHeight(height);
            list.setMinHeight(height);
        }
        ContextMenu menu = new ContextMenu();
        MenuItem copySel = new MenuItem(label(b, "gui.diff.copy.selection", "Copy selection"));
        copySel.setOnAction(e -> selection.copy());
        MenuItem copyAll = new MenuItem(label(b, "gui.diff.copy.diff", "Copy file diff"));
        copyAll.setOnAction(e -> MarkdownArea.copyToClipboard(DiffPaneBuilder.copyText(lines)));
        menu.getItems().addAll(copySel, copyAll);
        if (copyNewFile != null) {
            MenuItem copyFile = new MenuItem(label(b, "gui.diff.copy.newfile", "Copy file"));
            copyFile.setOnAction(e -> copyNewFile.run());
            menu.getItems().add(copyFile);
        }
        // Nothing is selected until the pointer has been dragged over something, so the item says so.
        menu.setOnShowing(e -> copySel.setDisable(selection.isEmpty()));
        list.setContextMenu(menu);
        return list;
    }

    /**
     * A diff shown inline (a markdown {@code ```diff} block, a tool's output): full file cards when the text has
     * hunks, otherwise each line coloured by its +/− prefix. Long lines scroll sideways instead of widening the parent.
     */
    public static Node block(String text) {
        String t = text == null ? "" : text;
        List<DiffPaneBuilder.FileDiff> files = DiffPaneBuilder.parse(t);
        if (!files.isEmpty() && files.stream().anyMatch(f -> f.lines().stream().anyMatch(l -> l.kind() == DiffPaneBuilder.Kind.HUNK))) {
            DiffArea area = new DiffArea();
            area.setFiles(files);
            return area;
        }
        List<DiffPaneBuilder.Line> lines = new ArrayList<>();
        for (String raw : t.split("\n", -1)) {
            DiffPaneBuilder.Kind kind = DiffPaneBuilder.kindOf(raw);
            String code = kind == DiffPaneBuilder.Kind.ADDED || kind == DiffPaneBuilder.Kind.REMOVED ? raw.substring(1) : raw;
            lines.add(new DiffPaneBuilder.Line(kind, null, null, code));
        }
        VBox box = new VBox(rows(lines));
        box.getStyleClass().add("diff-block");
        box.setMinWidth(0);
        box.setMaxWidth(Double.MAX_VALUE);
        copyMenu(box, "Copy diff", t);
        return box;
    }

    /**
     * A file change given as lines rather than as diff text (a tool call's old and new string): the file card with
     * the path, an optional note badge and the +/− counts, then the coloured lines without a number gutter.
     */
    public static Node changes(String path, List<DiffPaneBuilder.Line> lines, int additions, int deletions, String note) {
        VBox card = new VBox();
        card.getStyleClass().addAll("diff-file", "diff-changes");
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        if (path != null && !path.isBlank()) {
            HBox h = header(new DiffPaneBuilder.FileDiff(path, null, DiffPaneBuilder.Change.MODIFIED, lines, additions, deletions));
            if (note != null && !note.isBlank()) {
                Label badge = new Label(note);
                badge.getStyleClass().addAll("diff-change-badge", "modified");
                h.getChildren().add(1, badge);
            }
            card.getChildren().add(h);
        }
        card.getChildren().add(rows(lines));
        copyMenu(card, "Copy diff", LineDiff.text(lines));
        return card;
    }

    /** Sign + code rows in a sideways scroll, so long lines never widen the parent. */
    private static ScrollPane rows(List<DiffPaneBuilder.Line> lines) {
        VBox box = new VBox();
        for (DiffPaneBuilder.Line l : lines) {
            HBox row = new HBox(sign(l.kind()), codeFlow(l.code(), l.kind()));
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinHeight(LINE_HEIGHT);
            row.getStyleClass().addAll("diff-line", styleClass(l.kind()));
            box.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToHeight(true);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMinWidth(0);
        scroll.getStyleClass().add("md-code-scroll");
        return scroll;
    }

    private static void copyMenu(Node owner, String label, String text) {
        ContextMenu menu = new ContextMenu();
        MenuItem copy = new MenuItem(label);
        copy.setOnAction(e -> MarkdownArea.copyToClipboard(text));
        menu.getItems().add(copy);
        owner.setOnContextMenuRequested(e -> { menu.show(owner, e.getScreenX(), e.getScreenY()); e.consume(); });
    }

    static String styleClass(DiffPaneBuilder.Kind kind) {
        return switch (kind) {
            case ADDED -> "diff-added";
            case REMOVED -> "diff-removed";
            case HUNK -> "diff-hunk";
            case META -> "diff-meta";
            case CONTEXT -> "diff-context";
        };
    }

    static String signOf(DiffPaneBuilder.Kind kind) {
        return switch (kind) {
            case ADDED -> "+";
            case REMOVED -> "−";
            default -> "";
        };
    }

    private static Label sign(DiffPaneBuilder.Kind kind) {
        Label l = new Label(signOf(kind));
        l.getStyleClass().add("diff-sign");
        l.setMaxHeight(Double.MAX_VALUE);
        return l;
    }

    private static TextFlow codeFlow(String code, DiffPaneBuilder.Kind kind) {
        Text text = new Text(code);
        text.getStyleClass().add("diff-code");
        TextFlow flow = new TextFlow(text);
        flow.setPadding(new Insets(0, 10, 0, 4));
        flow.setMinWidth(Region.USE_PREF_SIZE);
        flow.setMaxHeight(Double.MAX_VALUE);
        return flow;
    }

    private static Label gutter() {
        Label l = new Label();
        l.getStyleClass().add("diff-gutter");
        l.setMaxHeight(Double.MAX_VALUE);
        return l;
    }

    /**
     * One diff line: old number, new number, sign, code. The nodes are reused as the cell is recycled, and the
     * highlight behind the code is the row's share of the list's text selection, drawn from the flow's own shapes
     * so it follows the glyphs exactly.
     */
    private static final class LineCell extends ListCell<DiffPaneBuilder.Line> implements DiffSelection.Row {
        private final Label oldNo = gutter();
        private final Label newNo = gutter();
        private final Label sign = new Label();
        private final Text code = new Text();
        private final TextFlow flow;
        private final Path highlight = new Path();
        private final DiffSelection selection;
        private final HBox row;

        LineCell(DiffSelection selection) {
            this.selection = selection;
            sign.getStyleClass().add("diff-sign");
            sign.setMaxHeight(Double.MAX_VALUE);
            code.getStyleClass().add("diff-code");
            flow = new TextFlow(code);
            flow.setPadding(new Insets(0, 10, 0, 4));
            flow.setMinWidth(Region.USE_PREF_SIZE);
            flow.setMaxHeight(Double.MAX_VALUE);
            highlight.getStyleClass().add("diff-selection");
            highlight.setManaged(false);
            highlight.setMouseTransparent(true);
            // The highlight is unmanaged, so the layout neither moves nor resizes it; it is painted behind the
            // flow and put where the flow is, because the shapes it draws are in the flow's own coordinates.
            StackPane codeArea = new StackPane(highlight, flow);
            codeArea.setAlignment(Pos.CENTER_LEFT);
            codeArea.setMinWidth(Region.USE_PREF_SIZE);
            row = new HBox(oldNo, newNo, sign, codeArea);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setFillHeight(true);
            setPadding(Insets.EMPTY);
            selection.register(this);
            // The shapes can only be asked for once the text is laid out, which is after the item is set.
            flow.layoutBoundsProperty().addListener((o, a, v) -> paintSelection(selection.range()));
        }

        @Override
        public int line() {
            return isEmpty() || getItem() == null ? -1 : getIndex();
        }

        @Override
        public TextFlow flow() {
            return flow;
        }

        @Override
        public javafx.geometry.Bounds boundsInScene() {
            return getScene() == null ? null : localToScene(getBoundsInLocal());
        }

        @Override
        public void paintSelection(TranscriptCopy.Range range) {
            int length = code.getText() == null ? 0 : code.getText().length();
            int from = range.startIn(line(), length);
            int to = range.endIn(line(), length);
            if (line() < 0 || from < 0 || to <= from) {
                highlight.getElements().clear();
                return;
            }
            highlight.setLayoutX(flow.getLayoutX());
            highlight.setLayoutY(flow.getLayoutY());
            highlight.getElements().setAll(flow.getRangeShape(from, to, false));
        }

        @Override
        protected void updateItem(DiffPaneBuilder.Line line, boolean empty) {
            super.updateItem(line, empty);
            getStyleClass().removeAll("diff-added", "diff-removed", "diff-hunk", "diff-context", "diff-meta");
            setText(null);
            if (empty || line == null) {
                highlight.getElements().clear();
                setGraphic(null);
                return;
            }
            getStyleClass().add(styleClass(line.kind()));
            oldNo.setText(line.oldNumber() == null ? "" : String.valueOf(line.oldNumber()));
            newNo.setText(line.newNumber() == null ? "" : String.valueOf(line.newNumber()));
            sign.setText(signOf(line.kind()));
            code.setText(line.code());
            setGraphic(row);
            paintSelection(selection.range());
        }
    }
}
