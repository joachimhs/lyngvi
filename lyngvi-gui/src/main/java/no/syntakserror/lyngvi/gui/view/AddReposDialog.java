package no.syntakserror.lyngvi.gui.view;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import no.syntakserror.lyngvi.core.api.AddRepoOptions;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.RepoEntry;
import no.syntakserror.lyngvi.core.i18n.Messages;

/**
 * «Add repository…» (spec §5.2.1): the registered repositories a conversation does not have yet, ticked one
 * by one, with the history choice «New conversation» offers. Only repositories that can still be added are
 * listed, so the list is never empty; «Add» stays disabled until at least one of them is ticked.
 */
public final class AddReposDialog extends Dialog<AddRepoOptions> {

    private static final double LABEL_WIDTH = 110;

    public AddReposDialog(Window owner, ResourceBundle b, Messages m, String conversationTitle, List<RepoEntry> available) {
        initOwner(owner);
        setTitle(b.getString("gui.chat.addrepo"));
        setHeaderText(m.get("gui.chat.addrepo.header", conversationTitle));
        ButtonType add = new ButtonType(b.getString("gui.chat.addrepo.add"), ButtonType.OK.getButtonData());
        getDialogPane().getButtonTypes().addAll(add, ButtonType.CANCEL);

        List<CheckBox> boxes = new ArrayList<>();
        GridPane repoBox = new GridPane();
        repoBox.setHgap(12);
        repoBox.setVgap(4);
        Path home = Path.of(System.getProperty("user.home", ""));
        for (RepoEntry r : available) {
            CheckBox cb = new CheckBox(r.name());
            Label path = new Label(NewSessionDialog.abbreviate(r.path(), home));
            path.getStyleClass().add("repo-path");
            path.setTooltip(new Tooltip(r.path().toString()));
            path.setOnMouseClicked(e -> cb.fire());
            repoBox.addRow(boxes.size(), cb, path);
            boxes.add(cb);
        }

        ComboBox<History> history = new ComboBox<>(FXCollections.observableArrayList(History.values()));
        history.setValue(History.SHALLOW);
        TextField depth = new TextField();
        depth.setPromptText(b.getString("gui.session.new.depth.prompt"));
        depth.setPrefColumnCount(5);
        HBox historyRow = new HBox(6, history, depth);
        historyRow.setAlignment(Pos.CENTER_LEFT);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(LABEL_WIDTH);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);
        Label reposLabel = new Label(b.getString("gui.session.new.repos"));
        GridPane.setValignment(reposLabel, VPos.TOP);
        GridPane.setMargin(reposLabel, new Insets(3, 0, 0, 0));
        grid.addRow(0, reposLabel, repoBox);
        grid.addRow(1, new Label(b.getString("gui.session.new.history")), historyRow);

        Label note = new Label(b.getString("gui.chat.addrepo.note"));
        note.setWrapText(true);
        note.getStyleClass().add("repo-path");
        note.setMaxWidth(440);

        VBox content = new VBox(14, grid, note);
        content.setPadding(new Insets(4, 0, 0, 0));
        content.setMinWidth(460);
        getDialogPane().setContent(content);

        // Nothing ticked is not an «add» anyone means; the button says so rather than the error afterwards.
        Node addButton = getDialogPane().lookupButton(add);
        addButton.setDisable(true);
        boxes.forEach(cb -> cb.selectedProperty().addListener(
                (o, a, v) -> addButton.setDisable(boxes.stream().noneMatch(CheckBox::isSelected))));

        setResultConverter(bt -> {
            if (bt != add) return null;
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < available.size(); i++) if (boxes.get(i).isSelected()) selected.add(available.get(i).name());
            if (selected.isEmpty()) return null;
            Optional<Integer> d = Optional.empty();
            try {
                if (!depth.getText().isBlank()) d = Optional.of(Integer.parseInt(depth.getText().strip()));
            } catch (NumberFormatException ignored) {
                // an unparsable depth falls back to the profile default, as in «New conversation»
            }
            return new AddRepoOptions(selected, Optional.ofNullable(history.getValue()), d);
        });
    }
}
