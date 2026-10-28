package no.syntakserror.lyngvi.gui.view;

import java.time.Duration;

/**
 * «3 t», «2 d» — how long ago something happened, as a bundle key and its argument.
 *
 * <p>Returning the key instead of the finished string keeps this pure: the caller formats it with the resource
 * bundle, and the rounding can be tested without a locale.
 */
public final class RelativeTime {

    private RelativeTime() {}

    /** Anything younger than this is «now»: a conversation that just answered should not read «0 min». */
    static final long NOW_MILLIS = Duration.ofMinutes(1).toMillis();

    public record Age(String key, long value) {}

    public static Age of(long ageMillis) {
        long age = Math.max(0, ageMillis);
        if (age < NOW_MILLIS) return new Age("gui.chat.age.now", 0);
        long minutes = Duration.ofMillis(age).toMinutes();
        if (minutes < 60) return new Age("gui.chat.age.minutes", minutes);
        long hours = Duration.ofMillis(age).toHours();
        if (hours < 24) return new Age("gui.chat.age.hours", hours);
        long days = Duration.ofMillis(age).toDays();
        if (days < 7) return new Age("gui.chat.age.days", days);
        return new Age("gui.chat.age.weeks", days / 7);
    }
}
