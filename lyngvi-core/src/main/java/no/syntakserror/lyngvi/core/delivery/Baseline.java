package no.syntakserror.lyngvi.core.delivery;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.contract.ReviewResult;
import no.syntakserror.lyngvi.core.session.Conversation;

/** The commit a delivery is measured from, per repository (spec §5.8). */
public record Baseline(String kind, Integer index, String describe, Map<String, String> commits) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** {@code start}, {@code last} or a delivery index; empty means {@code last} when deliveries exist, else {@code start}. */
    public static Baseline resolve(Conversation c, Optional<String> from) {
        String f = from.map(String::strip).orElse(c.deliveries().isEmpty() ? "start" : "last");
        if (f.equals("start")) {
            return new Baseline("start", null, "since the session started", commitsOf(c, null));
        }
        if (f.equals("last")) {
            if (c.deliveries().isEmpty()) return resolve(c, Optional.of("start"));
            int i = c.deliveries().size() - 1;
            return new Baseline("last", i, describeDelivery(c, i), commitsOf(c, i));
        }
        int i;
        try {
            i = Integer.parseInt(f);
        } catch (NumberFormatException e) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "--from must be start, last or a delivery index; got '" + f + "'");
        }
        if (i < 0 || i >= c.deliveries().size()) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "No delivery with index " + i + " (the session has " + c.deliveries().size() + ")");
        }
        return new Baseline("delivery", i, describeDelivery(c, i), commitsOf(c, i));
    }

    private static String describeDelivery(Conversation c, int i) {
        Conversation.Delivery d = c.deliveries().get(i);
        return "since delivery " + i + " at " + TIME.format(Instant.ofEpochMilli(d.at()).atZone(ZoneId.systemDefault())) + " (" + d.branch() + ")";
    }

    /** Per repository: the delivery's copy commit when that delivery touched the repository, else the base commit. */
    private static Map<String, String> commitsOf(Conversation c, Integer deliveryIndex) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String repo : c.repos()) {
            String commit = c.base().get(repo).baseCommit();
            if (deliveryIndex != null) {
                for (int i = deliveryIndex; i >= 0; i--) {
                    Conversation.DeliveryRepo dr = c.deliveries().get(i).repos().get(repo);
                    if (dr != null) { commit = dr.copyCommit(); break; }
                }
            }
            out.put(repo, commit);
        }
        return out;
    }

    public ReviewResult.From toFrom() {
        return new ReviewResult.From(kind, index, describe);
    }
}
