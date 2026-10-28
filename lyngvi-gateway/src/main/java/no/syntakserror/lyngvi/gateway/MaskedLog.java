package no.syntakserror.lyngvi.gateway;

import java.io.PrintStream;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import no.syntakserror.lyngvi.gateway.api.Log;

/** Logs to stdout with every registered secret value replaced by asterisks. */
public final class MaskedLog implements Log {

    private final Set<String> secrets;
    private final PrintStream out;
    private final String prefix;

    public MaskedLog(PrintStream out, String prefix) {
        this(out, prefix, new CopyOnWriteArraySet<>());
    }

    private MaskedLog(PrintStream out, String prefix, Set<String> secrets) {
        this.out = out;
        this.prefix = prefix;
        this.secrets = secrets;
    }

    /** A log with a longer prefix that shares this log's secret set (secrets registered later are masked in both). */
    public MaskedLog child(String suffix) {
        return new MaskedLog(out, prefix + " " + suffix, secrets);
    }

    public void registerSecret(String secret) {
        if (secret != null && secret.length() >= 4) secrets.add(secret);
    }

    String mask(String message) {
        String m = message == null ? "" : message;
        for (String s : secrets) m = m.replace(s, "******");
        return m.replaceAll("(?i)(authorization:\\s*)\\S+.*", "$1******");
    }

    @Override
    public void info(String message) {
        out.println(prefix + " " + mask(message));
    }

    @Override
    public void warn(String message) {
        out.println(prefix + " WARN " + mask(message));
    }

    @Override
    public void error(String message, Throwable t) {
        out.println(prefix + " ERROR " + mask(message) + (t == null ? "" : ": " + mask(String.valueOf(t.getMessage()))));
    }
}
