package no.syntakserror.lyngvi.gui.viewmodel;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** The application log shown in the console and attached to problem reports: a bounded in-memory ring. */
public final class AppLog {

    public enum Level { INFO, WARN, ERROR }

    public record Entry(Instant at, Level level, String message) {
        public String line() {
            return String.format("%s %-5s %s", at, level, message);
        }
    }

    private final int capacity;
    private final ArrayDeque<Entry> ring = new ArrayDeque<>();
    private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();

    public AppLog(int capacity) {
        this.capacity = capacity;
    }

    public AppLog() {
        this(5000);
    }

    public void info(String message) {
        add(Level.INFO, message);
    }

    public void warn(String message) {
        add(Level.WARN, message);
    }

    public void error(String message, Throwable t) {
        add(Level.ERROR, t == null ? message : message + ": " + t.getMessage());
    }

    private void add(Level level, String message) {
        Entry e = new Entry(Instant.now(), level, message);
        synchronized (ring) {
            ring.addLast(e);
            while (ring.size() > capacity) ring.removeFirst();
        }
        for (Consumer<Entry> l : listeners) l.accept(e);
    }

    public List<Entry> entries() {
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries()) sb.append(e.line()).append('\n');
        return sb.toString();
    }

    public void addListener(Consumer<Entry> listener) {
        listeners.add(listener);
    }

    public void clear() {
        synchronized (ring) {
            ring.clear();
        }
    }
}
