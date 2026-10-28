package no.syntakserror.lyngvi.gui.viewmodel;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The message bundle as the views see it. A key the runtime's core lacks (a GUI built against a newer core than the
 * one it runs with) yields {@code !key!} and one log line instead of a {@code MissingResourceException} on the FX
 * thread, so a stale build degrades to a visible marker rather than a crash.
 */
public final class LenientBundle extends ResourceBundle {

    private final ResourceBundle delegate;
    private final AppLog log;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public LenientBundle(ResourceBundle delegate, AppLog log) {
        this.delegate = delegate;
        this.log = log;
    }

    @Override
    protected Object handleGetObject(String key) {
        if (delegate.containsKey(key)) return delegate.getObject(key);
        if (reported.add(key)) log.warn("missing message key " + key + " (the GUI is newer than the core it runs with; rebuild with mvn install)");
        return "!" + key + "!";
    }

    @Override
    public Enumeration<String> getKeys() {
        return Collections.enumeration(delegate.keySet());
    }

    @Override
    public boolean containsKey(String key) {
        return delegate.containsKey(key);
    }

    @Override
    public Set<String> keySet() {
        return delegate.keySet();
    }

    @Override
    public Locale getLocale() {
        return delegate.getLocale();
    }
}
