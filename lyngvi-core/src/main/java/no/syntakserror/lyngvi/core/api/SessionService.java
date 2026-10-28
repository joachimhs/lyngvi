package no.syntakserror.lyngvi.core.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.ask.AgentAdapter;
import no.syntakserror.lyngvi.core.ask.AgentEvent;
import no.syntakserror.lyngvi.core.ask.AgentRun;
import no.syntakserror.lyngvi.core.ask.AgentRunHandle;
import no.syntakserror.lyngvi.core.ask.AskRequest;
import no.syntakserror.lyngvi.core.ask.Suggestion;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.RepoEntry;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.contract.CleanupReport;
import no.syntakserror.lyngvi.core.contract.ExportResult;
import no.syntakserror.lyngvi.core.contract.SessionContainerInfo;
import no.syntakserror.lyngvi.core.contract.SessionInfo;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.session.ConversationStore;
import no.syntakserror.lyngvi.core.session.CopyFilter;
import no.syntakserror.lyngvi.core.session.Locks;
import no.syntakserror.lyngvi.core.session.SessionContainer;
import no.syntakserror.lyngvi.core.session.SessionExport;
import no.syntakserror.lyngvi.core.session.WorkGit;
import no.syntakserror.lyngvi.core.session.Workspace;
import no.syntakserror.lyngvi.core.stack.Podman;
import no.syntakserror.lyngvi.core.stack.SessionVolume;

/** Sessions: working copies, conversation files and one container per session (spec §5). */
public final class SessionService {

    private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Sandbox sandbox;
    private final ConversationStore store;
    private final SessionContainer containers;
    private final Podman podman;
    /** Volumes already seen by this service: {@link #volume} only has to look once. */
    private final Map<String, SessionVolume> volumes = new HashMap<>();

    SessionService(Sandbox sandbox) {
        this.sandbox = sandbox;
        this.store = new ConversationStore(sandbox.paths());
        this.containers = new SessionContainer(sandbox);
        this.podman = new Podman(sandbox.runner(), sandbox.paths().root());
    }

