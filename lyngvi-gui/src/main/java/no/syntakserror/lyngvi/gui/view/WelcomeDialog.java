package no.syntakserror.lyngvi.gui.view;

import java.util.ResourceBundle;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** «Get started»: the checklist (build, start, log in, new conversation) with a button per step. */
public final class WelcomeDialog extends Dialog<Boolean> {

    public WelcomeDialog(Window owner, ResourceBundle b, Runnable build, Runnable up, Runnable agents, Runnable newSession) {
        initOwner(owner);
        setTitle(b.getString("gui.welcome.title"));
        setHeaderText(b.getString("gui.welcome.header"));
        ButtonType close = new ButtonType(b.getString("gui.button.close"), ButtonType.CLOSE.getButtonData());
        getDialogPane().getButtonTypes().add(close);
        Label intro = new Label(b.getString("gui.welcome.intro"));
        intro.setWrapText(true);
        intro.setMaxWidth(560);
        VBox steps = new VBox(6);
        steps.getChildren().addAll(
                step(b.getString("gui.welcome.step.build"), b.getString("gui.welcome.step.build.button"), build),
                step(b.getString("gui.welcome.step.up"), b.getString("gui.welcome.step.up.button"), up),
                step(b.getString("gui.welcome.step.login"), b.getString("gui.welcome.step.login.button"), agents),
                step(b.getString("gui.welcome.step.session"), b.getString("gui.welcome.step.session.button"), newSession));
        Label examplesTitle = new Label(b.getString("gui.welcome.examples"));
        examplesTitle.getStyleClass().add("md-h3");
        Label examples = new Label(b.getString("gui.welcome.examples.text"));
        examples.setWrapText(true);
        CheckBox dontShow = new CheckBox(b.getString("gui.welcome.dontshow"));
        VBox box = new VBox(10, intro, steps, examplesTitle, examples, dontShow);
        box.setPadding(new Insets(8));
        getDialogPane().setContent(box);
        setResultConverter(bt -> dontShow.isSelected());
    }

    private static HBox step(String text, String buttonText, Runnable action) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(420);
        Button btn = new Button(buttonText);
        btn.setOnAction(e -> action.run());
        HBox row = new HBox(10, btn, l);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }
}
