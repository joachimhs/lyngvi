package no.syntakserror.lyngvi.gui.view;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import no.syntakserror.lyngvi.core.contract.GatewayPluginInfo;
import no.syntakserror.lyngvi.core.contract.JsonOutput;

/** «Add system…»: id, label, hostname, aliases, plugin type, secret scheme, allowlist mode and plugin config as JSON (spec §8.8). */
public final class AddSystemDialog extends Dialog<AddSystemDialog.Request> {

    public record Request(String id, String plugin, String label, String hostname, List<String> aliases, String scheme, String mode, Map<String, Object> config) {}

    public AddSystemDialog(Window owner, ResourceBundle b, List<GatewayPluginInfo> plugins) {
        initOwner(owner);
        setTitle(b.getString("gui.gateway.add.title"));
        setHeaderText(b.getString("gui.gateway.add.header"));
        ButtonType add = new ButtonType(b.getString("gui.button.add"), ButtonType.OK.getButtonData());
        getDialogPane().getButtonTypes().addAll(add, ButtonType.CANCEL);
        TextField id = new TextField();
        id.setPromptText("docs");
        TextField label = new TextField();
        TextField hostname = new TextField();
        hostname.setPromptText("docs.gateway.local");
        TextField aliases = new TextField();
        aliases.setPromptText(b.getString("gui.gateway.add.aliases.prompt"));
        ComboBox<String> plugin = new ComboBox<>(FXCollections.observableArrayList(plugins.stream().map(GatewayPluginInfo::type).filter(t -> t != null).toList()));
        plugin.setValue("http");
        ComboBox<String> scheme = new ComboBox<>(FXCollections.observableArrayList("bearer", "basic", "header:X-Api-Key", "query:token"));
        scheme.setEditable(true);
        scheme.setValue("bearer");
        ComboBox<String> mode = new ComboBox<>(FXCollections.observableArrayList("path-prefix", "path-exact", "path-regex", "query:space", "none"));
        mode.setEditable(true);
        mode.setValue("path-prefix");
        TextArea config = new TextArea("{\n  \"restBase\": \"/api\",\n  \"pathAllow\": [\"^/.*\"]\n}");
        config.setPrefRowCount(6);
        plugin.valueProperty().addListener((o, a, v) -> {
            boolean http = "http".equals(v);
            mode.setDisable(!http);
            if (!http) config.setText("{}");
        });
        Label hint = new Label(b.getString("gui.gateway.add.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("section-help");
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        int r = 0;
        grid.addRow(r++, new Label(b.getString("gui.gateway.add.id")), id);
        grid.addRow(r++, new Label(b.getString("gui.gateway.add.label")), label);
        grid.addRow(r++, new Label(b.getString("gui.gateway.add.hostname")), hostname);
        grid.addRow(r++, new Label(b.getString("gui.gateway.add.aliases")), aliases);
        grid.addRow(r++, new Label(b.getString("gui.gateway.add.plugin")), plugin);
        grid.addRow(r++, new Label(b.getString("gui.gateway.add.scheme")), scheme);
        grid.addRow(r++, new Label(b.getString("gui.gateway.add.mode")), mode);
        grid.addRow(r++, new Label(b.getString("gui.gateway.add.config")), config);
        grid.add(hint, 0, r, 2, 1);
        getDialogPane().setContent(grid);
        setResultConverter(bt -> {
            if (bt != add) return null;
            Map<String, Object> cfg = config.getText().isBlank() ? Map.of() : JsonOutput.readObject(config.getText(), "config");
            List<String> al = Arrays.stream(aliases.getText().split("[,\\s]+")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            return new Request(id.getText().strip(), plugin.getValue(), label.getText().isBlank() ? null : label.getText().strip(), hostname.getText().strip(),
                    al, scheme.getValue(), "http".equals(plugin.getValue()) ? mode.getValue() : null, cfg);
        });
    }
}
