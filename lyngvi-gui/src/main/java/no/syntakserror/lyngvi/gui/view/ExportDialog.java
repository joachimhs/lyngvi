package no.syntakserror.lyngvi.gui.view;

import java.io.File;
import java.nio.file.Path;
import java.util.ResourceBundle;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import no.syntakserror.lyngvi.core.api.ExportOptions;

/**
 * «Export ZIP»: where the archive goes, and what goes in it (spec §5.9).
 *
 * <p>The dialog exists because the two choices it offers are the ones that decide whether the archive is any
 * use. The git history is what makes it a way back — with it, the extracted copy still knows what the agent
 * did and what it started from — so it is on by default; build output is on nobody's list and can be larger
 * than everything else put together, so it is off until asked for.
 */
public final class ExportDialog extends Dialog<ExportOptions> {

    public ExportDialog(Window owner, ResourceBundle b, Path directory, String fileName) {
        initOwner(owner);
        setTitle(b.getString("gui.export.title"));
        setHeaderText(b.getString("gui.export.header"));
        ButtonType export = new ButtonType(b.getString("gui.export.button"), ButtonType.OK.getButtonData());
        getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);

        Label explain = new Label(b.getString("gui.export.explain"));
        explain.setWrapText(true);
        explain.setMaxWidth(460);
        explain.getStyleClass().add("section-help");
        TextField file = new TextField(directory.resolve(fileName).toString());
        file.setPrefColumnCount(40);
        HBox.setHgrow(file, Priority.ALWAYS);
        Button browse = new Button(b.getString("gui.button.browse"));
        browse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(b.getString("gui.export.choose"));
            fc.setInitialFileName(fileName);
            File dir = directory.toFile();
            if (dir.isDirectory()) fc.setInitialDirectory(dir);
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(b.getString("gui.export.filter"), "*.zip"));
            File chosen = fc.showSaveDialog(owner);
            if (chosen != null) file.setText(chosen.getAbsolutePath());
        });
        CheckBox git = new CheckBox(b.getString("gui.export.git"));
        git.setSelected(true);
        CheckBox build = new CheckBox(b.getString("gui.export.build"));

        VBox box = new VBox(10, explain, new VBox(4, new Label(b.getString("gui.export.file")), new HBox(6, file, browse)), git, build);
        box.setPadding(new Insets(10));
        getDialogPane().setContent(box);
        getDialogPane().lookupButton(export).disableProperty().bind(file.textProperty().isEmpty());
        setResultConverter(bt -> bt != export || file.getText().isBlank() ? null
                : new ExportOptions(Path.of(file.getText().strip()), git.isSelected(), build.isSelected()));
    }
}
