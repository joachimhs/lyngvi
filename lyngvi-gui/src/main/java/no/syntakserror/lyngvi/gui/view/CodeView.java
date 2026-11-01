package no.syntakserror.lyngvi.gui.view;

import java.util.List;
import java.util.ResourceBundle;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Source code with its words coloured, for reading. One line per row with a line-number gutter, the colours from
 * {@link SyntaxScanner} over a {@link Syntax}, and free text selection across the lines ({@link LineSelection}),
 * where copying yields the code and never the gutter.
 *
 * <p>The rows are a virtualised {@link ListView}, which is what makes a large file cheap: only the lines on screen
 * are nodes at all, and a line is tokenised when it scrolls into sight. What is worked out for the whole file up
 * front is one {@code int} per line — the state the line begins in, so that a line inside a block comment is
 * coloured as a comment even when the opening {@code /*} is thousands of lines above the viewport.
 *
 * <p>Read-only on purpose: JavaFX's own text controls cannot colour part of their content, so the pane that uses
 * this keeps the plain editor for writing ({@link FilesController}) and shows this for reading.
 */
public final class CodeView extends StackPane {

    /** Every row the same height, so the list can virtualise without measuring: the diff's row height. */
    private static final double LINE_HEIGHT = DiffArea.LINE_HEIGHT;

    private final ListView<String> list = new ListView<>();
    private final LineSelection<String> selection;
    private Syntax syntax;
    private int[] carries = new int[0];

    public CodeView(ResourceBundle bundle) {
        getStyleClass().add("code-view");
        list.getStyleClass().add("code-list");
        list.setFixedCellSize(LINE_HEIGHT);
        // Source is text, not a list of choices: with no selection model a line can never be picked out whole,
        // and the only selection on screen is the one over the characters.
        list.setSelectionModel(null);
        selection = new LineSelection<>(list, line -> line);
        list.setCellFactory(v -> new CodeCell());
        ContextMenu menu = new ContextMenu();
        MenuItem copySelection = new MenuItem(label(bundle, "gui.files.copy.selection", "Copy selection"));
        copySelection.setOnAction(e -> selection.copy());
        MenuItem copyAll = new MenuItem(label(bundle, "gui.files.copy.file", "Copy file"));
        copyAll.setOnAction(e -> MarkdownArea.copyToClipboard(String.join("\n", list.getItems())));
        menu.getItems().addAll(copySelection, copyAll);
        menu.setOnShowing(e -> copySelection.setDisable(selection.isEmpty()));
        list.setContextMenu(menu);
        getChildren().add(list);
    }

    /** A label from the bundle, with an English fallback for the callers that have no bundle. */
    private static String label(ResourceBundle bundle, String key, String fallback) {
        try {
            return bundle == null ? fallback : bundle.getString(key);
        } catch (java.util.MissingResourceException e) {
            return fallback;
        }
    }

    /**
     * Shows a file. The lines are split and the per-line states worked out here, which is the one pass over the
     * whole text; the colouring itself happens per row, as the list asks for it.
     */
    public void show(String text, Syntax syntax) {
        this.syntax = syntax;
        selection.clear();
        if (syntax == null) {  // nothing to colour by: an empty view, rather than a view of uncoloured lines
            carries = new int[0];
            list.getItems().clear();
            return;
        }
        List<String> lines = List.of((text == null ? "" : text).split("\n", -1));
        // A file written on Windows would otherwise carry a carriage return into the last token of every line.
        lines = lines.stream().map(l -> l.endsWith("\r") ? l.substring(0, l.length() - 1) : l).toList();
        carries = SyntaxScanner.carries(lines, syntax);
        list.getItems().setAll(lines);
        list.scrollTo(0);
    }

    /** The language the view is colouring by, for the caller that wants to name it. */
    public Syntax syntax() {
        return syntax;
    }

    /** One line: the number in the gutter, and the code as coloured spans in a flow the selection can shape. */
    private final class CodeCell extends ListCell<String> implements LineSelection.Row {

        private final Label number = new Label();
        private final TextFlow flow = new TextFlow();
        private final Path highlight = new Path();
        private final HBox row;

        CodeCell() {
            number.getStyleClass().add("code-line-no");
            number.setMinWidth(Region.USE_PREF_SIZE);
            number.setMaxHeight(Double.MAX_VALUE);
            flow.getStyleClass().add("code-flow");
            flow.setPadding(new Insets(0, 10, 0, 8));
            flow.setMinWidth(Region.USE_PREF_SIZE);
            flow.setMaxHeight(Double.MAX_VALUE);
            highlight.getStyleClass().add("diff-selection");
            highlight.setManaged(false);
            highlight.setMouseTransparent(true);
            // Unmanaged and painted behind the flow, in the flow's own coordinates: the same arrangement the
            // diff's rows use, because the shapes come from the same place.
            StackPane codeArea = new StackPane(highlight, flow);
            codeArea.setAlignment(Pos.CENTER_LEFT);
            codeArea.setMinWidth(Region.USE_PREF_SIZE);
            row = new HBox(number, codeArea);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setFillHeight(true);
            setPadding(Insets.EMPTY);
            selection.register(this);
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
        public Bounds boundsInScene() {
            return getScene() == null ? null : localToScene(getBoundsInLocal());
        }

        @Override
        public void paintSelection(TranscriptCopy.Range range) {
            String code = getItem() == null ? "" : getItem();
            int from = range.startIn(line(), code.length());
            int to = range.endIn(line(), code.length());
            if (line() < 0 || from < 0 || to <= from) {
                highlight.getElements().clear();
                return;
            }
            highlight.setLayoutX(flow.getLayoutX());
            highlight.setLayoutY(flow.getLayoutY());
            highlight.getElements().setAll(flow.getRangeShape(from, to, false));
        }

        @Override
        protected void updateItem(String code, boolean empty) {
            super.updateItem(code, empty);
            setText(null);
            if (empty || code == null) {
                highlight.getElements().clear();
                flow.getChildren().clear();
                setGraphic(null);
                return;
            }
            number.setText(String.valueOf(getIndex() + 1));
            flow.getChildren().setAll(spans(code, getIndex()));
            setGraphic(row);
        }

        /** The line as coloured text nodes. The spans cover the whole line, so the flow holds it exactly once. */
        private List<Text> spans(String code, int index) {
            int carry = index >= 0 && index < carries.length ? carries[index] : 0;
            return SyntaxScanner.tokens(code, carry, syntax).stream().map(t -> {
                Text text = new Text(code.substring(t.from(), t.to()));
                text.getStyleClass().addAll("code-text", t.kind().styleClass());
                return text;
            }).toList();
        }
    }
}
