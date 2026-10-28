package no.syntakserror.lyngvi.gui.view;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.UnaryOperator;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * The last word before a delivery that would leave files behind: every such file by name, with the reason the
 * policy gave, and the held-back ones with a tick box, so the decision is made here instead of sending the
 * developer back to the buckets. A rejected file gets the same row with its box ticked off and disabled — it is
 * named for the same reason, but no tick can bring it out.
 *
 * <p>Answers with the ticks as they stand when «Deliver» is pressed, and with nothing at all when the dialog is
 * cancelled; the ticks are the caller's to keep, so the buckets behind show the same state afterwards.
 */
final class ExcludedDialog extends Dialog<Set<String>> {

    /** How tall the list of files grows before it scrolls rather than pushing the buttons off the screen. */
    private static final double LIST_HEIGHT = 320;

    private final ResourceBundle b;
    private final int total;
    private final int held;
    private final int rejected;
    private final Set<String> ticked;
    private final Label summary = new Label();
    private final Button deliver;

    /**
     * @param held     every held-back file, ticked or not, in review order
     * @param included the keys ticked so far; the dialog starts from them and never touches the caller's set
     */
    ExcludedDialog(Window owner, ResourceBundle b, DeliverFiles.Excluded ex, List<DeliverFiles.Entry> held,
                   Set<String> included, UnaryOperator<String> reasonText) {
        this.b = b;
        this.total = ex.total();
        this.held = held.size();
        this.rejected = ex.rejected().size();
        this.ticked = new LinkedHashSet<>(included);
        // Only a file in this list can be ticked off it, so the count below always matches what is on screen.
        this.ticked.retainAll(held.stream().map(DeliverFiles.Entry::key).collect(java.util.stream.Collectors.toSet()));
        initOwner(owner);
        setTitle(b.getString("gui.deliver.excluded.confirm.title"));
        setHeaderText(b.getString("gui.deliver.excluded.confirm.title"));
        setResizable(true);

        summary.setWrapText(true);
        summary.getStyleClass().add("deliver-warning-title");
        VBox warning = new VBox(summary);
        warning.getStyleClass().add("deliver-warning");
        // The help says what can be done here, which depends on what is in the list: ticks, rejections, or both.
        Label help = new Label(b.getString(held.isEmpty() ? "gui.deliver.excluded.confirm.help.rejected"
                : ex.rejected().isEmpty() ? "gui.deliver.excluded.confirm.help.held"
                : "gui.deliver.excluded.confirm.help"));
        help.setWrapText(true);
        help.getStyleClass().add("section-help");

        VBox files = new VBox(6);
        if (!held.isEmpty()) {
            files.getChildren().add(section("gui.deliver.bucket.hold", held.size()));
            for (DeliverFiles.Entry e : held) files.getChildren().add(row(e, reasonText, true));
        }
        if (!ex.rejected().isEmpty()) {
            files.getChildren().add(section("gui.deliver.bucket.reject", ex.rejected().size()));
            for (DeliverFiles.Entry e : ex.rejected()) files.getChildren().add(row(e, reasonText, false));
        }
        ScrollPane scroll = new ScrollPane(files);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(LIST_HEIGHT);

        VBox content = new VBox(10, warning, help, scroll);
        content.setPadding(new Insets(4));
        content.setPrefWidth(560);
        getDialogPane().setContent(content);
        ButtonType apply = new ButtonType(b.getString("gui.deliver.excluded.confirm.apply"), ButtonType.OK.getButtonData());
        ButtonType cancel = new ButtonType(b.getString("gui.button.cancel"), ButtonType.CANCEL.getButtonData());
        getDialogPane().getButtonTypes().setAll(apply, cancel);
        deliver = (Button) getDialogPane().lookupButton(apply);
        setResultConverter(bt -> bt == apply ? ticked : null);
        update();
    }

    /** «Held back (tick to include) (3)» — the bucket's own title, so this list and the buckets name the same thing. */
    private Label section(String key, int n) {
        Label label = new Label(b.getString(key) + " (" + n + ")");
        label.getStyleClass().add("section-title");
        label.setPadding(new Insets(6, 0, 0, 0));
        return label;
    }

    /** One file: the tick that takes it along, or a disabled one where nothing can. */
    private CheckBox row(DeliverFiles.Entry entry, UnaryOperator<String> reasonText, boolean canInclude) {
        CheckBox box = new CheckBox(DeliverFiles.line(entry, reasonText));
        box.setWrapText(true);
        box.setTooltip(new Tooltip(entry.key()));
        if (!canInclude) {
            box.setDisable(true);
            return box;
        }
        box.setSelected(ticked.contains(entry.key()));
        box.setOnAction(e -> {
            if (box.isSelected()) ticked.add(entry.key());
            else ticked.remove(entry.key());
            update();
        });
        return box;
    }

    /** The count falls as files are ticked, and the button says what pressing it would deliver now. */
    private void update() {
        int left = held - ticked.size() + rejected;
        summary.setText(left == 0 ? b.getString("gui.deliver.excluded.confirm.all")
                : "⚠ " + b.getString("gui.deliver.excluded.summary")
                        .replace("{0}", String.valueOf(left)).replace("{1}", String.valueOf(total)));
        deliver.setText(b.getString(ticked.isEmpty() ? "gui.deliver.excluded.confirm.apply"
                : "gui.deliver.excluded.confirm.apply.ticked"));
        // The button bar hands every button one width and remembers it, so a longer label would be cut short.
        deliver.setPrefWidth(Region.USE_COMPUTED_SIZE);
    }
}
