package no.syntakserror.lyngvi.gui.viewmodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.ask.AgentEvent;
import no.syntakserror.lyngvi.core.ask.AgentRun;
import no.syntakserror.lyngvi.core.session.Conversation;

/**
 * Subscribes to one agent run, batches events (at most ~30 UI updates per second) and builds the live
 * bubble on the UI thread (spec §9.4). A stream that ends without {@code done} is reported as an error.
 */
public final class AgentRunBinder {

    /** What the live bubble needs. Every method runs on the UI thread. */
    public interface Listener {
        default void onStart(String agent, String model) {}
        default void onText(String fullText) {}
        default void onReasoning(String fullReasoning) {}
        default void onWork(List<Conversation.WorkItem> work) {}
        default void onNotice(String level, String text) {}
        default void onUsage(Conversation.Usage usage) {}
        default void onDone(Outcome outcome) {}
    }

    public record Outcome(String stopReason, String error, Conversation.Usage usage, boolean cancelled) {
        public boolean ok() {
            return error == null && !"error".equals(stopReason) && !cancelled;
        }
    }

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-run-batcher");
        t.setDaemon(true);
        return t;
    });

    private final Async async;
    private final long batchMillis;
    private final Listener listener;
    private final Object lock = new Object();
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final no.syntakserror.lyngvi.core.ask.WorkTimeline timeline = new no.syntakserror.lyngvi.core.ask.WorkTimeline();
    private final List<AgentEvent.Notice> notices = new ArrayList<>();
    private Conversation.Usage usage;
    private AgentEvent.Start start;
    private String error;
    private String stopReason;
    private boolean textDirty;
    private boolean reasoningDirty;
    private boolean workDirty;
    private boolean usageDirty;
    private boolean flushScheduled;
    private boolean finished;
    private boolean persisted;
    private volatile boolean cancelled;
    private volatile AgentRun run;

    public AgentRunBinder(Async async, long batchMillis, Listener listener) {
        this.async = async;
        this.batchMillis = batchMillis;
        this.listener = listener;
    }

    /** The consumer to hand to {@code SessionService.ask}. */
    public Consumer<AgentEvent> consumer() {
        return this::accept;
    }

    public void attach(AgentRun run) {
        this.run = run;
    }

    public void cancel() {
        cancelled = true;
        AgentRun r = run;
        if (r != null) r.cancel();
    }

    public boolean isFinished() {
        synchronized (lock) {
            return finished;
        }
    }

    private void accept(AgentEvent e) {
        boolean done = false;
        synchronized (lock) {
            if (finished) return;
            switch (e) {
                case AgentEvent.Start s -> start = s;
                case AgentEvent.Text t -> { text.append(t.delta()); textDirty = true; timeline.accept(e); workDirty = true; }
                case AgentEvent.Reasoning r -> { reasoning.append(r.delta()); reasoningDirty = true; }
                case AgentEvent.ToolStart t -> { timeline.accept(e); workDirty = true; }
                case AgentEvent.ToolEnd t -> { timeline.accept(e); workDirty = true; }
                case AgentEvent.Notice n -> notices.add(n);
                case AgentEvent.Usage u -> { usage = new Conversation.Usage(u.input(), u.output(), u.cacheRead(), u.cacheWrite(), u.costUsd(), u.contextUsed(), u.contextMax()); usageDirty = true; }
                case AgentEvent.Error er -> {
                    if (error == null || !"AGENT_EXIT".equals(er.code())) {
                        error = er.message();
                    }
                }
                case AgentEvent.Done d -> { stopReason = d.stopReason(); finished = true; done = true; }
            }
            if (done || batchMillis <= 0) {
                flushScheduled = false;
            } else if (flushScheduled) {
                return;
            } else {
                flushScheduled = true;
                TIMER.schedule(() -> async.onUi(this::flush), batchMillis, TimeUnit.MILLISECONDS);
                return;
            }
        }
        async.onUi(this::flush);
    }

    /**
     * The run's {@code waitFor} returned: the core has persisted the conversation. A stream that ended without a
     * {@code done} event is reported as an error. {@code onDone} is delivered only after this call (spec §9.4 point 4).
     */
    public void streamEnded() {
        synchronized (lock) {
            persisted = true;
            if (!finished) {
                finished = true;
                stopReason = "error";
                if (error == null) error = "The agent stream ended without a result";
            }
        }
        async.onUi(this::flush);
    }

    private void flush() {
        AgentEvent.Start s;
        String t = null;
        String r = null;
        List<Conversation.WorkItem> w = null;
        List<AgentEvent.Notice> n;
        Conversation.Usage u = null;
        boolean done;
        String stop;
        String err;
        synchronized (lock) {
            s = start;
            start = null;
            if (textDirty) { t = timeline.text(); textDirty = false; }
            if (reasoningDirty) { r = reasoning.toString(); reasoningDirty = false; }
            if (workDirty) { w = timeline.items(); workDirty = false; }
            n = new ArrayList<>(notices);
            notices.clear();
            if (usageDirty) { u = usage; usageDirty = false; }
            done = finished && persisted && !doneReported;
            if (done) doneReported = true;
            stop = stopReason;
            err = error;
            flushScheduled = false;
        }
        if (s != null) listener.onStart(s.agent(), s.model());
        if (t != null) listener.onText(t);
        if (r != null) listener.onReasoning(r);
        if (w != null) listener.onWork(w);
        for (AgentEvent.Notice notice : n) listener.onNotice(notice.level(), notice.text());
        if (u != null) listener.onUsage(u);
        if (done) listener.onDone(new Outcome(stop, err, usage, cancelled || "cancelled".equals(stop)));
    }

    private boolean doneReported;
}
