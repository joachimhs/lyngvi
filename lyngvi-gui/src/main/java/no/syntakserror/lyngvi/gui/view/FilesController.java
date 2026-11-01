package no.syntakserror.lyngvi.gui.view;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Predicate;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.stack.SessionVolume;
import no.syntakserror.lyngvi.gui.Services;

/**
 * The files pane: the session's working copies and output, read for display and edited by the user
 * (spec §2.2). They live in the session's volume, not on the host, so every read and write goes through
 * it — which also means every one of them is a call off the UI thread. Symlinks, binaries and very large
 * files are refused.
 *
 * <p>The tree opens closed: a working copy is thousands of files, and the ones worth reading are found by opening
 * the directory they are in or by typing in the filter, not by scrolling past everything else. A directory the user
 * opens stays open for the rest of the session, a refresh included.
 *
 * <p>A markdown file opens rendered rather than as source — a README or a changelog is written to be read — with
 * «Raw» a click away and the choice remembered for the rest of the session. The rendering is the transcript's
 * ({@link MarkdownArea}), and so is the text selection over it ({@link TranscriptSelection}), so a paragraph in a
 * preview can be marked and copied like one in a reply.
 *
 * <p>A source file in a language Lyngvi has rules for ({@link Languages}) opens coloured, in the same two-button
 * toggle: «Coloured» is {@link CodeView}, read-only because JavaFX's own text controls cannot colour part of their
 * content, and «Raw» is this editor, which is where writing and saving happen. A file no language claims opens in
 * the editor with no toggle at all — a guess at the language would be worse than plain text.
 */
public final class FilesController {

    static final long MAX_BYTES = 2_000_000;
    /** How deep into a working copy the tree goes; deeper than any source tree a human browses. */
    static final int MAX_DEPTH = 12;
    /** How many entries one root may contribute, so a node_modules cannot fill the pane. */
    static final int MAX_ENTRIES = 20_000;
    private static final List<String> IMAGES = List.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp");
    private static final List<String> MARKDOWN = List.of(".md", ".markdown");
    /**
     * How much markdown the preview lays out. A specification of a few hundred kilobytes is tens of thousands of
     * text nodes, which is seconds of layout and no use to anyone; every document in a repository is well under
     * this, and what is over it is read in Raw.
     */
    static final int MAX_PREVIEW_CHARS = 100_000;

    private final Services s;
    private final ResourceBundle b;
    /** The directories shown at the top of the tree: the working copies and {@code output}. */
    private final List<String> roots = new ArrayList<>();
    /** The directories the user has opened, by item value, so a rebuild does not close them again. */
    private final Set<String> opened = new HashSet<>();
    private Conversation session;
    /** The open file, as a path relative to the volume root. */
    private String currentFile;
    private final TextArea editor = new TextArea();
    /** The coloured view of the open file; built once, since it is a list that is refilled per file. */
    private CodeView code;
    /** The language the open file is coloured by, or null when no language claims it. */
    private Syntax language;
    private boolean dirty;
    /** Set once the user picks Raw or Preview, after which that choice is kept for every file opened. */
    private boolean viewModeChosen;

    @FXML private TextField fileFilter;
    @FXML private Button refreshFilesButton;
    @FXML private TreeView<String> fileTree;
    @FXML private Label fileNameLabel;
    @FXML private Label fileStatusLabel;
    @FXML private HBox viewModeBox;
    @FXML private RadioButton rawModeButton;
    @FXML private RadioButton previewModeButton;
    @FXML private Button reloadButton;
    @FXML private Button saveButton;
    @FXML private StackPane editorHolder;

    public FilesController(Services services) {
        this.s = services;
        this.b = services.bundle();
    }

