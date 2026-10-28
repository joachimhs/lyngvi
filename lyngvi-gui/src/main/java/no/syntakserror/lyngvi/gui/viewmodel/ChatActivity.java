package no.syntakserror.lyngvi.gui.viewmodel;

import java.util.LinkedHashSet;
import java.util.Set;
import no.syntakserror.lyngvi.core.i18n.Messages;

/** What the nav-rail badges show: conversations running, finished unread, failed unread. */
public final class ChatActivity {

    private final Set<String> running = new LinkedHashSet<>();
    private final Set<String> done = new LinkedHashSet<>();
    private final Set<String> failed = new LinkedHashSet<>();
    private final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public synchronized void started(String sessionId) {
        running.add(sessionId);
        done.remove(sessionId);
        failed.remove(sessionId);
        notifyListeners();
    }

    public synchronized void finished(String sessionId, boolean ok) {
        running.remove(sessionId);
        (ok ? done : failed).add(sessionId);
        notifyListeners();
    }

    /** The user looked at the conversation: its badge is cleared. */
    public synchronized void seen(String sessionId) {
        done.remove(sessionId);
        failed.remove(sessionId);
        notifyListeners();
    }

    public synchronized boolean isRunning(String sessionId) {
        return running.contains(sessionId);
    }

    /** Finished successfully and not yet looked at since. */
    public synchronized boolean isUnreadDone(String sessionId) {
        return done.contains(sessionId);
    }

    /** Failed and not yet looked at since. */
    public synchronized boolean isUnreadError(String sessionId) {
        return failed.contains(sessionId);
    }

    public synchronized int running() {
        return running.size();
    }

    public synchronized int unreadDone() {
        return done.size();
    }

    public synchronized int unreadErrors() {
        return failed.size();
    }

    public synchronized boolean isIdle() {
        return running.isEmpty() && done.isEmpty() && failed.isEmpty();
    }

    public synchronized String tooltip(Messages m) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (!running.isEmpty()) parts.add(m.get(running.size() == 1 ? "gui.activity.running.one" : "gui.activity.running.many", running.size()));
        if (!done.isEmpty()) parts.add(m.get(done.size() == 1 ? "gui.activity.done.one" : "gui.activity.done.many", done.size()));
        if (!failed.isEmpty()) parts.add(m.get(failed.size() == 1 ? "gui.activity.failed.one" : "gui.activity.failed.many", failed.size()));
        return parts.isEmpty() ? m.get("gui.activity.idle") : String.join(", ", parts);
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    private void notifyListeners() {
        for (Runnable r : listeners) r.run();
    }
}
