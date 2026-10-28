package no.syntakserror.lyngvi.gui.view;

import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import no.syntakserror.lyngvi.core.ask.AgentUsage;
import no.syntakserror.lyngvi.core.ask.UsageLimit;
import no.syntakserror.lyngvi.core.contract.AgentUsageReport;
import no.syntakserror.lyngvi.core.i18n.Messages;

/**
 * The usage view for one agent: the quotas its account reports (plan, bars, reset times) and
 * the tokens and cost this sandbox has spent on it, per model and per conversation.
 * All text comes from the bundle; all numbers from {@link UsageFormatter}.
 */
public final class UsagePane extends VBox {

    private final Messages m;

    public UsagePane(Messages messages) {
        this.m = messages;
        setSpacing(10);
    }

    /** Replaces the contents with {@code report}; {@code null} shows the "pick an agent" hint. */
    public void show(AgentUsageReport report) {
        getChildren().clear();
        if (report == null) {
            getChildren().add(help(m.get("gui.usage.empty")));
            return;
        }
        getChildren().addAll(accountCard(report.account()), localCard(report));
    }

    // ---- the agent's own account ----

    private VBox accountCard(AgentUsage account) {
        VBox card = card(m.get("gui.usage.account.title"));
        if (!account.supported()) {
            card.getChildren().add(help(account.note() == null ? m.get("gui.usage.account.none")
                    : m.get("gui.usage.account.none") + " " + account.note()));
            return card;
        }
        if (account.plan() != null) card.getChildren().add(new Label(m.get("gui.usage.plan", account.plan())));
        if (account.note() != null) card.getChildren().add(help(account.note()));
        if (account.resetAt() != null) card.getChildren().add(help(m.get("gui.usage.resets", UsageFormatter.moment(account.resetAt()))));
        if (account.limits().isEmpty() && account.extra().isEmpty()) card.getChildren().add(help(m.get("gui.usage.account.nolimits")));
        for (UsageLimit limit : account.limits()) card.getChildren().add(limitRow(limit));
        account.extra().forEach((key, value) -> card.getChildren().add(new Label(key + ": " + value)));
        return card;
    }

    /** One quota: name and numbers on a line, the bar under it, coloured from {@code 75%} and {@code 90%}. */
    private VBox limitRow(UsageLimit limit) {
        Label name = new Label(limit.label());
        Label value = new Label(UsageFormatter.limit(limit));
        value.getStyleClass().add("section-help");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox line = new HBox(6, name, spacer, value);
        line.setAlignment(Pos.CENTER_LEFT);

        VBox row = new VBox(3, line);
        limit.ratio().ifPresent(ratio -> {
            ProgressBar bar = new ProgressBar(ratio);
            bar.setMaxWidth(Double.MAX_VALUE);
            String style = UsageFormatter.levelStyle(limit.level());
            if (!style.isEmpty()) bar.getStyleClass().add(style);
            row.getChildren().add(bar);
        });
        if (limit.resetAt() != null) row.getChildren().add(help(m.get("gui.usage.resets", UsageFormatter.moment(limit.resetAt()))));
        return row;
    }

    // ---- what this sandbox has spent ----

    private VBox localCard(AgentUsageReport report) {
        VBox card = card(m.get("gui.usage.local.title", report.label()));
        card.getChildren().add(help(m.get("gui.usage.local.help")));
        card.getChildren().add(new Label(m.get("gui.usage.turns", report.local().turns()) + " · " + UsageFormatter.totals(report.local())));
        if (report.local().turns() == 0) {
            card.getChildren().add(help(m.get("gui.usage.local.none")));
            return card;
        }
        card.getChildren().addAll(new Label(m.get("gui.usage.permodel")), modelTable(report), new Label(m.get("gui.usage.persession")), sessionTable(report));
        return card;
    }

    private TableView<AgentUsageReport.Group> modelTable(AgentUsageReport report) {
        TableView<AgentUsageReport.Group> table = new TableView<>();
        column(table, m.get("gui.usage.col.model"), 160, g -> g.key().isEmpty() ? m.get("gui.usage.nomodel") : g.key());
        column(table, m.get("gui.usage.col.turns"), 70, g -> String.valueOf(g.totals().turns()));
        column(table, m.get("gui.usage.col.tokens"), 90, g -> UsageFormatter.tokens(g.totals().tokens()));
        column(table, m.get("gui.usage.col.cost"), 90, g -> UsageFormatter.cost(g.totals().costUsd()));
        table.getItems().setAll(report.byModel());
        table.setPrefHeight(rowHeight(report.byModel().size()));
        return table;
    }

    private TableView<AgentUsageReport.Session> sessionTable(AgentUsageReport report) {
        TableView<AgentUsageReport.Session> table = new TableView<>();
        column(table, m.get("gui.usage.col.session"), 220, AgentUsageReport.Session::title);
        column(table, m.get("gui.usage.col.model"), 110, s -> s.model().isEmpty() ? m.get("gui.usage.nomodel") : s.model());
        column(table, m.get("gui.usage.col.turns"), 70, s -> String.valueOf(s.totals().turns()));
        column(table, m.get("gui.usage.col.tokens"), 90, s -> UsageFormatter.tokens(s.totals().tokens()));
        column(table, m.get("gui.usage.col.cost"), 90, s -> UsageFormatter.cost(s.totals().costUsd()));
        column(table, m.get("gui.usage.col.context"), 150, s -> UsageFormatter.context(s.contextUsed(), s.contextMax()));
        column(table, m.get("gui.usage.col.lastactive"), 130, s -> UsageFormatter.moment(s.lastActiveAt()));
        table.getItems().setAll(report.sessions());
        table.setPrefHeight(rowHeight(report.sessions().size()));
        return table;
    }

    private static <T> void column(TableView<T> table, String title, double width, Function<T, String> value) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        table.getColumns().add(col);
    }

    /** Enough room for the rows plus the header, capped so both tables stay on screen. */
    private static double rowHeight(int rows) {
        return Math.min(220, 32 + Math.max(1, rows) * 26.0);
    }

    private VBox card(String title) {
        Label heading = new Label(title);
        heading.getStyleClass().add("section-title");
        VBox box = new VBox(6, heading);
        box.getStyleClass().add("usage-card");
        return box;
    }

    private static Label help(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-help");
        label.setWrapText(true);
        return label;
    }
}
