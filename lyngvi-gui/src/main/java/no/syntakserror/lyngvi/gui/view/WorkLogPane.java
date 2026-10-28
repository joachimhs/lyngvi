package no.syntakserror.lyngvi.gui.view;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import no.syntakserror.lyngvi.core.session.Conversation;

/**
 * The work log under a bubble: a "Thinking" block, notices with severity, and collapsible tool calls. Updates are
 * applied in place by entry key, so an expanded pane stays expanded while the run continues.
 */
public final class WorkLogPane extends VBox {

    private final java.util.ResourceBundle bundle;
    private final Map<String, Node> byKey = new HashMap<>();

    public WorkLogPane(java.util.ResourceBundle bundle) {
        this.bundle = bundle;
        getStyleClass().add("work-log");
        setSpacing(4);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
    }

    private Runnable onToggle = () -> {};

    /** Called just before a pane expands or collapses, so the transcript can keep its viewport in place. */
    public void setOnToggle(Runnable onToggle) {
        this.onToggle = onToggle == null ? () -> {} : onToggle;
    }

    /** No expand animation: one height change per toggle, which the transcript compensates in one step. */
    private TitledPane toggling(TitledPane p) {
        p.setAnimated(false);
        p.expandedProperty().addListener((o, a, v) -> onToggle.run());
        return p;
    }

    /** Panes and text areas must not widen the bubble: the title ellipsizes, the output wraps. */
    private static <T extends javafx.scene.layout.Region> T shrinkable(T node) {
        node.setMinWidth(0);
        node.setMaxWidth(Double.MAX_VALUE);
        return node;
    }

