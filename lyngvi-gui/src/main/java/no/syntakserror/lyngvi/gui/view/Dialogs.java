package no.syntakserror.lyngvi.gui.view;

import java.util.Optional;
import java.util.ResourceBundle;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import no.syntakserror.lyngvi.gui.viewmodel.Errors;

/** Modal dialogs: info, error with details, confirmation, text input. All text comes from the bundle or the core. */
public final class Dialogs {

    private final ResourceBundle bundle;
    private final Window owner;

    public Dialogs(ResourceBundle bundle, Window owner) {
        this.bundle = bundle;
        this.owner = owner;
    }

    public void info(String title, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(title);
        a.getDialogPane().setContent(contentText(text));
        a.showAndWait();
    }

    public void error(Errors.UiError e) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.initOwner(owner);
        a.setTitle(e.title());
        a.setHeaderText(e.title());
        a.getDialogPane().setContent(contentText(e.message()));
        if (e.details() != null && !e.details().isBlank()) {
            TextArea details = new TextArea(e.details());
            details.setEditable(false);
            details.setWrapText(true);
            details.setPrefRowCount(10);
            VBox box = new VBox(new Label(bundle.getString("gui.dialog.details")), details);
            a.getDialogPane().setExpandableContent(box);
        }
        a.showAndWait();
    }

    public boolean confirm(String title, String text, String okLabel) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(title);
        a.getDialogPane().setContent(contentText(text));
        ButtonType ok = new ButtonType(okLabel, ButtonType.OK.getButtonData());
        ButtonType cancel = new ButtonType(bundle.getString("gui.button.cancel"), ButtonType.CANCEL.getButtonData());
        a.getButtonTypes().setAll(ok, cancel);
        return a.showAndWait().filter(b -> b == ok).isPresent();
    }

    public Optional<String> textInput(String title, String header, String initial) {
        TextInputDialog d = new TextInputDialog(initial == null ? "" : initial);
        d.initOwner(owner);
        d.setTitle(title);
        d.setHeaderText(header);
        return d.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
    }

    static Label contentText(String text) {
        Label l = new Label(text == null ? "" : text);
        l.setWrapText(true);
        l.setMaxWidth(520);
        return l;
    }
}
