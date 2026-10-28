package no.syntakserror.lyngvi.gui.viewmodel;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import no.syntakserror.lyngvi.core.api.AddRepoOptions;
import no.syntakserror.lyngvi.core.api.ApplyOptions;
import no.syntakserror.lyngvi.core.api.AskOptions;
import no.syntakserror.lyngvi.core.api.ExportOptions;
import no.syntakserror.lyngvi.core.api.NewSessionOptions;
import no.syntakserror.lyngvi.core.ask.AgentRun;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.ask.Suggestion;
import no.syntakserror.lyngvi.core.contract.AgentInfo;
import no.syntakserror.lyngvi.core.contract.ApplyResult;
import no.syntakserror.lyngvi.core.contract.ExportResult;
import no.syntakserror.lyngvi.core.contract.ReviewResult;
import no.syntakserror.lyngvi.core.contract.SessionInfo;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.stack.SessionVolume;

/** Sessions: list, create, ask (live), stop, deliver, remove (spec §9.2 {@code SessionViewModel}). */
public final class SessionViewModel extends BaseViewModel {

    /** UI updates per second for live bubbles, as a batch interval. */
    static final long BATCH_MILLIS = 33;

    private final ObservableList<SessionInfo> sessions = FXCollections.observableArrayList();
    private final Map<String, AgentRunBinder> running = new ConcurrentHashMap<>();
    private final Map<String, LiveTurn> live = new ConcurrentHashMap<>();
    private final Map<String, AgentRunBinder.Listener> watchers = new ConcurrentHashMap<>();
    private final ChatActivity activity = new ChatActivity();

    public SessionViewModel(SandboxContext ctx, Async async, AppLog log) {
        super(ctx, async, log);
    }

    public ObservableList<SessionInfo> sessions() {
        return sessions;
    }

    public ChatActivity activity() {
        return activity;
    }

    public void refresh() {
        call(() -> sandbox().sessionService().list(), sessions::setAll);
    }

    public void create(NewSessionOptions options, Consumer<Conversation> onCreated) {
        create(options, onCreated, this::fail);
    }

    /**
     * Creates a session: a working copy per repository, which is the one step in Lyngvi that takes real time.
     * {@code onError} is how a view that shows the wait gets to take it down again when the creation fails.
     */
    public void create(NewSessionOptions options, Consumer<Conversation> onCreated, Consumer<Throwable> onError) {
        long started = System.currentTimeMillis();
        call(() -> {
            Conversation c = sandbox().sessionService().create(options);
            return Map.entry(c, sandbox().sessionService().list());
        }, e -> {
            log.info("created session " + e.getKey().shortId() + " in " + (System.currentTimeMillis() - started) / 1000 + "s: " + e.getKey().title());
            sessions.setAll(e.getValue());
            onCreated.accept(e.getKey());
        }, onError);
    }

    public void show(String id, Consumer<Conversation> onShown) {
        call(() -> sandbox().sessionService().show(id), onShown);
    }

    public void show(String id, Consumer<Conversation> onShown, Consumer<Throwable> onError) {
        call(() -> sandbox().sessionService().show(id), onShown, onError);
    }

    /**
     * The files of a session — the working copies and the output — as a listing per root directory. They live
     * in the session's volume, so reading them is a container of its own and belongs off the UI thread like
     * every other core call (spec §5.1).
     */
    public void files(Conversation c, List<String> roots, int maxDepth, int maxEntries,
                      Consumer<Map<String, List<SessionVolume.Entry>>> onListed, Consumer<Throwable> onError) {
        call(() -> {
            SessionVolume volume = sandbox().sessionService().volume(c);
            Map<String, List<SessionVolume.Entry>> all = new LinkedHashMap<>();
            for (String root : roots) all.put(root, volume.tree(root, maxDepth, maxEntries));
            return all;
        }, onListed, onError);
    }

    /** The bytes of one file in the session's volume; larger than {@code maxBytes} is refused by the core. */
    public void readFile(Conversation c, String path, long maxBytes, Consumer<byte[]> onRead, Consumer<Throwable> onError) {
        call(() -> sandbox().sessionService().volume(c).read(path, maxBytes), onRead, onError);
    }

