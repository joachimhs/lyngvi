package no.syntakserror.lyngvi.gui.view;

import java.util.List;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import no.syntakserror.lyngvi.gui.viewmodel.AgentsViewModel;

/** Runs an agent's login flow and shows its output; URLs become links and codes are highlighted (spec §9.3). */
public final class LoginDialog extends Dialog<Void> {

    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern CODE = Pattern.compile("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b");

    public LoginDialog(Window owner, ResourceBundle b, AgentsViewModel agents, String agentId, List<String> modes) {
        initOwner(owner);
        setTitle(b.getString("gui.agents.login.title") + " — " + agentId);
        setHeaderText(b.getString("gui.agents.login.header"));
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ComboBox<String> mode = new ComboBox<>(FXCollections.observableArrayList(modes));
        if (!modes.isEmpty()) mode.setValue(modes.getFirst());
        Button start = new Button(b.getString("gui.agents.login.start"));
        start.getStyleClass().add("primary");
        TextArea output = new TextArea();
        output.setEditable(false);
        output.setWrapText(true);
        output.setPrefRowCount(14);
        HBox links = new HBox(8);
        Label code = new Label();
        code.getStyleClass().add("login-code");
        Label status = new Label();
        javafx.animation.Timeline poll = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(2), ev ->
                agents.status(agentId, st -> {
                    if (st.loggedIn()) {
                        status.setText(b.getString("gui.agents.login.done") + (st.account() == null ? "" : " (" + st.account() + ")"));
                        start.setDisable(false);
                        stopPolling();
                    }
                })));
        poll.setCycleCount(javafx.animation.Animation.INDEFINITE);
        this.poll = poll;
        setOnHidden(ev -> stopPolling());
        start.setOnAction(e -> {
            start.setDisable(true);
            output.clear();
            links.getChildren().clear();
            code.setText("");
            status.setText(b.getString("gui.agents.login.running"));
            // TUI logins (Claude Code, OpenCode) run in a terminal window; the dialog polls the login state.
            agents.loginInTerminal(agentId, mode.getValue(), opened -> {
                if (opened) {
                    output.setText(b.getString("gui.agents.login.terminal"));
                    status.setText(b.getString("gui.agents.login.waiting"));
                    poll.play();
                } else {
                    streamLogin(agents, agentId, mode.getValue(), output, links, code, status, start, b);
                }
            });
        });
        VBox box = new VBox(8, new HBox(8, new Label(b.getString("gui.agents.login.mode")), mode, start, status), links, code, output);
        box.setPadding(new Insets(8));
        getDialogPane().setContent(box);
        getDialogPane().setPrefWidth(640);
    }

    private javafx.animation.Timeline poll;

    private void stopPolling() {
        if (poll != null) poll.stop();
    }

    /** Non-interactive flows: stream the lines, turn URLs into links and highlight codes. */
    private static void streamLogin(AgentsViewModel agents, String agentId, String mode, TextArea output, HBox links, Label code, Label status, Button start, ResourceBundle b) {
        {
            agents.login(agentId, mode, line -> {
                output.appendText(line + "\n");
                Matcher u = URL.matcher(line);
                while (u.find()) {
                    String url = u.group();
                    if (links.getChildren().stream().noneMatch(n -> n instanceof Hyperlink h && h.getText().equals(url))) {
                        Hyperlink h = new Hyperlink(url);
                        h.setOnAction(x -> MarkdownArea.browse(url));
                        links.getChildren().add(h);
                        MarkdownArea.browse(url);
                    }
                }
                Matcher c = CODE.matcher(line);
                if (c.find()) code.setText(b.getString("gui.agents.login.code") + " " + c.group());
            }, exit -> {
                start.setDisable(false);
                status.setText(exit == 0 ? b.getString("gui.agents.login.done") : b.getString("gui.agents.login.failed").replace("{0}", String.valueOf(exit)));
            });
        }
    }
}
