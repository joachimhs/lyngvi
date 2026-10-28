package no.syntakserror.lyngvi.core.stack;

import java.util.Locale;

/** Parses podman-style sizes ({@code 8g}, {@code 512m}) and formats bytes for people. */
public final class ResourceSizes {

    private ResourceSizes() {}

    public static long parse(String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        long mult = 1;
        char last = v.charAt(v.length() - 1);
        if (Character.isLetter(last)) {
            mult = switch (last) {
                case 'k' -> 1L << 10;
                case 'm' -> 1L << 20;
                case 'g' -> 1L << 30;
                case 'b' -> 1L;
                default -> throw new IllegalArgumentException("Unknown size unit in '" + value + "'");
            };
            v = v.substring(0, v.length() - 1);
        }
        return Long.parseLong(v) * mult;
    }

    /**
     * A size as Podman prints it for people — {@code 166.5MB}, {@code 1.222GB}, {@code 0B} — in decimal
     * units, which is what those suffixes mean there. Returns -1 for anything it does not recognise, so a
     * caller can tell «no size» from «zero».
     */
    public static long parseHuman(String value) {
        String v = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?)B?$").matcher(v);
        if (!m.matches()) return -1;
        double number = Double.parseDouble(m.group(1));
        long mult = switch (m.group(2)) {
            case "K" -> 1_000L;
            case "M" -> 1_000_000L;
            case "G" -> 1_000_000_000L;
            case "T" -> 1_000_000_000_000L;
            default -> 1L;
        };
        return Math.round(number * mult);
    }

    public static String format(long bytes) {
        if (bytes >= (1L << 30)) {
            double g = bytes / (double) (1L << 30);
            return (g == Math.rint(g) ? String.format(Locale.ROOT, "%.0f", g) : String.format(Locale.ROOT, "%.1f", g)) + " GiB";
        }
        if (bytes >= (1L << 20)) {
            return String.format(Locale.ROOT, "%.0f", bytes / (double) (1L << 20)) + " MiB";
        }
        return bytes + " B";
    }
}
