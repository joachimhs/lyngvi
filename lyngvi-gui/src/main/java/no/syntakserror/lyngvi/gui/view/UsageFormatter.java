package no.syntakserror.lyngvi.gui.view;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import no.syntakserror.lyngvi.core.ask.UsageLimit;
import no.syntakserror.lyngvi.core.contract.AgentUsageReport;
import no.syntakserror.lyngvi.core.session.Conversation;

/** Formats token counts, cost, quotas and context usage for labels. */
public final class UsageFormatter {

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private UsageFormatter() {}

    public static String count(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    /** An accumulated token count in millions, always with the {@code M} suffix: {@code 0.01M}, {@code 2.50M}. */
    public static String tokens(long n) {
        return String.format(Locale.ROOT, "%.2fM", n / 1_000_000.0);
    }

    public static String cost(Double usd) {
        if (usd == null) return "";
        return usd < 0.1 ? String.format(Locale.ROOT, "$%.3f", usd) : String.format(Locale.ROOT, "$%.2f", usd);
    }

    public static String context(Long used, Long max) {
        if (used == null || max == null || max <= 0) return "";
        return count(used) + " / " + count(max) + " (" + Math.round(100.0 * used / max) + "%)";
    }

    public static double contextRatio(Long used, Long max) {
        if (used == null || max == null || max <= 0) return 0;
        return Math.min(1.0, (double) used / max);
    }

    /** {@code 380 / 400 requests (95%)}; without a ceiling just the running total. */
    public static String limit(UsageLimit l) {
        StringBuilder sb = new StringBuilder(amount(l.used()));
        if (l.limit() != null) sb.append(" / ").append(amount(l.limit()));
        if (l.unit() != null && !l.unit().isBlank()) sb.append(' ').append(l.unit());
        l.ratio().ifPresent(r -> sb.append(" (").append(Math.round(r * 100)).append("%)"));
        return sb.toString();
    }

    /** Whole numbers stay whole; fractional credits keep two decimals (the rule the CLI uses too). */
    public static String amount(double n) {
        return UsageLimit.format(n);
    }

    /** The style class that colours a quota bar, or empty while it is still comfortable. */
    public static String levelStyle(UsageLimit.Level level) {
        return switch (level) {
            case DANGER -> "usage-bar-danger";
            case WARN -> "usage-bar-warn";
            case OK -> "";
        };
    }

    /** An absolute point in time as {@code 2026-09-05 12:00}; empty when the agent reports none. */
    public static String moment(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) return "";
        return MOMENT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /**
     * What a conversation has cost so far, as the conversation list and the chat bar show it:
     * {@code 1.23M · $4.56}; the dollars are left out when no turn reported a price, and the
     * whole thing is empty until a turn has reported usage at all.
     */
    public static String spend(AgentUsageReport.Totals t) {
        if (t == null || t.turns() == 0) return "";
        String tokens = tokens(t.tokens());
        return t.costUsd() == null ? tokens : tokens + " · " + cost(t.costUsd());
    }

    /** The one-line roll-up of what a sandbox, a model or a conversation has spent. */
    public static String totals(AgentUsageReport.Totals t) {
        if (t == null) return "";
        List<String> parts = new ArrayList<>();
        parts.add("in " + count(t.input()));
        parts.add("out " + count(t.output()));
        if (t.cacheRead() > 0 || t.cacheWrite() > 0) parts.add("cache " + count(t.cacheRead() + t.cacheWrite()));
        parts.add(tokens(t.tokens()) + " total");
        if (t.costUsd() != null) parts.add(cost(t.costUsd()));
        return String.join(" · ", parts);
    }

    public static String summary(Conversation.Usage u) {
        if (u == null) return "";
        List<String> parts = new ArrayList<>();
        parts.add("in " + count(u.input()));
        parts.add("out " + count(u.output()));
        if (u.cacheRead() > 0 || u.cacheWrite() > 0) parts.add("cache " + count(u.cacheRead() + u.cacheWrite()));
        if (u.costUsd() != null) parts.add(cost(u.costUsd()));
        String ctx = context(u.contextUsed(), u.contextMax());
        if (!ctx.isEmpty()) parts.add("context " + ctx);
        return String.join(" · ", parts);
    }
}
