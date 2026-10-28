package no.syntakserror.lyngvi.gui.view;

import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import no.syntakserror.lyngvi.gui.viewmodel.AppLog;
import no.syntakserror.lyngvi.gui.viewmodel.UiThread;

/** The console: the application log as a list with severity styles and copy actions. */
public final class ConsoleView extends ListView<AppLog.Entry> {

    private final ObservableList<AppLog.Entry> all = FXCollections.observableArrayList();
    private final FilteredList<AppLog.Entry> filtered = new FilteredList<>(all, e -> true);
    private boolean onlyImportant;

    public ConsoleView(java.util.ResourceBundle bundle) {
        getStyleClass().add("console");
        setItems(filtered);
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(AppLog.Entry e, boolean empty) {
                super.updateItem(e, empty);
                getStyleClass().removeAll("console-warning", "console-error");
                if (empty || e == null) {
                    setText(null);
                    return;
                }
                setText(e.line());
                if (e.level() == AppLog.Level.WARN) getStyleClass().add("console-warning");
                if (e.level() == AppLog.Level.ERROR) getStyleClass().add("console-error");
            }
        });
        ContextMenu menu = new ContextMenu();
        MenuItem copySel = new MenuItem(bundle.getString("gui.console.copy.selected"));
        copySel.setOnAction(e -> copy(getSelectionModel().getSelectedItems()));
        MenuItem copyAll = new MenuItem(bundle.getString("gui.console.copy.all"));
        copyAll.setOnAction(e -> copy(all));
        MenuItem copyImportant = new MenuItem(bundle.getString("gui.console.copy.important"));
        copyImportant.setOnAction(e -> copy(all.filtered(x -> x.level() != AppLog.Level.INFO)));
        MenuItem clear = new MenuItem(bundle.getString("gui.console.clear"));
        clear.setOnAction(e -> all.clear());
        menu.getItems().addAll(copySel, copyAll, copyImportant, clear);
        setContextMenu(menu);
        KeyCombination copyKey = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        setOnKeyPressed(e -> {
            if (copyKey.match(e)) copy(getSelectionModel().getSelectedItems().isEmpty() ? all : getSelectionModel().getSelectedItems());
        });
    }

    /** Shows existing entries and follows new ones (marshalled to the UI thread). */
    public void bind(AppLog log, UiThread ui) {
        all.setAll(log.entries());
        log.addListener(e -> ui.run(() -> {
            all.add(e);
            if (all.size() > 5000) all.remove(0, 500);
            scrollTo(filtered.size() - 1);
        }));
    }

    public void setOnlyImportant(boolean only) {
        this.onlyImportant = only;
        filtered.setPredicate(e -> !onlyImportant || e.level() != AppLog.Level.INFO);
    }

    public boolean isOnlyImportant() {
        return onlyImportant;
    }

    private static void copy(List<AppLog.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (AppLog.Entry e : entries) sb.append(e.line()).append('\n');
        MarkdownArea.copyToClipboard(sb.toString());
    }
}
