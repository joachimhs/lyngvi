package no.syntakserror.lyngvi.gui.view;

import java.util.ResourceBundle;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import no.syntakserror.lyngvi.core.contract.UpgradeReport;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.gui.Services;
import no.syntakserror.lyngvi.gui.viewmodel.Errors;

/**
 * What an upgrade left behind, and the build it asks for. The refreshed files reach a conversation only once the
 * images are built again, so the dialog that says so does it as well: «Rebuild now» starts the build here, with its
 * output and its progress in the dialog, rather than sending the user off to find the button under «Sandbox».
 *
 * <p>The build belongs to the stack rather than to the dialog, so the dialog may be closed while it runs: it goes
 * on, and its output reaches the console either way. A build that fails leaves the button behind as «Try again».
 */
public final class UpgradeDialog extends Dialog<Void> {

    /** How much of the output the dialog keeps. A build is mostly downloads; what matters is the tail. */
    private static final int TAIL = 40_000;

    private final ResourceBundle b;
    private final Messages m;
    private final Services s;
    private final Button rebuild = new Button();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label status = new Label();
    private final TextArea output = new TextArea();

    public UpgradeDialog(Window owner, ResourceBundle b, Messages m, Services s, UpgradeReport report) {
        this.b = b;
        this.m = m;
        this.s = s;
        initOwner(owner);
        setTitle(b.getString("gui.upgrade.title"));
        setHeaderText(b.getString("gui.upgrade.title"));
        setResizable(true);
        getDialogPane().getButtonTypes().add(new ButtonType(b.getString("gui.button.close"), ButtonType.CLOSE.getButtonData()));
        boolean refreshed = !report.updated().isEmpty();
        rebuild.setText(b.getString("gui.upgrade.rebuild"));
        rebuild.setGraphic(Icon.of("hammer"));
        rebuild.getStyleClass().add("primary");
        rebuild.setOnAction(e -> start());
        spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        spinner.setPrefSize(16, 16);
        spinner.setMinSize(16, 16);
        wrap(status);
        output.setEditable(false);
        output.getStyleClass().add("wizard-output");
        output.setPrefRowCount(14);
        VBox.setVgrow(output, Priority.ALWAYS);
        HBox action = new HBox(8, rebuild, spinner);
        action.setAlignment(Pos.CENTER_LEFT);
        action.setMinHeight(28);
        // Nothing was refreshed: there is nothing to build again either, so the dialog is the message alone.
        showing(action, refreshed);
        showing(spinner, false);
        showing(status, false);
        showing(output, false);
        Label text = new Label(refreshed ? m.get("gui.upgrade.done", report.to()) : m.get("gui.upgrade.uptodate"));
        wrap(text);
        VBox box = new VBox(10, text, action, status, output);
        box.setPrefWidth(560);
        getDialogPane().setContent(box);
    }

    private void start() {
        output.clear();
        rebuild.setDisable(true);
        showing(spinner, true);
        showing(output, true);
        say(m.get("gui.upgrade.rebuild.running"), null);
        s.stack().build(false, this::append, this::done, this::failed);
        // The log takes room the message alone did not need, so the dialog grows to it rather than squeezing it in.
        if (getDialogPane().getScene() != null) getDialogPane().getScene().getWindow().sizeToScene();
    }

    private void done() {
        showing(spinner, false);
        say(m.get("gui.upgrade.rebuild.done"), "status-done");
    }

    /** A build that did not get there: the console keeps the detail, the dialog says so and offers the build again. */
    private void failed(Throwable t) {
        Errors.UiError e = Errors.map(t, m);
        s.log().error(e.message(), null);
        if (e.details() != null && !e.details().isBlank()) s.log().error(e.details(), null);
        showing(spinner, false);
        rebuild.setDisable(false);
        rebuild.setText(b.getString("gui.wizard.retry"));
        say(e.message(), "status-error");
    }

    private void say(String text, String styleClass) {
        status.getStyleClass().removeAll("status-done", "status-error");
        if (styleClass != null) status.getStyleClass().add(styleClass);
        status.setText(text);
        showing(status, true);
    }

    private void append(String line) {
        output.appendText(line + "\n");
        int over = output.getLength() - TAIL;
        if (over <= 0) return;
        int lineEnd = output.getText(0, Math.min(over + 500, output.getLength())).indexOf('\n', over);
        output.deleteText(0, lineEnd < 0 ? over : lineEnd + 1);
    }

    /**
     * A label that keeps its lines. The dialog is sized to its scene when the log appears, and a wrapping label is
     * measured at one line's height unless it has a width of its own to wrap within — the text is cut short then.
     */
    private static void wrap(Label label) {
        label.setWrapText(true);
        label.setPrefWidth(520);
        label.setMinHeight(Region.USE_PREF_SIZE);
    }

    private static void showing(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
