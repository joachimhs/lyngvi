package no.syntakserror.lyngvi.gui.view;

import java.util.Optional;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.util.StringConverter;

/**
 * The model dropdown «New conversation» and the composer share: the rows {@link ModelChoices} builds, with a
 * group heading drawn as a heading rather than as something to pick. Wired in one place so the two cannot drift.
 */
public final class ModelDropdown {

    private ModelDropdown() {}

    /** How a choice is shown, how a heading is drawn, and the one thing a heading must never become: the value. */
    public static void wire(ComboBox<ModelChoices.Choice> box) {
        box.setConverter(new StringConverter<>() {
            @Override public String toString(ModelChoices.Choice c) { return c == null ? "" : c.label(); }
            @Override public ModelChoices.Choice fromString(String s) { return null; }
        });
        box.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(ModelChoices.Choice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
                boolean heading = item != null && item.heading();
                // A heading names the group under it; the mouse goes straight past it.
                setDisable(heading);
                setMouseTransparent(heading);
                getStyleClass().remove("model-group");
                if (heading) getStyleClass().add("model-group");
            }
        });
        // The keyboard can still walk onto a heading, and Enter there would pick it: the value goes back instead.
        box.valueProperty().addListener((o, was, now) -> {
            if (now != null && now.heading()) box.setValue(was != null && !was.heading() ? was : null);
        });
    }

    /** The model the user has picked: empty when nothing is selected, and never a heading. */
    public static Optional<String> selected(ComboBox<ModelChoices.Choice> box) {
        return Optional.ofNullable(box.getValue()).filter(c -> !c.heading()).map(ModelChoices.Choice::id);
    }
}
