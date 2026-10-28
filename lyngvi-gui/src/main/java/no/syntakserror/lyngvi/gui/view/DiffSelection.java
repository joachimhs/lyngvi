package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.TextFlow;

/**
 * Free text selection over a diff list, the way a page in a browser selects: press and drag over the code and the
 * selection follows the characters, across lines and on past the edge of the viewport; a double click takes the
 * word under it, a third click the line, ⌘/Ctrl+A the whole file, ⌘/Ctrl+C or the context menu copies exactly
 * what is highlighted, and Esc or a plain click clears it.
 *
 * <p>JavaFX gives selectable text only inside text controls, and the diff is a virtualised {@link ListView} whose
 * rows are recycled as it scrolls. So the selection is kept as a range over line numbers and character offsets —
 * which no recycling can invalidate — and every visible row paints its own part of it from
 * {@link TextFlow#getRangeShape}. The arithmetic is {@link TranscriptCopy.Range}, the same the transcript uses.
 */
final class DiffSelection {

    private static final KeyCombination COPY = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination ALL = new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);

    /** A row of the list: the line it shows, where it is, and how it draws its share of the selection. */
    interface Row {

        /** The index of the line this row shows, or −1 when the row is empty. */
        int line();

        /** The laid-out code of the row, which hit-tests points and shapes ranges. */
        TextFlow flow();

        /** Where the row sits on screen, for finding the row under the pointer. */
        Bounds boundsInScene();

        void paintSelection(TranscriptCopy.Range range);
    }

    private final ListView<DiffPaneBuilder.Line> list;
    private final List<Row> rows = new ArrayList<>();
    private int anchorLine = -1;
    private int anchorChar;
    private int focusLine = -1;
    private int focusChar;

    DiffSelection(ListView<DiffPaneBuilder.Line> list) {
        this.list = list;
        list.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onPressed);
        list.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onDragged);
        // Keeping the press from the cell is what stops the list selecting whole rows underneath the selection.
        list.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && anchorLine >= 0) e.consume();
        });
        list.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    /** Every row the list builds registers once; there are only ever as many as fit on the screen. */
    void register(Row row) {
        rows.add(row);
    }

    TranscriptCopy.Range range() {
        return TranscriptCopy.Range.of(anchorLine, anchorChar, focusLine, focusChar);
    }

    boolean isEmpty() {
        return range().isEmpty();
    }

    String selectedText() {
        return DiffPaneBuilder.selectedText(list.getItems(), range());
    }

    void copy() {
        String text = selectedText();
        if (!text.isEmpty()) MarkdownArea.copyToClipboard(text);
    }

    void selectAll() {
        if (list.getItems().isEmpty()) return;
        int last = list.getItems().size() - 1;
        set(0, 0, last, list.getItems().get(last).code().length());
    }

    void clear() {
        set(-1, 0, -1, 0);
    }

    private void set(int fromLine, int fromChar, int toLine, int toChar) {
        anchorLine = fromLine;
        anchorChar = fromChar;
        focusLine = toLine;
        focusChar = toChar;
        paint();
    }

    /** Repaints every row from the current range; rows outside it clear themselves. */
    void paint() {
        TranscriptCopy.Range range = range();
        for (Row row : rows) row.paintSelection(range);
    }

    private void onPressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        Row row = rowUnder(e.getSceneY(), true);
        if (row == null) return;
        list.requestFocus();
        int at = offsetIn(row, e.getSceneX());
        String code = codeOf(row.line());
        if (e.getClickCount() >= 3) {
            set(row.line(), 0, row.line(), code.length());
        } else if (e.getClickCount() == 2) {
            TranscriptCopy.Word word = TranscriptCopy.word(code, at);
            set(row.line(), word.from(), row.line(), word.to());
        } else {
            set(row.line(), at, row.line(), at);
        }
        e.consume();
    }

    private void onDragged(MouseEvent e) {
        if (anchorLine < 0 || !e.isPrimaryButtonDown()) return;
        Bounds view = list.localToScene(list.getBoundsInLocal());
        if (e.getSceneY() < view.getMinY()) scrollBy(-1);
        else if (e.getSceneY() > view.getMaxY()) scrollBy(1);
        Row row = rowUnder(e.getSceneY(), false);
        if (row != null) {
            focusLine = row.line();
            focusChar = offsetIn(row, e.getSceneX());
        }
        paint();
        e.consume();
    }

    private void onKey(KeyEvent e) {
        if (COPY.match(e)) {
            copy();
            e.consume();
        } else if (ALL.match(e)) {
            selectAll();
            e.consume();
        } else if (e.getCode() == KeyCode.ESCAPE && !isEmpty()) {
            clear();
            e.consume();
        }
    }

    private void scrollBy(int lines) {
        int target = Math.max(0, Math.min(list.getItems().size() - 1, focusLine + lines));
        list.scrollTo(target);
    }

    /**
     * The row the pointer is on. While dragging the pointer is often past the last row or above the first, and
     * then the nearest row in that direction is what the selection should run to; a press, which starts a
     * selection, has to land on a row — a click on a scroll bar is not a click on the text.
     */
    private Row rowUnder(double sceneY, boolean strict) {
        Bounds view = list.localToScene(list.getBoundsInLocal());
        Row above = null;
        Row below = null;
        for (Row row : rows) {
            if (row.line() < 0 || row.line() >= list.getItems().size()) continue;
            Bounds b = row.boundsInScene();
            if (b == null || !b.intersects(view)) continue;
            if (sceneY >= b.getMinY() && sceneY <= b.getMaxY()) return row;
            if (b.getMaxY() < sceneY && (above == null || b.getMaxY() > above.boundsInScene().getMaxY())) above = row;
            if (b.getMinY() > sceneY && (below == null || b.getMinY() < below.boundsInScene().getMinY())) below = row;
        }
        return strict ? null : above != null ? above : below;
    }

    /** Where a point lands in a row's text: the character boundary nearest it, clamped to the line. */
    private int offsetIn(Row row, double sceneX) {
        TextFlow flow = row.flow();
        Point2D local = flow.sceneToLocal(sceneX, 0);
        // The y is the row's own middle: the pointer may be anywhere in the row, or past it while dragging.
        int at = flow.getHitInfo(new Point2D(local.getX(), flow.getHeight() / 2)).getInsertionIndex();
        return Math.max(0, Math.min(at, codeOf(row.line()).length()));
    }

    private String codeOf(int line) {
        return line < 0 || line >= list.getItems().size() ? "" : list.getItems().get(line).code();
    }
}