    @FXML
    private void initialize() {
        code = new CodeView(b);
        editor.getStyleClass().add("code-editor");
        editor.textProperty().addListener((o, a, v) -> { if (currentFile != null) { dirty = true; fileStatusLabel.setText(b.getString("gui.files.modified")); saveButton.setDisable(false); } });
        editorHolder.getChildren().setAll(editor);
        fileTree.setShowRoot(false);
        fileTree.setRoot(new TreeItem<>());
        fileTree.setCellFactory(v -> new javafx.scene.control.TreeCell<>() {
            @Override protected void updateItem(String p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : roots.contains(p) ? rootLabel(p) : name(p));
            }
        });
        fileTree.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> {
            if (item != null && item.isLeaf() && !roots.contains(item.getValue())) openFile(item.getValue());
        });
        fileFilter.textProperty().addListener((o, a, v) -> rebuild());
        rawModeButton.setSelected(true);
        rawModeButton.setOnAction(e -> { viewModeChosen = true; render(); });
        previewModeButton.setOnAction(e -> { viewModeChosen = true; render(); });
        KeyCombination save = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);
        editor.setOnKeyPressed(e -> { if (save.match(e)) onSave(); });
        saveButton.setDisable(true);
        fileNameLabel.setText(b.getString("gui.files.none"));
    }

    static String name(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private String rootLabel(String root) {
        return "/work/" + root;
    }

    /** Shows a session's working copies and its output directory, both inside the session's volume. */
    public void showSession(Conversation c) {
        roots.clear();
        roots.addAll(c.repos());
        roots.add("output");
        opened.clear();  // another session: other working copies, and a tree that starts closed again
        session = c;
        currentFile = null;
        editor.clear();
        showViewModes(false);
        editorHolder.getChildren().setAll(editor);
        fileNameLabel.setText(b.getString("gui.files.none"));
        fileTree.setRoot(new TreeItem<>());
        rebuild();
    }

    /** Forgets the session being shown: the window has changed sandbox, and its files are no longer reachable. */
    public void clearSession() {
        session = null;
        roots.clear();
        opened.clear();
        currentFile = null;
        language = null;
        dirty = false;
        editor.clear();
        showViewModes(false);
        editorHolder.getChildren().setAll(editor);
        fileNameLabel.setText(b.getString("gui.files.none"));
        fileStatusLabel.setText("");
        saveButton.setDisable(true);
        fileTree.setRoot(new TreeItem<>());
    }

    @FXML private void onRefreshFiles() {
        rebuild();
    }

    private void failed(Throwable t) {
        fileStatusLabel.setText(String.valueOf(t.getMessage()));
        s.log().warn("files: " + t);
    }

    private void rebuild() {
        if (session == null) return;
        s.sessions().files(session, List.copyOf(roots), MAX_DEPTH, MAX_ENTRIES, this::showTree, this::failed);
    }

    private void showTree(Map<String, List<SessionVolume.Entry>> entries) {
        String filter = fileFilter.getText() == null ? "" : fileFilter.getText().strip().toLowerCase(Locale.ROOT);
        // with the filter on, what is left in the tree is hits only, and a closed directory would hide the answer
        TreeItem<String> root = tree(entries, filter, filter.isEmpty() ? opened::contains : p -> true);
        remember(root);
        fileTree.setRoot(root);
    }

    /**
     * The tree for a listing: a root per working copy, and under it the files laid out by their paths.
     * {@code open} says which directories start open — by their item value, the path with the root in front.
     */
    static TreeItem<String> tree(Map<String, List<SessionVolume.Entry>> entries, String filter, Predicate<String> open) {
        TreeItem<String> root = new TreeItem<>();
        entries.forEach((rootName, list) -> {
            if (list.isEmpty()) return;
            TreeItem<String> item = new TreeItem<>(rootName);
            item.setExpanded(open.test(rootName));
            build(item, rootName, list, filter, open);
            root.getChildren().add(item);
        });
        return root;
    }

    /**
     * Keeps track of which directories are open, so that a rebuild — a refresh, a filter typed and cleared again —
     * puts the tree back the way the user left it instead of closing what they opened.
     */
    private void remember(TreeItem<String> item) {
        for (TreeItem<String> child : item.getChildren()) {
            if (child.isLeaf()) continue;  // a file: nothing to open
            child.expandedProperty().addListener((o, a, isOpen) -> {
                if (isOpen) opened.add(child.getValue()); else opened.remove(child.getValue());
            });
            remember(child);
        }
    }

    /**
     * Lays the flat listing out as a tree. Directories are made as the files under them need them, so a
     * directory the filter emptied — or one that holds nothing but what the listing left out — is not shown.
     */
    private static void build(TreeItem<String> parent, String rootName, List<SessionVolume.Entry> entries, String filter, Predicate<String> open) {
        Map<String, TreeItem<String>> dirs = new LinkedHashMap<>();
        dirs.put("", parent);
        List<SessionVolume.Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(SessionVolume.Entry::path));
        for (SessionVolume.Entry e : sorted) {
            if (e.directory()) continue;
            if (!filter.isEmpty() && !name(e.path()).toLowerCase(Locale.ROOT).contains(filter)) continue;
            int slash = e.path().lastIndexOf('/');
            TreeItem<String> above = directory(dirs, rootName, slash < 0 ? "" : e.path().substring(0, slash), open);
            above.getChildren().add(new TreeItem<>(rootName + "/" + e.path()));
        }
    }

    /** The item for a directory, with the ones above it made first. */
    private static TreeItem<String> directory(Map<String, TreeItem<String>> dirs, String rootName, String path, Predicate<String> open) {
        TreeItem<String> known = dirs.get(path);
        if (known != null) return known;
        int slash = path.lastIndexOf('/');
        TreeItem<String> above = directory(dirs, rootName, slash < 0 ? "" : path.substring(0, slash), open);
        TreeItem<String> item = new TreeItem<>(rootName + "/" + path);
        item.setExpanded(open.test(item.getValue()));
        above.getChildren().add(item);
        dirs.put(path, item);
        return item;
    }

    static boolean isImage(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        return IMAGES.stream().anyMatch(n::endsWith);
    }

    /** A file the pane can render instead of showing as source. */
    static boolean isMarkdown(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        return MARKDOWN.stream().anyMatch(n::endsWith);
    }

    /** A path the pane may touch: inside one of the roots shown, and with nothing that climbs out of it. */
    static boolean insideRoots(String path, List<String> roots) {
        if (path == null || path.isBlank() || path.startsWith("/")) return false;
        for (String part : path.split("/")) {
            if (part.equals("..") || part.isBlank()) return false;
        }
        return roots.stream().anyMatch(r -> path.startsWith(r + "/"));
    }

    private void openFile(String path) {
        if (session == null) return;
        if (!insideRoots(path, roots)) {
            fileStatusLabel.setText(b.getString("gui.files.outside"));
            return;
        }
        currentFile = path;
        fileNameLabel.setText(name(path));
        dirty = false;
        language = null;  // decided once the bytes are in: an image or a binary is neither source nor markdown
        saveButton.setDisable(true);
        // hidden until the file turns out to be markdown, so an image or a binary does not keep the last file's toggle
        showViewModes(false);
        s.sessions().readFile(session, path, MAX_BYTES, bytes -> show(path, bytes), t -> {
            boolean tooLarge = t instanceof SandboxException e && "FILE_TOO_LARGE".equals(e.data().get("code"));
            fileStatusLabel.setText(tooLarge ? b.getString("gui.files.toolarge") : String.valueOf(t.getMessage()));
            editorHolder.getChildren().setAll(new Label(fileStatusLabel.getText()));
        });
    }

    private void show(String path, byte[] bytes) {
        if (!path.equals(currentFile)) return;  // another file was opened while this one was read
        if (isImage(path)) {
            ImageView iv = new ImageView(new Image(new ByteArrayInputStream(bytes)));
            iv.setPreserveRatio(true);
            iv.setFitWidth(600);
            editorHolder.getChildren().setAll(new ScrollPane(iv));
            fileStatusLabel.setText(bytes.length + " B");
            return;
        }
        for (int i = 0; i < Math.min(bytes.length, 8000); i++) {
            if (bytes[i] == 0) {
                fileStatusLabel.setText(b.getString("gui.files.binary"));
                editorHolder.getChildren().setAll(new Label(b.getString("gui.files.binary")));
                return;
            }
        }
        editor.setText(new String(bytes, StandardCharsets.UTF_8));
        dirty = false;
        saveButton.setDisable(true);
        fileStatusLabel.setText(bytes.length + " B");
        boolean md = isMarkdown(path);
        language = md ? null : s.languages().forPath(path).orElse(null);
        boolean rendered = md || language != null;
        // the same two buttons for both: what the right-hand one shows is what this file has to offer
        previewModeButton.setText(b.getString(md ? "gui.files.preview" : "gui.files.highlight"));
        showViewModes(rendered);
        // a document is opened to be read: rendered first, unless the user has said which view they want
        if (rendered && !viewModeChosen) previewModeButton.setSelected(true);
        render();
    }

    private void showViewModes(boolean show) {
        viewModeBox.setVisible(show);
        viewModeBox.setManaged(show);
    }

    /**
     * Puts the view the mode asks for in the pane. The text comes from the editor rather than from the file, so
     * what is shown coloured or rendered is what the user has in front of them, saved or not.
     */
    private void render() {
        if (currentFile == null) return;
        if (previewModeButton.isSelected() && isMarkdown(currentFile)) {
            editorHolder.getChildren().setAll(preview(editor.getText()));
        } else if (previewModeButton.isSelected() && language != null) {
            code.show(editor.getText(), language);
            editorHolder.getChildren().setAll(code);
        } else {
            editorHolder.getChildren().setAll(editor);
        }
    }

    /**
     * The rendered file, built the way the transcript is so that the text in it can be selected: the markdown in a
     * box, the selection's highlight on a mouse-transparent pane over it, both inside the scroll pane the selection
     * hit-tests and scrolls against. ⌘/Ctrl+C or the context menu copies what is marked; {@link MarkdownArea}'s own
     * «Copy» (no selection) copies the markdown as written.
     */
    private Node preview(String text) {
        Preview cut = previewOf(text);
        MarkdownArea md = new MarkdownArea();
        md.setMarkdown(cut.markdown());
        VBox box = new VBox(md);
        box.getStyleClass().add("md-preview");
        box.setMinWidth(0);
        if (cut.truncated()) {
            Label rest = new Label(s.messages().get("gui.files.preview.partial", (cut.markdown().length() + 500) / 1000 + " kB"));
            rest.getStyleClass().add("section-help");
            rest.setWrapText(true);
            box.getChildren().add(rest);
        }
        Pane highlight = new Pane();
        StackPane stack = new StackPane(box, highlight);
        stack.setAlignment(Pos.TOP_LEFT);
        ScrollPane sp = new ScrollPane(stack);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // the selection lives as long as the preview does: its filters are registered on these nodes and hold it
        TranscriptSelection sel = new TranscriptSelection(sp, box, highlight);
        box.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
            if (sel.isEmpty()) return;  // nothing marked: MarkdownArea's own «Copy», which takes the whole file
            ContextMenu menu = new ContextMenu();
            MenuItem copy = new MenuItem(b.getString("gui.files.copy.selection"));
            copy.setOnAction(a -> sel.copy());
            menu.getItems().add(copy);
            menu.show(box, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return sp;
    }

    /** What the preview shows of a file, and whether that is all of it. */
    record Preview(String markdown, boolean truncated) {}

    /**
     * The markdown the preview renders: all of it when the file is of a readable size, and otherwise as much as
     * {@link #MAX_PREVIEW_CHARS} allows, cut at a line break so no line is shown half.
     */
    static Preview previewOf(String text) {
        String t = text == null ? "" : text;
        if (t.length() <= MAX_PREVIEW_CHARS) return new Preview(t, false);
        int lineEnd = t.lastIndexOf('\n', MAX_PREVIEW_CHARS);
        return new Preview(t.substring(0, lineEnd < 0 ? MAX_PREVIEW_CHARS : lineEnd), true);
    }

    @FXML private void onReload() {
        if (currentFile != null) openFile(currentFile);
    }

    @FXML private void onSave() {
        if (currentFile == null || !dirty || session == null) return;
        String path = currentFile;
        byte[] content = editor.getText().getBytes(StandardCharsets.UTF_8);
        s.sessions().writeFile(session, path, content, () -> {
            dirty = false;
            saveButton.setDisable(true);
            fileStatusLabel.setText(b.getString("gui.files.saved"));
            s.log().info("saved " + path);
        }, this::failed);
    }
}
