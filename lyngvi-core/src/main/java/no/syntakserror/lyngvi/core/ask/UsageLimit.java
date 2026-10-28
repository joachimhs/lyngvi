package no.syntakserror.lyngvi.core.ask;

import java.util.Locale;
import java.util.OptionalDouble;

/**
 * One quota the agent's account is subject to: how much of {@code limit} {@code unit}s is
 * spent, and when the window resets. {@code limit} is null when the agent reports a running
 * total without a ceiling.
 */
public record UsageLimit(String id, String label, double used, Double limit, String unit, Long resetAt) {

    /** The thresholds the front-ends colour by: three quarters warns, nine tenths is danger. */
    public static final double WARN = 0.75;
    public static final double DANGER = 0.9;

    public enum Level { OK, WARN, DANGER }

    /** How much of the quota is spent, 0..1; empty when the agent reports no ceiling. */
    public OptionalDouble ratio() {
        if (limit == null || limit <= 0) return OptionalDouble.empty();
        return OptionalDouble.of(Math.min(1.0, used / limit));
    }

    public Level level() {
        double r = ratio().orElse(0);
        return r >= DANGER ? Level.DANGER : r >= WARN ? Level.WARN : Level.OK;
    }

    /**
     * An amount as both front-ends print it: request counts stay whole, fractional credits keep
     * two decimals. Shared so the CLI and the GUI cannot drift apart.
     */
    public static String format(double amount) {
        return amount == Math.rint(amount)
                ? String.format(Locale.ROOT, "%,d", (long) amount)
                : String.format(Locale.ROOT, "%,.2f", amount);
    }
}
