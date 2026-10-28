package no.syntakserror.lyngvi.core.ask;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Account-level usage, when the agent exposes it (adapter action {@code usage}, spec §6.2).
 *
 * <p>The adapter's JSON is read by {@link #parse}: {@code supported}, {@code plan},
 * {@code note}, {@code resetAt} and a {@code limits} array of
 * {@code {id, label, used, limit, unit, resetAt}}. Anything else the agent reports is kept
 * verbatim in {@code extra} so a front-end can still show it.
 */
public record AgentUsage(boolean supported, String plan, String note, Long resetAt, List<UsageLimit> limits, Map<String, Object> extra) {

    private static final Set<String> KNOWN = Set.of("supported", "plan", "note", "resetAt", "limits");

    public AgentUsage {
        limits = limits == null ? List.of() : List.copyOf(limits);
        // a LinkedHashMap, not Map.copyOf: the agent's own order is the order the front-ends show
        extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    public static AgentUsage unsupported() {
        return unsupported(null);
    }

    /** Not reported; {@code note} says why when there is something useful to say. */
    public static AgentUsage unsupported(String note) {
        return new AgentUsage(false, null, note, null, List.of(), Map.of());
    }

    /** Reads the adapter's JSON leniently: a malformed member is skipped, never fatal. */
    public static AgentUsage parse(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return unsupported();
        List<UsageLimit> limits = new ArrayList<>();
        if (data.get("limits") instanceof List<?> raw) {
            for (Object o : raw) {
                if (o instanceof Map<?, ?> entry) {
                    UsageLimit limit = limit(entry);
                    if (limit != null) limits.add(limit);
                }
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (!KNOWN.contains(k) && v != null) extra.put(k, v);
        });
        return new AgentUsage(!Boolean.FALSE.equals(data.get("supported")), text(data.get("plan")), text(data.get("note")),
                epochMillis(data.get("resetAt")), limits, extra);
    }

    private static UsageLimit limit(Map<?, ?> entry) {
        String id = text(entry.get("id"));
        String label = text(entry.get("label"));
        if (id == null && label == null) return null;
        Double used = number(entry.get("used"));
        return new UsageLimit(id == null ? label : id, label == null ? id : label,
                used == null ? 0 : used, number(entry.get("limit")), text(entry.get("unit")), epochMillis(entry.get("resetAt")));
    }

    private static String text(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).strip();
        return s.isEmpty() ? null : s;
    }

    private static Double number(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        String s = text(o);
        if (s == null) return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Epoch millis, either as a number or as an ISO-8601 instant such as {@code 2026-09-05T12:00:00Z}. */
    private static Long epochMillis(Object o) {
        if (o instanceof Number n) return n.longValue();
        String s = text(o);
        if (s == null) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException ignored) {
            // not a number; try an instant below
        }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