    public Conversation create(NewSessionOptions options) {
        var config = sandbox.config();
        var profile = sandbox.profile();
        String agent = options.agent().orElse(config.defaultAgent());
        if (!profile.agents().enabled().contains(agent)) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Agent '" + agent + "' is not enabled in profile '" + profile.name() + "'",
                    Map.of("agent", agent));
        }
        List<RepoEntry> repos = new ArrayList<>();
        if (!options.noRepos()) {
            List<String> wanted = options.repos().orElse(config.repos().stream().map(RepoEntry::name).toList());
            for (String name : wanted) {
                repos.add(config.repo(name).orElseThrow(() -> new SandboxException(ErrorCode.VALIDATION_FAILED,
                        "No registered repository named '" + name + "' (see 'setup.sh repo list')", Map.of("repo", name))));
            }
        }
        ResourceLimits resources = config.resources().merge(options.resources().orElse(new ResourceLimits(null, null, null, null)));
        List<String> bad = resources.invalidFields();
        if (!bad.isEmpty()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Invalid resource value for " + String.join(", ", bad), Map.of("fields", bad));
        }
        String cacheMode = options.cache().map(String::strip).filter(s -> !s.isBlank()).orElse(null);
        if (cacheMode != null && !no.syntakserror.lyngvi.core.config.Profile.Cache.MODES.contains(cacheMode)) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT,
                    "--cache must be one of " + String.join(", ", no.syntakserror.lyngvi.core.config.Profile.Cache.MODES),
                    Map.of("cache", cacheMode));
        }
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        String title = options.title().filter(t -> !t.isBlank()).orElse("Session " + TITLE_TIME.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())));
        Path session = sandbox.paths().sessionMeta(id);
        String container = "lyngvi-" + sandbox.paths().projectName() + "-" + id.substring(0, 8);
        requireBaseImage();
        SessionVolume volume = sandbox.sessionVolumes().of(container, id);
        try (Locks.Held ignored = Locks.session(sandbox.paths(), id)) {
            try {
                Files.createDirectories(sandbox.paths().sessionAttachments(id));
                volume.create();
                volume.mkdirs("output");
                Map<String, Conversation.RepoBase> base = new LinkedHashMap<>();
                CopyFilter filter = CopyFilter.forProfile(profile.copy());
                for (RepoEntry repo : repos) {
                    History mode = options.history().or(repo::history).orElse(profile.copy().history());
                    int depth = options.depth().orElse(profile.copy().depth());
                    Workspace.Result r = Workspace.createIn(sandbox.runner(), volume, repo.name(), repo.path(),
                            mode, depth, profile.git(), filter);
                    base.put(repo.name(), new Conversation.RepoBase(r.hostSha(), r.hostBranch(), r.baseCommit(), r.hostDirty(), r.history(), r.depth()));
                }
                Conversation c = new Conversation(Conversation.CURRENT_SCHEMA_VERSION, id, title, now, now, now, agent, null,
                        options.model().orElse(null), repos.stream().map(RepoEntry::name).toList(), resources,
                        container, base, List.of(), List.of(), cacheMode);
                store.save(c);
                return c;
            } catch (RuntimeException e) {
                volume.remove();
                deleteTree(session);
                throw e;
            }
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot create session directory " + session + ": " + e.getMessage(), e);
        }
    }

    /**
     * Adds working copies of registered repositories to a conversation that already exists (spec §5.2.1).
     *
     * <p>The whole of {@code /work} is the session's volume, so a copy built now shows up in the container
     * the session is already running in, under {@code /work/<name>}, as soon as it is in the volume: no
     * container is rebuilt or restarted and the agent keeps the session it is in. The copies are made the
     * way {@link #create} makes them — the host's {@code .git} never travels — and each gets its own entry
     * in {@code base}, so a delivery measures it from where <em>it</em> started, not from where the
     * conversation did.
     *
     * <p>Refused while the agent is running: the agent would see a tree appear underneath it mid-turn, and
     * the conversation file is being written by that run.
     */
    public Conversation addRepos(String id, AddRepoOptions options) {
        Conversation c = show(id);
        if (options.repos().isEmpty()) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Name at least one repository to add");
        }
        if (isBusy(c.id())) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Session " + c.id() + " is busy: an agent run is in progress",
                    Map.of("id", c.id(), "code", "SESSION_BUSY"));
        }
        var config = sandbox.config();
        var profile = sandbox.profile();
        java.util.Set<String> taken = new java.util.LinkedHashSet<>(c.repos());
        List<RepoEntry> wanted = new ArrayList<>();
        for (String name : options.repos()) {
            if (!taken.add(name)) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Repository '" + name + "' is already in the conversation",
                        Map.of("repo", name, "id", c.id()));
            }
            wanted.add(config.repo(name).orElseThrow(() -> new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "No registered repository named '" + name + "' (see 'setup.sh repo list')", Map.of("repo", name))));
        }
        requireBaseImage();
        SessionVolume volume = volume(c);
        // The agent writes in /work too. A directory of its own where the copy is about to go would be
        // merged with the host's tree rather than replaced, which is no way to find out about it.
        List<String> occupied = volume.tree("", 1, 500).stream().map(SessionVolume.Entry::path).toList();
        for (RepoEntry repo : wanted) {
            if (occupied.contains(repo.name())) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                        "/work/" + repo.name() + " already exists in the session; rename the repository or remove the directory first",
                        Map.of("repo", repo.name(), "id", c.id()));
            }
        }
        try (Locks.Held ignored = Locks.session(sandbox.paths(), c.id())) {
            Conversation latest = store.load(c.id());
            List<String> repos = new ArrayList<>(latest.repos());
            Map<String, Conversation.RepoBase> base = new LinkedHashMap<>(latest.base());
            CopyFilter filter = CopyFilter.forProfile(profile.copy());
            for (RepoEntry repo : wanted) {
                History mode = options.history().or(repo::history).orElse(profile.copy().history());
                int depth = options.depth().orElse(profile.copy().depth());
                Workspace.Result r = Workspace.createIn(sandbox.runner(), volume, repo.name(), repo.path(),
                        mode, depth, profile.git(), filter);
                base.put(repo.name(), new Conversation.RepoBase(r.hostSha(), r.hostBranch(), r.baseCommit(), r.hostDirty(), r.history(), r.depth()));
                repos.add(repo.name());
            }
            Conversation updated = latest.withRepos(repos, base, System.currentTimeMillis());
            store.save(updated);
            return updated;
        }
    }

    /** The working copies are built with git from the base image, so the image has to be there first. */
    private void requireBaseImage() {
        if (!sandbox.stackService().baseImageExists()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "The base image " + StackService.baseImage() + " is missing; run 'setup.sh build' before creating a session",
                    Map.of("image", StackService.baseImage()));
        }
    }

    /**
     * The session's volume, created when it is not there yet. A session made before the volumes existed
     * has its working copies and output on the host; they are moved in here, once, and removed from the
     * host — that is the whole point of the volume.
     */
    public SessionVolume volume(Conversation c) {
        SessionVolume known = volumes.get(c.id());
        if (known != null) return known;
        SessionVolume volume = sandbox.sessionVolumes().of(c.container(), c.id());
        volumes.put(c.id(), volume);
        if (volume.exists()) return volume;
        requireBaseImage();
        volume.create();
        Path legacy = sandbox.paths().sessionMeta(c.id()).resolve("work");
        if (Files.isDirectory(legacy)) {
            volume.copyIn(legacy, "");
            deleteTree(legacy);
        }
        Path legacyOutput = sandbox.paths().sessionMeta(c.id()).resolve("output");
        if (Files.isDirectory(legacyOutput)) {
            volume.copyIn(legacyOutput, "output");
            deleteTree(legacyOutput);
        } else {
            volume.mkdirs("output");
        }
        return volume;
    }

    /** Git in one of the session's working copies; it runs in a helper container, never on the host. */
    public WorkGit work(Conversation c, String repo) {
        return new WorkGit(volume(c), repo);
    }

    /**
     * Writes everything the session has — every working copy and the agent's {@code output/} — into one zip
     * on the host (spec §5.9), and changes nothing: no baseline moves, and the session is as it was.
     *
     * <p>A delivery is the way work is meant to leave a session, file by file and reviewed. This is the way
     * out when that cannot be made to work — the patch will not apply, the host repository has moved too far,
     * the files are the wrong shape for a patch at all — and it holds the working copies as they are, with
     * their git history unless {@link ExportOptions#includeGit()} says otherwise.
     */
    public ExportResult export(String id, ExportOptions options) {
        Conversation c = show(id);
        return SessionExport.write(volume(c), c, sandbox.profile().copy().excludes(), options);
    }

    /** Resolves a full id or a unique prefix of one. */
    public String resolve(String idOrPrefix) {
        if (store.exists(idOrPrefix)) return idOrPrefix;
        List<Conversation> matches = store.list().stream().filter(c -> c.id().startsWith(idOrPrefix)).toList();
        if (matches.size() == 1) return matches.getFirst().id();
        if (matches.isEmpty()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "No session with id '" + idOrPrefix + "'", Map.of("id", idOrPrefix));
        }
        throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Session id '" + idOrPrefix + "' is ambiguous; use more characters", Map.of("id", idOrPrefix));
    }

    public Conversation show(String id) {
        return store.load(resolve(id));
    }

    public List<SessionInfo> list() {
        List<Podman.Container> containers = podman.ps(containerPrefix());
        List<SessionInfo> out = new ArrayList<>();
        for (Conversation c : store.list()) {
            Podman.Container ct = containers.stream().filter(x -> x.name().equals(c.container())).findFirst().orElse(null);
            out.add(SessionInfo.of(c, containerInfo(c, ct), isBusy(c.id())));
        }
        return out;
    }

    private static SessionInfo.ContainerInfo containerInfo(Conversation c, Podman.Container ct) {
        return ct == null ? new SessionInfo.ContainerInfo(c.container(), "", "") : new SessionInfo.ContainerInfo(ct.name(), ct.state(), ct.health());
    }

    public SessionInfo status(String id) {
        Conversation c = show(id);
        return SessionInfo.of(c, containerInfo(c, containers.state(c).orElse(null)), isBusy(c.id()));
    }

    private Path runMarker(String id) {
        return sandbox.paths().state().resolve("runs").resolve(id + ".json");
    }

    /** True while an {@code ask} is in progress for the session (spec §4.4). */
    public boolean isBusy(String id) {
        return Files.isRegularFile(runMarker(id));
    }

    /**
     * Sends one message to the session's agent. Events reach {@code onEvent} on the adapter's
     * thread; the returned run can be cancelled or waited for. The conversation file gets the
     * user message immediately and the assistant message when the run ends.
     */
    public AgentRun ask(String id, AskOptions options, Consumer<AgentEvent> onEvent) {
        Conversation c = show(id);
        AgentAdapter adapter = sandbox.agentService().adapter(c.agent());
        Path marker = runMarker(c.id());
        Path sessionDir = sandbox.paths().sessionMeta(c.id());
        Path attachments = sandbox.paths().sessionAttachments(c.id());
        List<String> attachmentPaths = new ArrayList<>();
        Path promptFile;
        try (Locks.Held ignored = Locks.session(sandbox.paths(), c.id())) {
            if (Files.isRegularFile(marker)) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Session " + c.id() + " is busy: an agent run is in progress",
                        Map.of("id", c.id(), "code", "SESSION_BUSY"));
            }
            try {
                Files.createDirectories(marker.getParent());
                Files.createDirectories(attachments);
                for (Path a : options.attachments()) {
                    Path target = attachments.resolve(a.getFileName().toString());
                    Files.copy(a, target, StandardCopyOption.REPLACE_EXISTING);
                    attachmentPaths.add("/work/_attachments/" + a.getFileName());
                }
                promptFile = attachments.resolve(".prompt-" + System.currentTimeMillis() + ".txt");
                Files.writeString(promptFile, options.prompt());
                Files.writeString(marker, "{\"startedAt\":" + System.currentTimeMillis() + "}");
            } catch (IOException e) {
                throw new SandboxException(ErrorCode.INTERNAL, "Cannot prepare the run: " + e.getMessage(), e);
            }
            long now = System.currentTimeMillis();
            List<Conversation.Message> messages = new ArrayList<>(c.messages());
            messages.add(new Conversation.Message(UUID.randomUUID().toString(), "user", now, options.prompt(), attachmentPaths, List.of(), null, null));
            // The model this turn runs with becomes the conversation's: it is what the next turn resumes on, and
            // what the UI shows when the conversation is reloaded.
            c = withMessages(c, messages, c.agentSessionId(), now, options.model().orElse(c.model()));
            store.save(c);
        }
        boolean local = !adapter.needsContainer();
        AskRequest request = new AskRequest(c.id(), sessionDir, c.container(), volume(c).name(), StackService.baseImage(),
                c.repos(), c.agentSessionId(), c.model(), promptFile, attachmentPaths, local);
        if (!local) {
            start(c.id());
            // The sandbox's switched-on skills, into the agent's configuration directory, before the agent is
            // started: it is read once per message, so a skill changed between two messages takes effect on the
            // next one rather than only in the next conversation (spec §3.6).
            sandbox.skillService().sync(c.agent());
            adapter.prepare(request);
        }
        String sessionId = c.id();
        AgentRun[] holder = new AgentRun[1];
        AgentRun run = new AgentRun(onEvent, () -> {
            AgentRun r = holder[0];
            try (Locks.Held ignored = Locks.session(sandbox.paths(), sessionId)) {
                Conversation latest = store.load(sessionId);
                long now = System.currentTimeMillis();
                List<Conversation.Message> messages = new ArrayList<>(latest.messages());
                messages.add(r.assistantMessage(now));
                store.save(withMessages(latest, messages, r.agentSessionId() != null ? r.agentSessionId() : latest.agentSessionId(), now));
            } finally {
                try {
                    Files.deleteIfExists(marker);
                    Files.deleteIfExists(promptFile);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        });
        holder[0] = run;
        try {
            AgentRunHandle handle = adapter.ask(request, run::accept);
            run.attach(handle);
            Thread.ofVirtual().start(() -> {
                int exit;
                try {
                    exit = handle.waitFor();
                } catch (RuntimeException e) {
                    run.accept(AgentEvent.error("ADAPTER_FAILURE", e.getMessage()));
                    exit = 1;
                }
                run.finish(exit);
            });
        } catch (RuntimeException e) {
            run.accept(AgentEvent.error("ADAPTER_FAILURE", String.valueOf(e.getMessage())));
            run.finish(1);
            throw e;
        }
        return run;
    }

    private static Conversation withMessages(Conversation c, List<Conversation.Message> messages, String agentSessionId, long now) {
        return withMessages(c, messages, agentSessionId, now, c.model());
    }

    private static Conversation withMessages(Conversation c, List<Conversation.Message> messages, String agentSessionId, long now, String model) {
        return new Conversation(c.schemaVersion(), c.id(), c.title(), c.createdAt(), now, now, c.agent(), agentSessionId, model,
                c.repos(), c.resources(), c.container(), c.base(), c.deliveries(), messages, c.cacheMode());
    }

    /** Asks the agent for a branch name and commit message; falls back to a slug of the title after 60 seconds or on failure. */
    public Suggestion suggest(String id) {
        Conversation c = show(id);
        Suggestion fallback = new Suggestion(sandbox.profile().git().branchPrefix() + slug(c.title()), c.title());
        Optional<AgentAdapter> adapter = sandbox.agentService().registry().find(c.agent());
        if (adapter.isEmpty()) return fallback;
        Path sessionDir = sandbox.paths().sessionMeta(c.id());
        try {
            Path promptFile = Files.writeString(sandbox.paths().sessionAttachments(c.id()).resolve(".suggest-prompt.txt"), c.title());
            AskRequest request = new AskRequest(c.id(), sessionDir, c.container(), volume(c).name(), StackService.baseImage(),
                    c.repos(), c.agentSessionId(), c.model(), promptFile, List.of(), !adapter.get().needsContainer());
            String diff = currentDiff(c);
            CompletableFuture<Suggestion> f = CompletableFuture.supplyAsync(() -> adapter.get().suggest(request, diff));
            Suggestion s = f.get(60, TimeUnit.SECONDS);
            Files.deleteIfExists(promptFile);
            if (s == null || s.branch() == null || s.branch().isBlank()) return fallback;
            return new Suggestion(s.branch(), s.message() == null || s.message().isBlank() ? c.title() : s.message());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** A rough diff of every working copy since its base commit, for suggestions; the delivery increment refines this. */
    String currentDiff(Conversation c) {
        StringBuilder sb = new StringBuilder();
        for (String repo : c.repos()) {
            try {
                var r = work(c, repo).sh("""
                        $GIT diff "$FROM" || true
                        STATUS=$($GIT status --porcelain) || true
                        [ -n "$STATUS" ] && printf '# status:\\n%s\\n' "$STATUS"
                        exit 0
                        """, Map.of("FROM", c.base().get(repo).baseCommit()), java.time.Duration.ofMinutes(2));
                if (r.success()) sb.append(r.stdout());
            } catch (SandboxException ignored) {
                // no diff available for this repo
            }
        }
        return sb.toString();
    }

    static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (s.isEmpty()) s = "change";
        return s.length() > 40 ? s.substring(0, 40).replaceAll("-$", "") : s;
    }

    public Conversation rename(String id, String title) {
        if (title == null || title.isBlank()) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Title must not be empty");
        }
        Conversation c = show(id);
        try (Locks.Held ignored = Locks.session(sandbox.paths(), c.id())) {
            Conversation renamed = c.withTitle(title.strip(), System.currentTimeMillis());
            store.save(renamed);
            return renamed;
        }
    }

    public SessionInfo start(String id) {
        Conversation c = show(id);
        try (Locks.Held ignored = Locks.session(sandbox.paths(), c.id())) {
            containers.start(c);
            store.save(c.withLastActiveAt(System.currentTimeMillis()));
        }
        return status(c.id());
    }

    public SessionInfo stop(String id) {
        Conversation c = show(id);
        try (Locks.Held ignored = Locks.session(sandbox.paths(), c.id())) {
            containers.stop(c);
        }
        return status(c.id());
    }

    public void remove(String id, boolean keepConversation) {
        Conversation c = show(id);
        try (Locks.Held ignored = Locks.session(sandbox.paths(), c.id())) {
            containers.remove(c);
            sandbox.sessionVolumes().of(c.container()).remove();
            volumes.remove(c.id());
            deleteTree(sandbox.paths().sessionMeta(c.id()));
            if (!keepConversation) {
                store.delete(c.id());
            }
        }
    }

    /** The name prefix every session container of this sandbox has. */
    private String containerPrefix() {
        return "lyngvi-" + sandbox.paths().projectName() + "-";
    }

    /**
     * Every session container Podman holds for this sandbox, in Podman's order, each joined with its conversation.
     * Containers whose conversation is gone are reported as orphans so they can be shown and removed.
     */
    public List<SessionContainerInfo> containers() {
        Map<String, Conversation> byContainer = new HashMap<>();
        for (Conversation c : store.list()) byContainer.put(c.container(), c);
        List<SessionContainerInfo> out = new ArrayList<>();
        for (Podman.Container ct : podman.ps(containerPrefix())) {
            if (!ct.name().startsWith(containerPrefix())) continue; // podman's name filter is a regex; be exact here
            Conversation c = byContainer.get(ct.name());
            out.add(c == null
                    ? new SessionContainerInfo(ct.name(), null, null, null, ct.state(), ct.health(), ct.image(), 0L, false)
                    : new SessionContainerInfo(ct.name(), c.id(), c.title(), c.agent(), ct.state(), ct.health(), ct.image(), c.lastActiveAt(), isBusy(c.id())));
        }
        return out;
    }

    /**
     * {@link #gc} plus {@link #retire}, removal of orphan containers, and the volumes of sessions that are
     * gone. Busy sessions are never touched. This is what the GUI runs every few minutes, so everything in
     * it has to be safe without asking: no working copy is ever removed here.
     */
    public CleanupReport cleanup(int idleMinutes) {
        return cleanup(idleMinutes, sandbox.profile().session().idleRemoveHours());
    }

    public CleanupReport cleanup(int idleMinutes, int idleRemoveHours) {
        List<String> stopped = gc(idleMinutes);
        List<String> retired = retire(idleRemoveHours);
        List<String> removed = new ArrayList<>();
        for (SessionContainerInfo ct : containers()) {
            if (ct.orphan()) {
                podman.removeForce(ct.container());
                removed.add(ct.container());
            }
        }
        List<String> removedVolumes = new ArrayList<>();
        java.util.Set<String> live = new java.util.HashSet<>();
        for (Conversation c : store.list()) live.add(c.id());
        sandbox.sessionVolumes().list().forEach((volume, sessionId) -> {
            if (live.contains(sessionId)) return;
            sandbox.sessionVolumes().removeByName(volume);
            removedVolumes.add(volume);
        });
        return new CleanupReport(stopped, retired, removed, removedVolumes);
    }

    /** Removes one orphan container and its volume. Refuses containers of other sandboxes and containers that a conversation owns. */
    public void removeOrphan(String container) {
        if (container == null || !container.startsWith(containerPrefix())) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Not a session container of this sandbox: " + container, Map.of("container", String.valueOf(container)));
        }
        for (Conversation c : store.list()) {
            if (c.container().equals(container)) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Container " + container + " belongs to session " + c.shortId() + "; remove the session instead",
                        Map.of("container", container, "id", c.id()));
            }
        }
        podman.removeForce(container);
        sandbox.sessionVolumes().of(container).remove();
    }

    /** Stops containers of sessions idle for longer than {@code idleMinutes}. Returns the ids stopped. */
    public List<String> gc(int idleMinutes) {
        long cutoff = System.currentTimeMillis() - idleMinutes * 60_000L;
        List<String> stopped = new ArrayList<>();
        for (SessionInfo s : list()) {
            if (s.container().state().equals("running") && !s.busy() && s.lastActiveAt() < cutoff) {
                stop(s.id());
                stopped.add(s.id());
            }
        }
        return stopped;
    }

    /**
     * Removes the containers of sessions untouched for longer than {@code idleHours}, and keeps everything
     * else about them. A session container holds nothing that cannot be made again — the work is in the
     * volume, the credentials in {@code state/agents/}, the conversation in its file — so the session goes
     * on working: the next {@code ask} builds the container afresh from the conversation. What it does cost
     * is the tool caches the container had accumulated, which is why {@code cache.mode} and this threshold
     * belong together (spec §5.3).
     *
     * @return the session ids whose containers were removed
     */
    public List<String> retire(int idleHours) {
        if (idleHours <= 0) return List.of();
        long cutoff = System.currentTimeMillis() - idleHours * 3_600_000L;
        List<String> retired = new ArrayList<>();
        for (SessionInfo s : list()) {
            if (s.container().state().isEmpty() || s.container().state().equals("running")) continue;
            if (s.busy() || s.lastActiveAt() >= cutoff) continue;
            Conversation c = store.load(s.id());
            try (Locks.Held ignored = Locks.session(sandbox.paths(), c.id())) {
                containers.remove(c);
                retired.add(c.id());
            }
        }
        return retired;
    }

    /** Interactive shell in the session's container (started first when needed). Returns the shell's exit code. */
    public int shell(String id) {
        Conversation c = show(id);
        if (containers.state(c).map(s -> !s.state().equals("running")).orElse(true)) {
            start(c.id());
        }
        return containers.shell(c);
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new SandboxException(ErrorCode.INTERNAL, "Cannot delete " + p + ": " + e.getMessage(), e);
                }
            });
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot delete " + dir + ": " + e.getMessage(), e);
        }
    }
}
