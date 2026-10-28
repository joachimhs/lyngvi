package no.syntakserror.lyngvi.gui.view;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;
import no.syntakserror.lyngvi.core.session.Conversation;

/**
 * The baselines a diff or a delivery can be measured from: the session start, and the point each delivery left
 * behind. The value is what the core takes ({@code start} or a delivery index); the label is what the user reads,
 * the delivery's time and branch. Pure, so the diff window and the deliver dialog cannot disagree.
 */
public final class BaselineChoices {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** One baseline: {@code value} goes to the core, {@code label} is shown. */
    public record Choice(String value, String label) {}

    private BaselineChoices() {}

    /** The session start first, then one entry per delivery, oldest first — the order they were made in. */
    public static List<Choice> of(Conversation c, ResourceBundle b) {
        List<Choice> out = new ArrayList<>();
        out.add(new Choice("start", b.getString("gui.baseline.start")));
        for (int i = 0; i < c.deliveries().size(); i++) {
            Conversation.Delivery d = c.deliveries().get(i);
            String when = TIME.format(Instant.ofEpochMilli(d.at()).atZone(ZoneId.systemDefault()));
            out.add(new Choice(String.valueOf(i), b.getString("gui.baseline.delivery").replace("{0}", when).replace("{1}", d.branch())));
        }
        return List.copyOf(out);
    }

    /** What a window opens on: the newest delivery, so the diff shows what has happened since — else the start. */
    public static String initial(Conversation c) {
        return c.deliveries().isEmpty() ? "start" : String.valueOf(c.deliveries().size() - 1);
    }

    /** The core's {@code last} as an index, so a baseline the core names matches the entry in the list. */
    public static String canonical(String value, Conversation c) {
        return "last".equals(value) ? initial(c) : value;
    }

    /** The label for a value, falling back to the value itself for one the conversation no longer has. */
    public static String label(String value, Conversation c, ResourceBundle b) {
        String v = canonical(value, c);
        return of(c, b).stream().filter(ch -> ch.value().equals(v)).map(Choice::label).findFirst().orElse(v);
    }

    /** Fills a picker: the items are the values the core takes, shown by their labels. */
    public static void fill(ComboBox<String> combo, Conversation c, ResourceBundle b) {
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : label(value, c, b);
            }

            @Override
            public String fromString(String shown) {
                return shown;
            }
        });
        combo.getItems().setAll(of(c, b).stream().map(Choice::value).toList());
        combo.setValue(initial(c));
    }
}
