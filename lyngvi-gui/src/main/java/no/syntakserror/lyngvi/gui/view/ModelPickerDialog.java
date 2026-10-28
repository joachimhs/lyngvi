package no.syntakserror.lyngvi.gui.view;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.gui.viewmodel.SessionViewModel;

/**
 * «Choose model»: the agent's whole catalogue, searchable, with a star on every row. The result is the chosen id.
 *
 * <p>Starring is not the same decision as switching, so the stars are reported through {@link #favourites()}
 * whether the dialog is accepted or cancelled — the caller saves them either way.
 */
public final class ModelPickerDialog extends Dialog<String> {

    /** How many models the list shows before the user starts typing; the rest are one search away. */
    private static final int PAGE = 400;

    private final ResourceBundle b;
    private final ListView<ModelChoices.Choice> list = new ListView<>();
    private final TextField search = new TextField();
    private final Label countLabel = new Label();
    private List<Model> catalogue;
    private List<String> favourites;

    public ModelPickerDialog(Window owner, ResourceBundle b, SessionViewModel sessions, String agent,
                             List<Model> catalogue, List<String> favourites, String selected) {
        this.b = b;
        this.catalogue = catalogue;
        this.favourites = favourites;
        initOwner(owner);
        setTitle(b.getString("gui.model.picker.title"));
        setHeaderText(b.getString("gui.model.picker.header"));
        ButtonType choose = new ButtonType(b.getString("gui.model.picker.choose"), ButtonType.OK.getButtonData());
        getDialogPane().getButtonTypes().addAll(choose, ButtonType.CANCEL);
        getDialogPane().setPrefSize(560, 520);

        search.setPromptText(b.getString("gui.model.picker.search"));
        search.textProperty().addListener((o, a, v) -> render());
        ProgressIndicator busy = new ProgressIndicator();
        busy.setPrefSize(16, 16);
        busy.setVisible(false);
        busy.setManaged(false);
        Button refresh = new Button();
        refresh.setGraphic(Icon.of("refresh"));
        refresh.getStyleClass().add("icon-button");
        refresh.setTooltip(new Tooltip(b.getString("gui.model.picker.refresh")));
        refresh.setOnAction(e -> {
            refresh.setDisable(true);
            busy.setVisible(true);
            busy.setManaged(true);
            sessions.models(agent, true, models -> {
                if (!models.isEmpty()) this.catalogue = models;
                refresh.setDisable(false);
                busy.setVisible(false);
                busy.setManaged(false);
                render();
            });
        });

        list.setCellFactory(v -> new ChoiceCell());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                setResult(list.getSelectionModel().getSelectedItem().id());
                close();
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        countLabel.getStyleClass().add("context-label");
        HBox top = new HBox(6, search, refresh, busy);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox content = new VBox(8, top, list, countLabel);
        content.setPadding(new Insets(10));
        getDialogPane().setContent(content);

        render();
        select(selected);
        setOnShown(e -> search.requestFocus());
        setResultConverter(bt -> {
            ModelChoices.Choice c = list.getSelectionModel().getSelectedItem();
            return bt == choose && c != null ? c.id() : null;
        });
    }

    /** The favourites as the user left them, whether the dialog was accepted or cancelled. */
    public List<String> favourites() {
        return favourites;
    }

    /** The catalogue as the dialog last had it, so a refresh here is not thrown away when it closes. */
    public List<Model> catalogue() {
        return catalogue;
    }

    private void render() {
        ModelChoices.Choice was = list.getSelectionModel().getSelectedItem();
        List<ModelChoices.Choice> matches = ModelChoices.filter(ModelChoices.order(catalogue, favourites), search.getText());
        list.getItems().setAll(matches.size() > PAGE ? matches.subList(0, PAGE) : matches);
        countLabel.setText(matches.size() > PAGE
                ? MessageFormat.format(b.getString("gui.model.picker.count.capped"), list.getItems().size(), matches.size())
                : MessageFormat.format(b.getString("gui.model.picker.count"), matches.size()));
        if (was != null) select(was.id());
    }

    private void select(String id) {
        if (id == null || id.isBlank()) return;
        list.getItems().stream().filter(c -> c.id().equals(id)).findFirst().ifPresent(c -> {
            list.getSelectionModel().select(c);
            list.scrollTo(c);
        });
    }

    private void toggle(String id) {
        favourites = ModelChoices.toggle(favourites, id);
        render();
        select(id);
    }

    /** A row: the star, the label, and the id underneath so an ambiguous label is never the only thing shown. */
    private final class ChoiceCell extends ListCell<ModelChoices.Choice> {

        private final Button star = new Button();
        private final Label name = new Label();
        private final Label id = new Label();
        private final HBox box;

        ChoiceCell() {
            star.getStyleClass().add("icon-button");
            star.setOnAction(e -> {
                if (getItem() != null) toggle(getItem().id());
            });
            id.getStyleClass().add("context-label");
            VBox text = new VBox(name, id);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box = new HBox(8, star, text, spacer);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(ModelChoices.Choice item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            star.setGraphic(Icon.of(item.favourite() ? "star-filled" : "star"));
            star.setTooltip(new Tooltip(b.getString(item.favourite() ? "gui.model.unstar" : "gui.model.star")));
            name.setText(item.label());
            id.setText(item.id());
            id.setVisible(!item.id().equals(item.label()));
            id.setManaged(!item.id().equals(item.label()));
            setGraphic(box);
        }
    }
}
