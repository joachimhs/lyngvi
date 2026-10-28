package no.syntakserror.lyngvi.gui.view;

import java.util.ResourceBundle;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import no.syntakserror.lyngvi.gui.viewmodel.Changelog;

/** «What's new»: app, sandbox and profile versions plus the changelog entries. */
public final class ChangelogDialog extends Dialog<Void> {

    public ChangelogDialog(Window owner, ResourceBundle b, String appVersion, String sandboxVersion, Changelog changelog) {
        initOwner(owner);
        setTitle(b.getString("gui.changelog.title"));
        setHeaderText(b.getString("gui.changelog.header"));
        getDialogPane().getButtonTypes().add(new ButtonType(b.getString("gui.button.close"), ButtonType.CLOSE.getButtonData()));
        Label versions = new Label(b.getString("gui.changelog.versions").replace("{0}", appVersion).replace("{1}", sandboxVersion == null ? "-" : sandboxVersion));
        versions.getStyleClass().add("section-help");
        VBox entries = new VBox(12, versions);
        for (Changelog.Entry e : changelog.entries()) {
            Label h = new Label(e.version() + (e.date() == null ? "" : "  ·  " + e.date()));
            h.getStyleClass().add("md-h3");
            MarkdownArea body = new MarkdownArea();
            body.setMarkdown(e.body());
            entries.getChildren().addAll(h, body);
        }
        ScrollPane sp = new ScrollPane(entries);
        sp.setFitToWidth(true);
        sp.setPrefSize(640, 480);
        getDialogPane().setContent(sp);
    }
}