    public void setModel(WorkLogModel model) {
        if (model == null) {
            getChildren().clear();
            byKey.clear();
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        int position = 0;
        for (WorkLogModel.Entry e : model.entries()) {
            seen.add(e.key());
            Node node = byKey.get(e.key());
            if (node == null) {
                node = create(e);
                byKey.put(e.key(), node);
                getChildren().add(Math.min(position, getChildren().size()), node);
            } else {
                update(node, e);
                if (getChildren().indexOf(node) != position) {
                    getChildren().remove(node);
                    getChildren().add(Math.min(position, getChildren().size()), node);
                }
            }
            position++;
        }
        byKey.keySet().removeIf(k -> {
            if (seen.contains(k)) return false;
            getChildren().remove(byKey.get(k));
            return true;
        });
    }

    private Node create(WorkLogModel.Entry e) {
        switch (e.kind()) {
            case REASONING -> {
                TitledPane p = toggling(shrinkable(new TitledPane(bundle.getString("gui.chat.thinking"), textArea(e.text()))));
                p.setExpanded(false);
                p.getStyleClass().add("work-reasoning");
                return p;
            }
            case TEXT -> {
                MarkdownArea md = shrinkable(new MarkdownArea());
                md.getStyleClass().add("work-text");
                md.setMarkdown(e.text());
                return md;
            }
            case NOTICE -> {
                Label l = shrinkable(new Label(e.text()));
                l.setWrapText(true);
                l.getStyleClass().addAll("work-notice", e.level() == null ? "info" : e.level());
                return l;
            }
            default -> {
                TitledPane p = toggling(shrinkable(new TitledPane(null, null)));
                p.setExpanded(false);
                ToolCall.Category category = ToolCall.categoryOf(e.tool().name());
                p.getStyleClass().addAll("work-tool", "tool-" + category.key());
                p.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                p.setGraphic(header(p, category));
                setHeader(p, e.tool());
                setToolContent(p, e.tool());
                if (e.tool().isError()) p.getStyleClass().add("error");
                return p;
            }
        }
    }

    /** The header's mutable parts: the title, and the +/- counts of an edit shown at the far right. */
    private record Header(Label title, Label additions, Label deletions) {}

    /** Space the pane's arrow and paddings take, which the header must leave free to reach the right edge. */
    private static final double HEADER_INSET = 44;

    /**
     * The whole header as one row: badge, title (ellipsized) and the change counts pushed to the right edge, so an
     * edit's size is visible without opening the pane.
     */
    private Node header(TitledPane p, ToolCall.Category category) {
        Label title = shrinkable(new Label());
        title.getStyleClass().add("tool-title");
        HBox.setHgrow(title, Priority.ALWAYS);
        Label add = new Label();
        add.getStyleClass().add("diff-count-add");
        Label del = new Label();
        del.getStyleClass().add("diff-count-del");
        HBox row = shrinkable(new HBox(8, badge(category), title, add, del));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("tool-header");
        row.prefWidthProperty().bind(p.widthProperty().subtract(HEADER_INSET));
        p.getProperties().put(Header.class, new Header(title, add, del));
        return row;
    }

    private void setHeader(TitledPane p, Conversation.WorkItem w) {
        Header h = (Header) p.getProperties().get(Header.class);
        h.title().setText(toolTitle(w));
        Optional<ToolCall.Counts> counts = ToolCall.changeCounts(w.name(), w.input());
        h.additions().setText(counts.map(c -> "+" + c.additions()).orElse(""));
        h.deletions().setText(counts.map(c -> "\u2212" + c.deletions()).orElse(""));
        boolean show = counts.isPresent();
        h.additions().setVisible(show); h.additions().setManaged(show);
        h.deletions().setVisible(show); h.deletions().setManaged(show);
    }

    /** The kind of call, so an edit is told from a lookup or a command before the pane is opened. */
    private Label badge(ToolCall.Category category) {
        Label badge = new Label(bundle.getString("gui.chat.tool." + category.key()));
        badge.getStyleClass().addAll("tool-badge", "tool-badge-" + category.key());
        return badge;
    }

    private void update(Node node, WorkLogModel.Entry e) {
        if (e.kind() == WorkLogModel.Kind.TEXT && node instanceof MarkdownArea md) {
            if (!md.getMarkdown().equals(e.text() == null ? "" : e.text())) md.setMarkdown(e.text());
            return;
        }
        if (!(node instanceof TitledPane p)) return;
        if (e.kind() == WorkLogModel.Kind.REASONING) {
            setText(p, e.text());
        } else if (e.kind() == WorkLogModel.Kind.TOOL) {
            setHeader(p, e.tool());
            setToolContent(p, e.tool());
            if (e.tool().isError() && !p.getStyleClass().contains("error")) p.getStyleClass().add("error");
        }
    }

    /**
     * A tool call opened up: what it was asked to do, read out of its input (an edit as a coloured old/new diff, a
     * command as a prompt line, other arguments as fields), then its output; a diff output is coloured too.
     */
    private Node toolContent(Conversation.WorkItem w) {
        VBox box = shrinkable(new VBox(6));
        box.getStyleClass().add("tool-body");
        box.setPadding(new Insets(8));
        List<ToolCall.Part> parts = ToolCall.parts(w.name(), w.input());
        for (ToolCall.Part part : parts) box.getChildren().add(render(part));
        if (w.output() == null) {
            Label pending = shrinkable(new Label(bundle.getString("gui.chat.tool.running")));
            pending.getStyleClass().add("tool-pending");
            box.getChildren().add(pending);
        } else {
            if (!parts.isEmpty()) box.getChildren().add(section(bundle.getString("gui.chat.tool.output")));
            if (DiffPaneBuilder.looksLikeDiff(w.output())) {
                box.getChildren().add(DiffArea.block(w.output()));
            } else {
                TextArea out = textArea(w.output());
                if (w.isError()) out.getStyleClass().add("error");
                box.getChildren().add(out);
            }
        }
        ContextMenu menu = new ContextMenu();
        if (!parts.isEmpty()) {
            MenuItem copyCall = new MenuItem("Copy call");
            copyCall.setOnAction(ev -> MarkdownArea.copyToClipboard(ToolCall.copyText(parts)));
            menu.getItems().add(copyCall);
        }
        if (w.output() != null) {
            MenuItem copyOutput = new MenuItem("Copy output");
            copyOutput.setOnAction(ev -> MarkdownArea.copyToClipboard(w.output()));
            menu.getItems().add(copyOutput);
        }
        if (!menu.getItems().isEmpty()) {
            box.setOnContextMenuRequested(ev -> { menu.show(box, ev.getScreenX(), ev.getScreenY()); ev.consume(); });
        }
        box.setUserData(toolText(w));
        return box;
    }

    private Node render(ToolCall.Part part) {
        switch (part) {
            case ToolCall.Command c -> {
                Label command = shrinkable(new Label("$ " + c.text()));
                command.setWrapText(true);
                command.getStyleClass().add("tool-command");
                return command;
            }
            case ToolCall.Field f -> {
                Label key = new Label(f.key());
                key.getStyleClass().add("tool-field-key");
                Label value = shrinkable(new Label(f.value()));
                value.setWrapText(true);
                value.getStyleClass().add("tool-field-value");
                HBox.setHgrow(value, Priority.ALWAYS);
                HBox row = shrinkable(new HBox(8, key, value));
                row.getStyleClass().add("tool-field");
                return row;
            }
            case ToolCall.Text t -> {
                TextArea area = textArea(t.text());
                if (t.key() == null) return area;
                Label key = new Label(t.key());
                key.getStyleClass().add("tool-field-key");
                return shrinkable(new VBox(2, key, area));
            }
            case ToolCall.Changes c -> {
                return DiffArea.changes(c.path(), c.lines(), c.additions(), c.deletions(), c.replaceAll() ? bundle.getString("gui.chat.tool.replaceall") : null);
            }
            case ToolCall.Patch d -> {
                return DiffArea.block(d.text());
            }
        }
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("tool-section");
        return l;
    }

    private void setToolContent(TitledPane p, Conversation.WorkItem w) {
        String text = toolText(w);
        if (p.getContent() == null || !text.equals(p.getContent().getUserData())) p.setContent(toolContent(w));
    }

    private static void setText(TitledPane p, String text) {
        if (p.getContent() instanceof TextArea a && !a.getText().equals(text == null ? "" : text)) {
            a.setText(text == null ? "" : text);
            a.setPrefRowCount(rows(text));
        }
    }

    static String toolTitle(Conversation.WorkItem w) {
        return ToolSummary.title(w.name(), w.input()) + (w.output() == null ? "  …" : "");
    }

    /** Identifies what a pane shows, so streaming updates rebuild the content only when the call changed. */
    static String toolText(Conversation.WorkItem w) {
        return (w.input() == null ? "" : "$ " + w.input() + "\n") + (w.output() == null ? "" : w.output()) + (w.isError() ? "\n!" : "");
    }

    private static int rows(String text) {
        return Math.max(2, Math.min(14, (int) (text == null ? 1 : text.lines().count())));
    }

    private static TextArea textArea(String text) {
        TextArea a = shrinkable(new TextArea(text == null ? "" : text));
        a.setEditable(false);
        a.setWrapText(true);
        a.getStyleClass().add("tool-output");
        a.setPrefRowCount(rows(text));
        return a;
    }
}
