package no.syntakserror.lyngvi.gui.view;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;

/**
 * Renders markdown from {@link MarkdownParser}: headings, paragraphs, lists, code, quotes, tables, rules, inline
 * styles.
 */
public final class MarkdownArea extends VBox {

    /** What a table column is never squeezed below, so a narrow column keeps a few characters of its content. */
    private static final double MIN_COLUMN = 60;

    private String markdown = "";

    public MarkdownArea() {
        getStyleClass().add("markdown");
        setSpacing(6);
        setFillWidth(true);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        ContextMenu menu = new ContextMenu();
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> copyToClipboard(markdown));
        menu.getItems().add(copy);
        setOnContextMenuRequested(e -> { menu.show(this, e.getScreenX(), e.getScreenY()); e.consume(); });
    }

    public String getMarkdown() {
        return markdown;
    }

    public void setMarkdown(String text) {
        this.markdown = text == null ? "" : text;
        getChildren().clear();
        for (MarkdownParser.Block b : MarkdownParser.parse(markdown)) getChildren().add(render(b));
    }

    private Node render(MarkdownParser.Block b) {
        switch (b.kind()) {
            case HEADING -> {
                Label l = new Label(b.text());
                l.setWrapText(true);
                l.getStyleClass().addAll("md-heading", "md-h" + Math.min(b.level(), 3));
                return l;
            }
            case CODE -> {
                if (isDiff(b)) return DiffArea.block(b.text());
                Label code = new Label(b.text());
                code.getStyleClass().add("md-code-text");
                code.setWrapText(false);
                // Long code lines scroll inside the block instead of widening the bubble.
                javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(code);
                scroll.setFitToHeight(true);
                scroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
                scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
                scroll.setMinWidth(0);
                scroll.getStyleClass().add("md-code-scroll");
                VBox box = new VBox(scroll);
                box.getStyleClass().add("md-code");
                box.setMinWidth(0);
                box.setMaxWidth(Double.MAX_VALUE);
                box.setPadding(new Insets(6, 8, 6, 8));
                ContextMenu menu = new ContextMenu();
                MenuItem copy = new MenuItem("Copy code");
                copy.setOnAction(e -> copyToClipboard(b.text()));
                menu.getItems().add(copy);
                box.setOnContextMenuRequested(e -> { menu.show(box, e.getScreenX(), e.getScreenY()); e.consume(); });
                return box;
            }
            case QUOTE -> {
                TextFlow f = flow(b.text());
                f.getStyleClass().add("md-quote");
                f.setPadding(new Insets(4, 8, 4, 10));
                return f;
            }
            case RULE -> {
                return new Separator();
            }
            case TABLE -> {
                return table(b.table());
            }
            case LIST -> {
                VBox list = new VBox(2);
                list.getStyleClass().add("md-list");
                int n = 1;
                for (String item : b.items()) {
                    Text bullet = new Text(b.ordered() ? (n++) + ". " : "•  ");
                    bullet.getStyleClass().add("md-bullet");
                    TextFlow f = flow(item);
                    HBox.setHgrow(f, javafx.scene.layout.Priority.ALWAYS);
                    f.setMaxWidth(Double.MAX_VALUE);
                    HBox row = new HBox(new TextFlow(bullet), f);
                    row.setPadding(new Insets(0, 0, 0, 8));
                    list.getChildren().add(row);
                }
                return list;
            }
            default -> {
                return flow(b.text());
            }
        }
    }

    /**
     * A pipe table as a grid: the header over the rows, every column as wide as its widest cell while the bubble has
     * room for them all, and no wider — a two-column table does not stretch across the conversation. When the bubble
     * is too narrow, {@link GridPane} takes the same amount off every column until it hits the column's minimum, so
     * the wide prose column gives up its width first and wraps, and the short ones stay readable.
     */
    private static Node table(MarkdownParser.Table t) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
        grid.setMinWidth(0);
        grid.setMaxWidth(Region.USE_PREF_SIZE);
        int columns = t.header().size();
        for (int c = 0; c < columns; c++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setMinWidth(MIN_COLUMN);
            column.setHgrow(Priority.NEVER);
            grid.getColumnConstraints().add(column);
        }
        for (int c = 0; c < columns; c++) grid.add(cell(t.header().get(c), t.align(c), c == columns - 1, true), c, 0);
        int row = 1;
        for (List<String> cells : t.rows()) {
            for (int c = 0; c < cells.size(); c++) grid.add(cell(cells.get(c), t.align(c), c == columns - 1, false), c, row);
            row++;
        }
        return grid;
    }

    private static Node cell(String text, MarkdownParser.Align align, boolean last, boolean header) {
        TextFlow f = flow(text);
        f.getStyleClass().add("md-table-cell");
        if (header) f.getStyleClass().add("md-table-head");
        f.setTextAlignment(switch (align) {
            case CENTER -> TextAlignment.CENTER;
            case RIGHT -> TextAlignment.RIGHT;
            case LEFT -> TextAlignment.LEFT;
        });
        if (!last) {
            // the cells of a row are one line to the transcript selection, which puts the runs of a line one after
            // the other: two spaces at the end of a cell keep the copied cells apart, and show as cell padding
            Text gap = new Text("  ");
            gap.getStyleClass().add("md-text");
            f.getChildren().add(gap);
        }
        return f;
    }

    /** A fenced block is a diff when it says so ({@code ```diff}, {@code ```patch}) or plainly is one. */
    static boolean isDiff(MarkdownParser.Block b) {
        String lang = b.language() == null ? "" : b.language().toLowerCase(java.util.Locale.ROOT);
        return lang.equals("diff") || lang.equals("patch") || DiffPaneBuilder.looksLikeDiff(b.text());
    }

    private static TextFlow flow(String text) {
        TextFlow f = new TextFlow();
        f.getStyleClass().add("md-paragraph");
        f.setMinWidth(0);
        f.setMaxWidth(Double.MAX_VALUE);
        List<MarkdownParser.Run> runs = MarkdownParser.inline(text);
        for (MarkdownParser.Run r : runs) {
            if (r.link() != null) {
                // Text, not Hyperlink: a control inside a flow is one opaque character to the flow, and would be a
                // hole in the text the transcript selection can copy. A plain click opens it; a drag selects it.
                Text link = new Text(r.text());
                link.getStyleClass().addAll("md-text", "md-link");
                link.setCursor(javafx.scene.Cursor.HAND);
                link.setOnMouseClicked(e -> { if (e.isStillSincePress()) browse(r.link()); });
                f.getChildren().add(link);
                continue;
            }
            Text t = new Text(r.text());
            t.getStyleClass().add("md-text");
            if (r.bold()) t.getStyleClass().add("md-bold");
            if (r.italic()) t.getStyleClass().add("md-italic");
            if (r.code()) t.getStyleClass().add("md-inline-code");
            f.getChildren().add(t);
        }
        return f;
    }

    static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
            // no browser available
        }
    }

    static void copyToClipboard(String text) {
        ClipboardContent c = new ClipboardContent();
        c.putString(text);
        Clipboard.getSystemClipboard().setContent(c);
    }
}
