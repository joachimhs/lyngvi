package no.syntakserror.lyngvi.core.ask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.session.Conversation;

/**
 * One turn of a session: stamps events with {@code ts}/{@code seq}, forwards them, and
 * accumulates the assistant message that the core persists when the run ends (spec §5.5).
 */
public final class AgentRun {

    /** Outcome of a finished run. {@code error} is the last error event's message, when any. */
    public record Result(String stopReason, int exitCode, Conversation.Message assistantMessage, String agentSessionId, String error) {}

    private final Consumer<AgentEvent> downstream;
    private final Runnable onFinished;
    private final AtomicLong seq = new AtomicLong();
    private final WorkTimeline timeline = new WorkTimeline();
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile AgentRunHandle handle;
    private volatile Conversation.Usage usage;
    private volatile String stopReason;
    private volatile String agentSessionId;
    private volatile String error;
    private volatile Result result;

    public AgentRun(Consumer<AgentEvent> downstream, Runnable onFinished) {
        this.downstream = downstream;
        this.onFinished = onFinished;
    }

    public void attach(AgentRunHandle handle) {
        this.handle = handle;
    }

    /** Called by the adapter thread for every event. */
    public synchronized void accept(AgentEvent raw) {
        AgentEvent e = raw.withMeta(System.currentTimeMillis(), seq.incrementAndGet());
        switch (e) {
            case AgentEvent.Start s -> agentSessionId = s.agentSessionId();
            case AgentEvent.Text t -> timeline.accept(e);
            case AgentEvent.ToolStart t -> timeline.accept(e);
            case AgentEvent.ToolEnd t -> timeline.accept(e);
            case AgentEvent.Usage u -> usage = new Conversation.Usage(u.input(), u.output(), u.cacheRead(), u.cacheWrite(), u.costUsd(), u.contextUsed(), u.contextMax());
            case AgentEvent.Error er -> {
                if (error == null || !"AGENT_EXIT".equals(er.code())) {
                    error = er.message();
                }
            }
            case AgentEvent.Done d -> stopReason = d.stopReason();
            case AgentEvent.Reasoning r -> { }
            case AgentEvent.Notice n -> { }
        }
        try {
            downstream.accept(e);
        } catch (RuntimeException ignored) {
            // a failing consumer must not break persistence
        }
    }

    /** The assistant message accumulated so far (final once {@code done} arrived). */
    public Conversation.Message assistantMessage(long at) {
        String body = timeline.text();
        if (error != null && !body.contains(error)) {
            body = body.isEmpty() ? error : body + "\n\n" + error;
        }
        return new Conversation.Message(java.util.UUID.randomUUID().toString(), "assistant", at, body, List.of(),
                timeline.items(), usage, stopReason == null ? "error" : stopReason);
    }

    public String agentSessionId() {
        return agentSessionId;
    }

    public void cancel() {
        AgentRunHandle h = handle;
        if (h != null) h.cancel();
    }

    /** Blocks until the adapter ended and the conversation was written. */
    public Result waitFor() {
        try {
            finished.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    public void finish(int exitCode) {
        if (stopReason == null) {
            accept(AgentEvent.done("error"));
        }
        result = new Result(stopReason, exitCode, assistantMessage(System.currentTimeMillis()), agentSessionId, error);
        try {
            onFinished.run();
        } finally {
            finished.countDown();
        }
    }
}