    /** Writes one file in the session's volume, as the user edited it. */
    public void writeFile(Conversation c, String path, byte[] content, Runnable onWritten, Consumer<Throwable> onError) {
        call(() -> {
            sandbox().sessionService().volume(c).write(path, content);
            return path;
        }, p -> onWritten.run(), onError);
    }

    public boolean isRunning(String id) {
        return running.containsKey(id);
    }

    /**
     * Where the files the human attached to a conversation are kept on this machine — the copies the agent reads
     * as {@code /work/_attachments/} — so that the transcript can show the images among them. Empty when no
     * sandbox is open.
     */
    public Optional<Path> attachments(String id) {
        return ctx.isOpen() ? Optional.of(sandbox().paths().sessionAttachments(id)) : Optional.empty();
    }

    /** The state of the turn running in a conversation, so a view can show it after switching back. */
    public Optional<LiveTurn> liveTurn(String id) {
        return Optional.ofNullable(live.get(id));
    }

    /** Replaces the listener of a running turn: the view now showing the conversation takes over the updates. */
    public void watch(String id, AgentRunBinder.Listener listener) {
        if (running.containsKey(id)) watchers.put(id, listener);
    }

    /** Stops delivering updates to the view that left the conversation; the turn keeps running. */
    public void unwatch(String id) {
        watchers.remove(id);
    }

    /**
     * Starts a turn. Any number of conversations can run at once: each has its own container and its own
     * {@link LiveTurn}. Updates go to the listener given here until {@link #watch} replaces it, always on the UI
     * thread; when the run ends the conversation is already persisted by the core, so callers reload it with
     * {@link #show}.
     */
    public AgentRunBinder ask(String id, String prompt, List<Path> attachments, Optional<String> model, AgentRunBinder.Listener listener) {
        String agent = sessions.stream().filter(x -> x.id().equals(id)).map(SessionInfo::agent).findFirst().orElse("");
        LiveTurn turn = new LiveTurn(agent, prompt, attachments.stream().map(p -> p.getFileName().toString()).toList(), System.currentTimeMillis());
        AgentRunBinder binder = new AgentRunBinder(async, batchMillis(), new AgentRunBinder.Listener() {
            private AgentRunBinder.Listener view() { return watchers.getOrDefault(id, listener); }
            @Override public void onStart(String a, String m) { turn.onStart(a, m); view().onStart(a, m); }
            @Override public void onText(String full) { turn.onText(full); view().onText(full); }
            @Override public void onReasoning(String full) { turn.onReasoning(full); view().onReasoning(full); }
            @Override public void onWork(List<Conversation.WorkItem> work) { turn.onWork(work); view().onWork(work); }
            @Override public void onNotice(String level, String text) { turn.onNotice(level, text); view().onNotice(level, text); }
            @Override public void onUsage(Conversation.Usage usage) { turn.onUsage(usage); view().onUsage(usage); }
            @Override public void onDone(AgentRunBinder.Outcome outcome) {
                AgentRunBinder.Listener v = view();
                running.remove(id);
                live.remove(id);
                watchers.remove(id);
                activity.finished(id, outcome.ok());
                log.info("session " + id + " finished: " + outcome.stopReason());
                v.onDone(outcome);
                refresh();
            }
        });
        activity.started(id);
        running.put(id, binder);
        live.put(id, turn);
        try {
            AgentRun run = sandbox().sessionService().ask(id, new AskOptions(prompt, model, attachments), binder.consumer());
            binder.attach(run);
            // A blocking wait: always its own thread, whatever executor the UI injected.
            Thread.ofVirtual().start(() -> {
                run.waitFor();
                binder.streamEnded();
            });
        } catch (RuntimeException e) {
            running.remove(id);
            live.remove(id);
            watchers.remove(id);
            activity.finished(id, false);
            binder.streamEnded();
            fail(e);
        }
        return binder;
    }

    private long batchMillis() {
        return async.ui() == UiThread.direct() ? 0 : BATCH_MILLIS;
    }

    /** Cancels the running turn; the core ends it with {@code done}/{@code cancelled}. */
    public void stop(String id) {
        AgentRunBinder b = running.get(id);
        if (b != null) b.cancel();
    }

    public void rename(String id, String title) {
        call(() -> { sandbox().sessionService().rename(id, title); return sandbox().sessionService().list(); }, sessions::setAll);
    }

