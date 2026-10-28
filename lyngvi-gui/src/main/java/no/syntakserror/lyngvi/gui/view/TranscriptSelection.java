package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Selecting transcript text with the pointer, the way a page in a browser is selected: press and drag over anything
 * the transcript shows — a message, a heading, a code block, a tool call's command — and ⌘/Ctrl+C copies exactly
 * what is highlighted, across bubbles. A double click takes the word (a path or a model id counting as one), a third
 * click the line, shift-click extends, ⌘/Ctrl+A takes all of it, and Esc or a plain click clears it.
 *
 * <p>JavaFX has no selectable text outside its text controls, so this does the work itself: the transcript is
 * scanned into {@link TranscriptCopy.Run}s — every text flow and every label's own text node, in reading order —
 * the pointer is hit-tested onto one of them, and the highlight is drawn as a path per run onto an overlay that
 * lies over the transcript and scrolls with it. Only runs inside the viewport are drawn, so selecting a long
 * conversation costs no more than selecting a short one. Text areas (tool output, reasoning) and the diff lists
 * select and copy themselves, so they are left out of the scan.
 *
 * <p>Every bubble also carries a {@link Supplier} of its own copy text (see {@link #setText}), which is what
 * «Copy message» puts on the clipboard: the message as it was written, markdown and all, rather than as it is laid
 * out. Any change to the transcript's children drops the selection.
 */
final class TranscriptSelection {

    private static final Object TEXT = new Object();
    /** Style class of the highlight drawn behind selected text. */
    private static final String HIGHLIGHT = "transcript-selection";
    /** How far one drag event past the viewport's edge scrolls, in pixels. */
    private static final double NUDGE = 24;

    private final ScrollPane scroll;
    private final Pane box;
    private final Pane overlay;
    /** The transcript as it was when the selection began; indices in the range point into this list. */
    private List<Piece> pieces = List.of();
    /** Code blocks whose scrolling already redraws the highlight; emptied with the transcript they are in. */
    private final Set<ScrollPane> scrolled = Collections.newSetFromMap(new IdentityHashMap<>());
    private int anchorRun = -1;
    private int anchorChar;
    private int focusRun = -1;
    private int focusChar;
    private boolean dragging;

    TranscriptSelection(ScrollPane scroll, Pane box, Pane overlay) {
        this.scroll = scroll;
        this.box = box;
        this.overlay = overlay;
        overlay.setMouseTransparent(true);
        scroll.setFocusTraversable(true);
        box.getChildren().addListener((ListChangeListener<Node>) c -> {
            scrolled.clear();
            clear();
        });
        // Filters, not handlers: a control's own input map handles MOUSE_PRESSED on the way up and consumes it even
        // when all it did was take the focus, so a press inside one never reached a handler on the transcript. A
        // code block scrolls its long lines in a scroll pane of its own and a tool call is a titled pane — both do
        // this — which is why a selection could be dragged into a code block from the line above it, but not begun
        // in the middle of one. A filter runs on the way down instead, before any of them sees the press.
        box.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onPressed);
        box.addEventFilter(MouseEvent.DRAG_DETECTED, this::onDragDetected);
        box.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onDragged);
        box.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> dragging = false);
        box.addEventFilter(MouseEvent.MOUSE_CLICKED, this::onClicked);
        scroll.addEventHandler(KeyEvent.KEY_PRESSED, this::onKey);
        // the highlight is geometry, so it is redrawn whenever the transcript scrolls or is laid out again
        scroll.vvalueProperty().addListener((o, a, v) -> draw());
        box.layoutBoundsProperty().addListener((o, a, v) -> draw());
    }

    /** Registers what copying the whole bubble yields. */
    static void setText(Node bubble, Supplier<String> text) {
        bubble.getProperties().put(TEXT, text);
    }

    boolean isEmpty() {
        return range().isEmpty();
    }

    /** Whether the selection touches the bubble, which decides what its context menu offers. */
    boolean covers(Node bubble) {
        int index = box.getChildren().indexOf(bubble);
        if (index < 0) return false;
        TranscriptCopy.Range range = range();
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).bubble() == index && range.covers(i)) return true;
        }
        return false;
    }

    void selectAll() {
        pieces = scan();
        if (pieces.isEmpty()) return;
        select(0, 0, pieces.size() - 1, pieces.getLast().text().length());
    }

    /** Selects everything the bubble shows, so what «Copy message» took is visible. */
    void selectMessage(Node bubble) {
        pieces = scan();
        int index = box.getChildren().indexOf(bubble);
        int first = -1;
        int last = -1;
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).bubble() != index) continue;
            if (first < 0) first = i;
            last = i;
        }
        if (first < 0) clear();
        else select(first, 0, last, pieces.get(last).text().length());
    }

    void clear() {
        anchorRun = -1;
        focusRun = -1;
        pieces = List.of();
        overlay.getChildren().clear();
    }

    /** The selected text, top to bottom. */
    String text() {
        List<TranscriptCopy.Run> runs = new ArrayList<>();
        for (Piece p : pieces) runs.add(new TranscriptCopy.Run(p.text(), p.bubble(), p.line()));
        return TranscriptCopy.text(runs, range());
    }

    /** Puts the selection on the clipboard; returns false when there is nothing to copy. */
    boolean copy() {
        String t = text();
        if (t.isEmpty()) return false;
        MarkdownArea.copyToClipboard(t);
        return true;
    }

    /** Copies the whole bubble as it was written, and highlights it; returns false when it says nothing. */
    boolean copyMessage(Node bubble) {
        selectMessage(bubble);
        Object registered = bubble.getProperties().get(TEXT);
        String t = registered instanceof Supplier<?> s && s.get() instanceof String str ? str : text();
        if (t.isBlank()) return false;
        MarkdownArea.copyToClipboard(t);
        return true;
    }

    private TranscriptCopy.Range range() {
        return TranscriptCopy.Range.of(anchorRun, anchorChar, focusRun, focusChar);
    }

    private void select(int fromRun, int fromChar, int toRun, int toChar) {
        anchorRun = fromRun;
        anchorChar = fromChar;
        focusRun = toRun;
        focusChar = toChar;
        draw();
    }

    // ---- the pointer ----

    private void onPressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || inOwnControl(e)) return;
        if (e.isShiftDown() && !pieces.isEmpty() && anchorRun >= 0) {
            extendTo(e);
            scroll.requestFocus();
            e.consume();
            return;
        }
        pieces = scan();
        Hit hit = hitAt(e);
        anchorRun = hit.run();
        anchorChar = hit.index();
        focusRun = hit.run();
        focusChar = hit.index();
        overlay.getChildren().clear();
    }

    private void onDragDetected(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || anchorRun < 0 || inOwnControl(e)) return;
        dragging = true;
        scroll.requestFocus();
        e.consume();
    }

    private void onDragged(MouseEvent e) {
        if (!dragging) return;
        extendTo(e);
        // past the viewport's edge: creep in that direction, one step per event
        double y = scroll.sceneToLocal(e.getSceneX(), e.getSceneY()).getY();
        double viewport = scroll.getViewportBounds().getHeight();
        double range = box.getBoundsInLocal().getHeight() - viewport;
        if (range > 0 && (y < 0 || y > viewport)) {
            scroll.setVvalue(Math.max(0, Math.min(1, scroll.getVvalue() + (y < 0 ? -NUDGE : NUDGE) / range)));
        }
        e.consume();
    }

    /** A plain click clears the selection; a double click takes the word under it and a third click the whole run. */
    private void onClicked(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || !e.isStillSincePress() || e.isShiftDown() || inOwnControl(e)) return;
        if (e.getClickCount() == 1) {
            clear();
            return;
        }
        Hit hit = hitAt(e);
        if (hit.run() < 0) return;
        String text = pieces.get(hit.run()).text();
        if (e.getClickCount() == 2) {
            TranscriptCopy.Word word = TranscriptCopy.word(text, hit.index());
            select(hit.run(), word.from(), hit.run(), word.to());
        } else {
            select(hit.run(), 0, hit.run(), text.length());
        }
        scroll.requestFocus();
        e.consume();
    }

    private void onKey(KeyEvent e) {
        if (e.isShortcutDown() && e.getCode() == KeyCode.C) {
            if (copy()) e.consume();
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.A) {
            selectAll();
            e.consume();
        } else if (e.getCode() == KeyCode.ESCAPE && !isEmpty()) {
            clear();
            e.consume();
        }
    }

    private void extendTo(MouseEvent e) {
        Hit hit = hitAt(e);
        if (hit.run() < 0) return;
        focusRun = hit.run();
        focusChar = hit.index();
        draw();
    }

    /** A place in the transcript: which run, and which character of it. */
    private record Hit(int run, int index) {}

    private Hit hitAt(MouseEvent e) {
        List<Bounds> bounds = new ArrayList<>();
        for (Piece p : pieces) bounds.add(visible(p.node()));
        int run = TranscriptCopy.nearest(bounds, e.getSceneX(), e.getSceneY());
        if (run < 0) return new Hit(-1, 0);
        // onto the part of the run that is on the screen: a point past the edge of a code block takes the last
        // character the block shows, not the one that happens to be under the pointer behind it
        Bounds seen = bounds.get(run);
        Point2D at = new Point2D(Math.max(seen.getMinX(), Math.min(e.getSceneX(), seen.getMaxX())),
                Math.max(seen.getMinY(), Math.min(e.getSceneY(), seen.getMaxY())));
        return new Hit(run, pieces.get(run).charAt(at));
    }

    /**
     * Where a run can be seen, in scene coordinates. A code block's long line reaches well past the block, and the
     * pointer must be hit-tested onto what is on the screen: otherwise a press in the margin beside a scrolled block
     * would land on the characters hidden behind it.
     */
    private Bounds visible(Node run) {
        Bounds b = run.localToScene(run.getLayoutBounds());
        ScrollPane inner = innerScroll(run);
        return inner == null ? b : TranscriptCopy.clamp(b, inner.localToScene(inner.getLayoutBounds()));
    }

    /** The scroll pane a run sits in inside the transcript — a code block's — or {@code null} when there is none. */
    private ScrollPane innerScroll(Node run) {
        for (Node n = run.getParent(); n != null && n != box; n = n.getParent()) {
            if (n instanceof ScrollPane inner) return inner;
        }
        return null;
    }

    /**
     * A press the pointer owes to a control rather than to the text, and which therefore never starts a selection
     * here: a text area or a diff list, which select and copy themselves; a scroll bar, whose thumb is dragged (a
     * code block has one of its own); and the title of a tool call's pane, where the drag that would select the
     * title is the click that opens and closes the pane — and where the title is ellipsized anyway, so selecting it
     * would yield «…» rather than what it says. The pane's content is text like any other.
     */
    private boolean inOwnControl(MouseEvent e) {
        for (Node n = e.getTarget() instanceof Node t ? t : null; n != null; n = n.getParent()) {
            if (n instanceof TextInputControl || n instanceof ListView<?> || n instanceof ScrollBar) return true;
            if (n.getParent() instanceof TitledPane && n.getStyleClass().contains("title")) return true;
        }
        return false;
    }

    // ---- the highlight ----

    /**
     * Draws the selection: one path per selected run, placed where that run is. Runs above the viewport are stepped
     * over and the first one below it ends the loop, so only what can be seen is built.
     */
    private void draw() {
        overlay.getChildren().clear();
        TranscriptCopy.Range range = range();
        if (range.isEmpty()) return;
        Bounds view = scroll.localToScene(scroll.getBoundsInLocal());
        for (int i = range.fromRun(); i < pieces.size() && i <= range.toRun(); i++) {
            Piece piece = pieces.get(i);
            if (piece.node().getScene() == null) {
                // the transcript was rebuilt under the selection: the runs are gone, and so is what was selected
                clear();
                return;
            }
            Bounds bounds = piece.node().localToScene(piece.node().getLayoutBounds());
            if (bounds.getMaxY() < view.getMinY()) continue;
            if (bounds.getMinY() > view.getMaxY()) break;
            String text = piece.text();
            int from = range.startIn(i, text.length());
            int to = range.endIn(i, text.length());
            if (to <= from) continue;
            Path path = new Path(piece.shape(from, to));
            path.getStyleClass().add(HIGHLIGHT);
            path.setManaged(false);
            path.setMouseTransparent(true);
            Point2D origin = overlay.sceneToLocal(piece.node().localToScene(0, 0));
            path.setLayoutX(origin.getX());
            path.setLayoutY(origin.getY());
            clip(path, piece.node(), origin);
            overlay.getChildren().add(path);
        }
    }

    /**
     * Keeps a highlight inside the box that scrolls the run. A code block scrolls its long lines in a scroll pane of
     * its own, so a line selected there reaches past the block — and the overlay, which is clipped by the
     * transcript's viewport and knows nothing of the block's, would otherwise draw it over what stands beside it.
     */
    private void clip(Path path, Node run, Point2D origin) {
        ScrollPane inner = innerScroll(run);
        if (inner == null) return;
        Bounds view = overlay.sceneToLocal(inner.localToScene(inner.getLayoutBounds()));
        path.setClip(new Rectangle(view.getMinX() - origin.getX(), view.getMinY() - origin.getY(),
                view.getWidth(), view.getHeight()));
    }

    // ---- the transcript's text, in reading order ----

    /** Scanned again whenever a selection begins: the transcript grows and changes while a turn runs. */
    private List<Piece> scan() {
        List<Piece> out = new ArrayList<>();
        int[] line = {0};
        List<Node> bubbles = box.getChildrenUnmodifiable();
        for (int i = 0; i < bubbles.size(); i++) scan(bubbles.get(i), i, -1, line, out);
        for (Piece p : out) follow(innerScroll(p.node()));
        return out;
    }

    /**
     * A code block scrolls under its own highlight, and the highlight is geometry: it is redrawn when the block is
     * scrolled sideways, the way it is when the transcript is scrolled. Once per block — the blocks are dropped
     * with the transcript they belong to.
     */
    private void follow(ScrollPane inner) {
        if (inner == null || !scrolled.add(inner)) return;
        inner.hvalueProperty().addListener((o, a, v) -> draw());
        inner.vvalueProperty().addListener((o, a, v) -> draw());
    }

    /**
     * Walks one bubble. {@code line} is the row the runs found here share — everything inside an {@link HBox} is on
     * one line, so a bullet keeps its item and a diff line its sign, and so is a table row — and −1 when each run is
     * a line of its own.
     */
    private void scan(Node node, int bubble, int line, int[] next, List<Piece> out) {
        if (node == null || !node.isVisible()) return;
        if (node instanceof TextInputControl || node instanceof ListView<?>) return;  // selects and copies itself
        if (node instanceof ScrollBar) return;  // a scroll bar carries no text of the transcript's
        if (node instanceof TextFlow flow) {
            out.add(new Piece(flow, bubble, line < 0 ? next[0]++ : line));
        } else if (node instanceof Text text) {
            out.add(new Piece(text, bubble, line < 0 ? next[0]++ : line));
        } else if (node instanceof TitledPane pane) {
            // the header is the graphic (a tool call) or the pane's own text; the content only counts when open
            scan(pane.getGraphic(), bubble, line, next, out);
            if (pane.isExpanded()) scan(pane.getContent(), bubble, line, next, out);
        } else if (node instanceof Labeled labeled) {
            // a label lays its text out in a text node of its own, which is what can be hit-tested and shaped
            if (labeled.lookup(".text") instanceof Text inner) out.add(new Piece(inner, bubble, line < 0 ? next[0]++ : line));
        } else if (node instanceof HBox row) {
            int own = line < 0 ? next[0]++ : line;
            for (Node child : row.getChildrenUnmodifiable()) scan(child, bubble, own, next, out);
        } else if (node instanceof GridPane grid) {
            // a table: the cells of a row are on that row's line, so a copied row reads as a row
            Map<Integer, List<Node>> rows = new TreeMap<>();
            for (Node cell : grid.getChildrenUnmodifiable()) {
                Integer index = GridPane.getRowIndex(cell);
                rows.computeIfAbsent(index == null ? 0 : index, k -> new ArrayList<>()).add(cell);
            }
            for (List<Node> cells : rows.values()) {
                int own = line < 0 ? next[0]++ : line;
                for (Node cell : cells) scan(cell, bubble, own, next, out);
            }
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) scan(child, bubble, line, next, out);
        }
    }

    /** One run of laid-out text: the node that can hit-test and shape it, and where it sits in the transcript. */
    private record Piece(Node node, int bubble, int line) {

        String text() {
            if (node instanceof Text t) return t.getText() == null ? "" : t.getText();
            StringBuilder sb = new StringBuilder();
            for (Node child : ((TextFlow) node).getChildrenUnmodifiable()) {
                // a flow counts a child that is not text as one character, and the offsets must agree with it
                if (child instanceof Text t) sb.append(t.getText() == null ? "" : t.getText());
                else sb.append("\uFFFC");
            }
            return sb.toString();
        }

        /** The character the point is at; a point outside the run is pulled onto its nearest edge. */
        int charAt(Point2D scene) {
            Point2D local = node.sceneToLocal(scene);
            Bounds b = node.getLayoutBounds();
            Point2D inside = new Point2D(Math.max(b.getMinX(), Math.min(local.getX(), b.getMaxX())),
                    Math.max(b.getMinY(), Math.min(local.getY(), b.getMaxY())));
            // the flow's own methods, which count its padding in; Text has none to count
            return node instanceof Text t ? t.hitTest(inside).getInsertionIndex() : ((TextFlow) node).getHitInfo(inside).getInsertionIndex();
        }

        PathElement[] shape(int from, int to) {
            return node instanceof Text t ? t.rangeShape(from, to) : ((TextFlow) node).getRangeShape(from, to, false);
        }
    }
}
