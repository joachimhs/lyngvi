package no.syntakserror.lyngvi.core.stack;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * Parses Squid's default access-log format:
 * {@code <ts> <ms> <client> <code>/<status> <bytes> <method> <url> <user> <hier> <type>}.
 */
public final class SquidLogParser {

    private static final Set<Integer> DENIED_STATUS = Set.of(403, 407, 451);

    private SquidLogParser() {}

    /** One denied request. */
    public record Denied(long at, String host, int status, String method, String url) {}

    public static Optional<Denied> parse(String line) {
        if (line == null || line.isBlank()) return Optional.empty();
        String[] f = line.strip().split("\\s+");
        if (f.length < 7) return Optional.empty();
        long at;
        try {
            at = Math.round(Double.parseDouble(f[0]) * 1000.0);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String[] codeStatus = f[3].split("/");
        if (codeStatus.length != 2) return Optional.empty();
        int status;
        try {
            status = Integer.parseInt(codeStatus[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        boolean denied = codeStatus[0].contains("DENIED") || DENIED_STATUS.contains(status);
        if (!denied) return Optional.empty();
        String method = f[5];
        String url = f[6];
        String host = hostOf(method, url);
        if (host == null || host.isBlank()) return Optional.empty();
        return Optional.of(new Denied(at, host, status, method, url));
    }

    static String hostOf(String method, String url) {
        if ("CONNECT".equals(method) || !url.contains("://")) {
            int colon = url.lastIndexOf(':');
            return colon > 0 ? url.substring(0, colon) : url;
        }
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