    /**
     * Adds working copies to a conversation that already exists (spec §5.2.1). Building them is a clone and a
     * copy per repository, so it belongs off the UI thread like every other core call; {@code onAdded} gets the
     * conversation as it is afterwards.
     */
    public void addRepos(String id, AddRepoOptions options, Consumer<Conversation> onAdded) {
        call(() -> {
            Conversation c = sandbox().sessionService().addRepos(id, options);
            return Map.entry(c, sandbox().sessionService().list());
        }, e -> {
            log.info("added " + String.join(", ", options.repos()) + " to session " + e.getKey().shortId());
            sessions.setAll(e.getValue());
            onAdded.accept(e.getKey());
        });
    }

    /**
     * Removes a session from the sandbox that is open at the moment of the call, and only writes a log line if it
     * cannot. The ephemeral «Ask the agent» conversation is thrown away like this when a window changes sandbox:
     * the call lands after the change, by which time {@code sandbox()} would be the sandbox it was never in, and a
     * throwaway conversation that outlives its window is not worth a dialog.
     */
    public void discard(String id) {
        var sandbox = sandbox();
        call(() -> { sandbox.sessionService().remove(id, false); return id; },
                removed -> log.info("removed session " + removed),
                t -> log.warn("could not remove session " + id + ": " + t.getMessage()));
    }

    public void remove(String id, boolean keepConversation) {
        call(() -> { sandbox().sessionService().remove(id, keepConversation); return sandbox().sessionService().list(); },
                s -> { log.info("removed session " + id); sessions.setAll(s); });
    }

    public void startContainer(String id) {
        call(() -> { sandbox().sessionService().start(id); return sandbox().sessionService().list(); }, sessions::setAll);
    }

    public void stopContainer(String id) {
        call(() -> { sandbox().sessionService().stop(id); return sandbox().sessionService().list(); }, sessions::setAll);
    }

    public void suggest(String id, Consumer<Suggestion> onSuggestion, Consumer<Throwable> onError) {
        call(() -> sandbox().sessionService().suggest(id), onSuggestion, onError);
    }

    /** Local branches of the session's host repositories, current first. */
    public void branches(String id, Consumer<List<String>> onBranches) {
        call(() -> sandbox().deliveryService().branches(id), onBranches, t -> onBranches.accept(List.of()));
    }

    public void diff(String id, Optional<String> from, Optional<String> repo, Optional<String> path, Consumer<String> onDiff) {
        diff(id, from, repo, path, false, onDiff);
    }

    /** With {@code wholeFiles} every changed file comes in full, its changes marked as usual. */
    public void diff(String id, Optional<String> from, Optional<String> repo, Optional<String> path, boolean wholeFiles, Consumer<String> onDiff) {
        call(() -> sandbox().deliveryService().diff(id, from, repo, path, wholeFiles), onDiff);
    }

    public void review(String id, Optional<String> from, Consumer<ReviewResult> onResult, Consumer<Throwable> onError) {
        call(() -> sandbox().deliveryService().review(id, from), onResult, onError);
    }

    /**
     * Writes the whole session — every working copy and the agent's output — to one zip on the host. The
     * last resort when a delivery cannot be made to work; it changes nothing in the session, so the deliver
     * button means the same afterwards as it did before.
     */
    public void export(String id, ExportOptions options, Consumer<ExportResult> onResult, Consumer<Throwable> onError) {
        call(() -> sandbox().sessionService().export(id, options),
                r -> { log.info("exported session " + id + " to " + r.path()); onResult.accept(r); }, onError);
    }

    public void apply(String id, ApplyOptions options, Consumer<ApplyResult> onResult, Consumer<Throwable> onError) {
        call(() -> sandbox().deliveryService().apply(id, options), r -> { log.info("applied delivery " + r.deliveryIndex() + " for " + id); onResult.accept(r); refresh(); }, onError);
    }

    public void agents(Consumer<List<AgentInfo>> onAgents) {
        call(() -> sandbox().agentService().list(), onAgents);
    }

    public void models(String agent, Consumer<List<Model>> onModels) {
        models(agent, false, onModels);
    }

    /** With {@code refresh} the agent's own catalogue is re-read; that runs the agent's image, so it takes a moment. */
    public void models(String agent, boolean refresh, Consumer<List<Model>> onModels) {
        call(() -> sandbox().agentService().models(agent, refresh), onModels, t -> onModels.accept(List.of()));
    }

    public int runningCount() {
        return running.size();
    }
}
