package no.syntakserror.lyngvi.gui.view;

import java.util.ResourceBundle;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import no.syntakserror.lyngvi.core.contract.GatewayInstanceInfo;
import no.syntakserror.lyngvi.gui.viewmodel.GatewayViewModel;

/** One gateway instance: secret (base URL + token), allowlist, and help text (spec §8.8, 1.1.2 GatewayPane). */
public final class GatewayPane extends BorderPane {

    private final GatewayViewModel vm;
    private final ResourceBundle bundle;
    private final String id;
    private final Label statusLabel = new Label();
    private final TextField urlField = new TextField();
    private final PasswordField tokenField = new PasswordField();
    private final ListView<String> allowlist = new ListView<>();
    private final TextField input = new TextField();
    private final Button removeButton;

    public GatewayPane(GatewayViewModel vm, ResourceBundle bundle, GatewayInstanceInfo info) {
        this.vm = vm;
        this.bundle = bundle;
        this.id = info.id();
        getStyleClass().add("gateway-pane");
        setPadding(new Insets(10));

        statusLabel.getStyleClass().add("status-label");
        urlField.setPromptText(bundle.getString("gui.gateway.url.prompt"));
        tokenField.setPromptText(bundle.getString("gui.gateway.token.prompt"));
        Button save = new Button(bundle.getString("gui.gateway.secret.save"));
        save.getStyleClass().add("primary");
        save.setOnAction(e -> {
            String url = urlField.getText().isBlank() ? null : urlField.getText().strip();
            String token = tokenField.getText().isBlank() ? null : tokenField.getText();
            vm.secretSet(id, url, token);
            tokenField.clear();
        });
        Button clear = new Button(bundle.getString("gui.gateway.secret.clear"));
        clear.setOnAction(e -> vm.secretClear(id));
        HBox secretRow = new HBox(6, urlField, tokenField, save, clear);
        HBox.setHgrow(urlField, Priority.ALWAYS);
        HBox.setHgrow(tokenField, Priority.ALWAYS);
        Label help = new Label(bundle.getString("gui.gateway.help"));
        help.setWrapText(true);
        help.getStyleClass().add("section-help");
        Label endpoint = new Label(bundle.getString("gui.gateway.endpoint") + "  http://" + info.hostname() + "   $" + info.urlEnvName());
        endpoint.getStyleClass().add("mono");
        VBox top = new VBox(6, statusLabel, endpoint, secretRow, help);
        setTop(top);

        allowlist.getStyleClass().add("allowlist");
        setCenter(allowlist);
        BorderPane.setMargin(allowlist, new Insets(8, 0, 8, 0));

        input.setPromptText(bundle.getString("gui.gateway.allow.prompt") + " (" + info.allowlistLabel() + ")");
        Button add = new Button(bundle.getString("gui.button.add"));
        add.setOnAction(e -> {
            if (!input.getText().isBlank()) {
                vm.allowAdd(id, input.getText().strip());
                input.clear();
                refresh(info);
            }
        });
        input.setOnAction(add.getOnAction());
        removeButton = new Button(bundle.getString("gui.button.remove.selected"));
        removeButton.setDisable(true);
        removeButton.setOnAction(e -> {
            String sel = allowlist.getSelectionModel().getSelectedItem();
            if (sel != null) {
                vm.allowRemove(id, sel);
                refresh(info);
            }
        });
        allowlist.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> removeButton.setDisable(b == null));
        Button refreshButton = new Button(bundle.getString("gui.button.refresh"));
        refreshButton.setOnAction(e -> refresh(info));
        HBox bottom = new HBox(6, input, add, removeButton, refreshButton);
        HBox.setHgrow(input, Priority.ALWAYS);
        setBottom(bottom);
        refresh(info);
    }

    public String instanceId() {
        return id;
    }

    /** Re-reads the allowlist and the readiness from the viewmodel. */
    public void refresh(GatewayInstanceInfo info) {
        statusLabel.setText(bundle.getString("gui.gateway.status") + " " + info.status()
                + "  ·  " + bundle.getString("gui.gateway.secret") + " " + (info.secretPresent() ? bundle.getString("gui.gateway.secret.set") : bundle.getString("gui.gateway.secret.missing")));
        statusLabel.getStyleClass().removeAll("status-done", "status-warn");
        statusLabel.getStyleClass().add("ready".equals(info.status()) ? "status-done" : "status-warn");
        try {
            allowlist.getItems().setAll(vm.allowlist(id));
        } catch (RuntimeException e) {
            allowlist.getItems().clear();
        }
        vm.secretStatus(id, s -> { if (s.baseUrl() != null) urlField.setText(s.baseUrl()); });
    }
}
