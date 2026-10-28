package no.syntakserror.lyngvi.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.api.AddRepoOptions;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.InitOptions;
import no.syntakserror.lyngvi.core.api.NewSessionOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.api.SessionService;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.contract.CleanupReport;
import no.syntakserror.lyngvi.core.contract.SessionContainerInfo;
import no.syntakserror.lyngvi.core.contract.SessionInfo;
import no.syntakserror.lyngvi.core.render.Renderer;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.ProcessRunner;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import no.syntakserror.lyngvi.core.testing.HybridRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Git runs for real (working copies); podman is scripted through a runner that delegates everything else. */
class SessionServiceTest {


    @TempDir Path tmp;
    HybridRunner runner;
    Sandbox sandbox;
    SessionService sessions;
    Path hostRepo;

    @BeforeEach
    void setUp() throws Exception {
        runner = new HybridRunner(tmp.resolve("volumes"));
        runner.fake.ok("podman network ls", "[]").ok("podman ps", "[]").ok("podman run", "").ok("podman start", "")
                .ok("podman stop", "").ok("podman rm", "").ok("podman image exists", "");
        hostRepo = Files.createDirectories(tmp.resolve("api"));
        WorkspaceTest.git(runner.real, hostRepo, "init", "-q", "-b", "main");
        WorkspaceTest.git(runner.real, hostRepo, "config", "user.email", "h@example.org");
        WorkspaceTest.git(runner.real, hostRepo, "config", "user.name", "h");
        Files.writeString(hostRepo.resolve("README.md"), "hi\n");
        WorkspaceTest.git(runner.real, hostRepo, "add", "-A");
        WorkspaceTest.git(runner.real, hostRepo, "commit", "-q", "-m", "init");
        Platform platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(hostRepo), Optional.empty(), false), platform, runner);
        sandbox = Sandbox.open(tmp.resolve("sb"), platform, runner);
        new Renderer(sandbox).render();
        sessions = sandbox.sessionService();
    }

    private static NewSessionOptions defaults() {
        return new NewSessionOptions(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    @Test
    void createMakesWorkingCopiesAndAConversation() throws Exception {
        Conversation c = sessions.create(new NewSessionOptions(Optional.of("Fix login"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false));

        assertThat(c.id()).matches("[0-9a-f-]{36}");
        assertThat(c.title()).isEqualTo("Fix login");
        assertThat(c.agent()).isEqualTo("claude-code");
        assertThat(c.repos()).containsExactly("api");
        assertThat(c.container()).isEqualTo("lyngvi-sb-" + c.shortId());
        assertThat(c.base().get("api").history()).isEqualTo(History.SHALLOW);
        assertThat(c.base().get("api").baseCommit()).isEqualTo(c.base().get("api").hostSha());
        assertThat(c.resources()).isEqualTo(sandbox.config().resources());
        Path volume = runner.volumes.dir(c.container() + "-work");
        assertThat(volume.resolve("api/README.md")).exists();
        assertThat(volume.resolve("api/.git")).isDirectory();
        assertThat(volume.resolve("output")).isDirectory();
        Path session = sandbox.paths().sessionMeta(c.id());
        assertThat(session.resolve("attachments")).isDirectory();
        // the working copy is the agent's and lives in the volume: no part of it is left on the host
        assertThat(session.resolve("work")).doesNotExist();
        assertThat(session.resolve("output")).doesNotExist();
        assertThat(sandbox.paths().conversation(c.id())).isRegularFile();
        assertThat(sessions.list()).extracting("id").containsExactly(c.id());
    }

    @Test
    void aWorkingCopyLeftOnTheHostByAnOlderVersionIsMovedIntoTheVolume() throws Exception {
        Conversation c = sessions.create(defaults());
        // what a pre-volume sandbox looks like: the copy on the host, no volume
        Path host = sandbox.paths().sessionMeta(c.id());
        Files.createDirectories(host.resolve("work/api"));
        Files.writeString(host.resolve("work/api/OLD.md"), "from the host\n");
        Files.createDirectories(host.resolve("output"));
        Files.writeString(host.resolve("output/report.txt"), "output\n");
        runner.volumes.run(List.of("podman", "volume", "rm", "-f", c.container() + "-work"), tmp, Duration.ofSeconds(5));

        Path volume = runner.volumes.dir(c.container() + "-work");
        assertThat(sandbox.sessionService().volume(c).name()).isEqualTo(c.container() + "-work");
        assertThat(volume.resolve("api/OLD.md")).exists();
        assertThat(volume.resolve("output/report.txt")).exists();
        assertThat(host.resolve("work")).doesNotExist();
        assertThat(host.resolve("output")).doesNotExist();
    }

    /**
     * A container left idle long enough is removed outright. Nothing of the session goes with it — the
     * volume stays, and the next start builds a new container from the conversation.
     */
    @Test
    void retireRemovesTheContainerAndKeepsTheWork() throws Exception {
        Conversation idle = sessions.create(defaults());
        Conversation fresh = sessions.create(defaults());
        ConversationStore store = new ConversationStore(sandbox.paths());
        store.save(idle.withLastActiveAt(System.currentTimeMillis() - Duration.ofDays(5).toMillis()));
        runner.fake.ok("podman ps", "[{\"Names\":[\"" + idle.container() + "\"],\"State\":\"exited\",\"Status\":\"Exited (0)\",\"Image\":\"x\"},"
                + "{\"Names\":[\"" + fresh.container() + "\"],\"State\":\"exited\",\"Status\":\"Exited (0)\",\"Image\":\"x\"}]");

        List<String> retired = sessions.retire(24);

        assertThat(retired).containsExactly(idle.id());
        assertThat(runner.fake.calls()).contains(List.of("podman", "rm", "-f", idle.container()));
        assertThat(runner.fake.calls()).doesNotContain(List.of("podman", "rm", "-f", fresh.container()));
        // what the session is made of is untouched: the work, the conversation, the files it was given
        assertThat(runner.volumes.dir(idle.container() + "-work").resolve("api/README.md")).exists();
        assertThat(sandbox.paths().conversation(idle.id())).isRegularFile();
        assertThat(sessions.show(idle.id()).repos()).containsExactly("api");
    }

    @Test
    void retireLeavesRunningAndBusySessionsAloneAndIsOffAtZero() {
        Conversation c = sessions.create(defaults());
        ConversationStore store = new ConversationStore(sandbox.paths());
        store.save(c.withLastActiveAt(0L));
        runner.fake.ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"}]");

        assertThat(sessions.retire(1)).isEmpty();
        assertThat(sessions.retire(0)).isEmpty();
        assertThat(runner.fake.calls()).doesNotContain(List.of("podman", "rm", "-f", c.container()));
    }

    @Test
    void removeTakesTheVolumeWithIt() {
        Conversation c = sessions.create(defaults());
        assertThat(runner.volumes.dir(c.container() + "-work")).isDirectory();

        sessions.remove(c.id(), false);

        assertThat(runner.volumes.dir(c.container() + "-work")).doesNotExist();
    }

    @Test
    void createHonoursHistoryOverridesAndDefaultTitle() {
        Conversation c = sessions.create(new NewSessionOptions(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(History.NONE), Optional.empty(), Optional.of(new ResourceLimits("16g", null, null, null)), false));

        assertThat(c.title()).startsWith("Session ");
        assertThat(c.base().get("api").history()).isEqualTo(History.NONE);
        assertThat(c.resources().mem()).isEqualTo("16g");
        assertThat(c.resources().memswap()).isEqualTo("12g");
    }

    @Test
    void createRejectsUnknownReposAndDisabledAgents() {
        assertThatThrownBy(() -> sessions.create(new NewSessionOptions(Optional.empty(), Optional.of(List.of("nope")), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false)))
                .isInstanceOf(SandboxException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> sessions.create(new NewSessionOptions(Optional.empty(), Optional.empty(), Optional.of("hal9000"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false)))
                .isInstanceOf(SandboxException.class).hasMessageContaining("hal9000");
    }

    @Test
    void noReposMakesAnEmptySession() {
        Conversation c = sessions.create(new NewSessionOptions(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), true));

        assertThat(c.repos()).isEmpty();
        assertThat(runner.volumes.dir(c.container() + "-work").resolve("output")).isDirectory();
    }

    @Test
    void startRunsTheContainerAndStartAgainStartsIt() {
        Conversation c = sessions.create(defaults());

        SessionInfo started = sessions.start(c.id());
        assertThat(runner.fake.calls().stream().anyMatch(l -> l.size() > 2 && l.get(0).equals("podman") && l.get(1).equals("run"))).isTrue();
        assertThat(Files.isRegularFile(sandbox.paths().sessionEnvFile(c.id()))).isTrue();
        assertThat(started.container().name()).isEqualTo(c.container());

        runner.fake.ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"exited\",\"Status\":\"Exited (0)\",\"Image\":\"x\"}]");
        sessions.start(c.id());
        assertThat(runner.fake.calls()).contains(List.of("podman", "start", c.container()));

        runner.fake.ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"}]");
        int before = runner.fake.calls().size();
        sessions.start(c.id());
        assertThat(runner.fake.calls().subList(before, runner.fake.calls().size())).noneMatch(l -> l.contains("run") || l.contains("start"));
        assertThat(sessions.status(c.id()).container().state()).isEqualTo("running");
    }

    @Test
    void stopAndRemoveCleanUp() throws Exception {
        Conversation c = sessions.create(defaults());
        sessions.start(c.id());
        runner.fake.ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"}]");

        sessions.stop(c.id());
        assertThat(runner.fake.calls()).contains(List.of("podman", "stop", c.container()));

        sessions.remove(c.id(), false);
        assertThat(runner.fake.calls()).contains(List.of("podman", "rm", "-f", c.container()));
        assertThat(sandbox.paths().sessionMeta(c.id())).doesNotExist();
        assertThat(sandbox.paths().conversation(c.id())).doesNotExist();
    }

    @Test
    void removeCanKeepTheConversation() {
        Conversation c = sessions.create(defaults());

        sessions.remove(c.id(), true);

        assertThat(sandbox.paths().sessionMeta(c.id())).doesNotExist();
        assertThat(sandbox.paths().conversation(c.id())).exists();
    }

    @Test
    void renameUpdatesTheTitle() {
        Conversation c = sessions.create(defaults());

        Conversation renamed = sessions.rename(c.id(), "Better title");

        assertThat(renamed.title()).isEqualTo("Better title");
        assertThat(sessions.show(c.id()).title()).isEqualTo("Better title");
        assertThat(renamed.updatedAt()).isGreaterThanOrEqualTo(c.updatedAt());
    }

    @Test
    void gcStopsRunningContainersIdleLongerThanTheLimit() throws Exception {
        Conversation idle = sessions.create(defaults());
        Conversation fresh = sessions.create(defaults());
        ConversationStore store = new ConversationStore(sandbox.paths());
        store.save(idle.withLastActiveAt(System.currentTimeMillis() - Duration.ofHours(3).toMillis()));
        runner.fake.ok("podman ps", "[{\"Names\":[\"" + idle.container() + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"},"
                + "{\"Names\":[\"" + fresh.container() + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"}]");

        List<String> stopped = sessions.gc(60);

        assertThat(stopped).containsExactly(idle.id());
        assertThat(runner.fake.calls()).contains(List.of("podman", "stop", idle.container()));
        assertThat(runner.fake.calls()).doesNotContain(List.of("podman", "stop", fresh.container()));
    }

    @Test
    void containersListsEveryContainerOfTheSandboxAndMarksThoseWithoutAConversation() {
        Conversation c = sessions.create(new NewSessionOptions(Optional.of("Known"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false));
        String orphan = "lyngvi-" + sandbox.paths().projectName() + "-deadbeef";
        runner.fake.ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"running\",\"Status\":\"Up (healthy)\",\"Image\":\"localhost/lyngvi-claude-code:2.0.0\"},"
                + "{\"Names\":[\"" + orphan + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"localhost/lyngvi-claude-code:2.0.0-M9\"}]");
        List<SessionContainerInfo> all = sessions.containers();
        assertThat(all).hasSize(2);
        SessionContainerInfo known = all.getFirst();
        assertThat(known.container()).isEqualTo(c.container());
        assertThat(known.sessionId()).isEqualTo(c.id());
        assertThat(known.title()).isEqualTo("Known");
        assertThat(known.state()).isEqualTo("running");
        assertThat(known.health()).isEqualTo("healthy");
        assertThat(known.image()).isEqualTo("localhost/lyngvi-claude-code:2.0.0");
        assertThat(known.orphan()).isFalse();
        SessionContainerInfo stray = all.getLast();
        assertThat(stray.container()).isEqualTo(orphan);
        assertThat(stray.sessionId()).isNull();
        assertThat(stray.orphan()).isTrue();
        assertThat(stray.image()).endsWith("2.0.0-M9");
    }

    @Test
    void cleanupStopsIdleContainersAndRemovesOrphans() throws Exception {
        Conversation idle = sessions.create(new NewSessionOptions(Optional.of("Idle"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false));
        String orphan = "lyngvi-" + sandbox.paths().projectName() + "-deadbeef";
        runner.fake.ok("podman ps", "[{\"Names\":[\"" + idle.container() + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"},"
                + "{\"Names\":[\"" + orphan + "\"],\"State\":\"exited\",\"Status\":\"Exited (0)\",\"Image\":\"x\"}]");
        Thread.sleep(5);
        CleanupReport report = sessions.cleanup(0);
        assertThat(report.stopped()).containsExactly(idle.id());
        assertThat(report.removed()).containsExactly(orphan);
        assertThat(runner.fake.calls()).contains(List.of("podman", "stop", idle.container()), List.of("podman", "rm", "-f", orphan));
        // a session whose container is gone is untouched; nothing to clean leaves an empty report
        runner.fake.ok("podman ps", "[]");
        assertThat(sessions.cleanup(0).stopped()).isEmpty();
        assertThat(sessions.cleanup(0).removed()).isEmpty();
    }

    @Test
    void removeOrphanRemovesOnlyContainersOfThisSandboxWithoutAConversation() {
        Conversation c = sessions.create(defaults());
        String orphan = "lyngvi-" + sandbox.paths().projectName() + "-deadbeef";
        runner.fake.ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"},"
                + "{\"Names\":[\"" + orphan + "\"],\"State\":\"exited\",\"Status\":\"Exited (0)\",\"Image\":\"x\"}]");
        sessions.removeOrphan(orphan);
        assertThat(runner.fake.calls()).contains(List.of("podman", "rm", "-f", orphan));
        // a container that belongs to a conversation, or to something else entirely, is refused
        assertThatThrownBy(() -> sessions.removeOrphan(c.container())).isInstanceOf(SandboxException.class)
                .hasMessageContaining(c.shortId());
        assertThatThrownBy(() -> sessions.removeOrphan("sandbox1-proxy")).isInstanceOf(SandboxException.class);
        assertThat(runner.fake.calls()).doesNotContain(List.of("podman", "rm", "-f", c.container()), List.of("podman", "rm", "-f", "sandbox1-proxy"));
    }

    @Test
    void shellExecsBashInsideTheContainerInteractively() {
        Conversation c = sessions.create(defaults());
        runner.fake.ok("podman ps", "[{\"Names\":[\"" + c.container() + "\"],\"State\":\"running\",\"Status\":\"Up\",\"Image\":\"x\"}]");

        int exit = sessions.shell(c.id());

        assertThat(exit).isZero();
        assertThat(runner.fake.calls()).contains(List.of("podman", "exec", "-it", "-w", "/work", c.container(), "bash"));
    }

    @Test
    void unknownSessionIsAValidationError() {
        assertThatThrownBy(() -> sessions.show("nope"))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void sessionIdsCanBeAbbreviated() {
        Conversation c = sessions.create(defaults());

        assertThat(sessions.show(c.shortId()).id()).isEqualTo(c.id());
    }

    /**
     * The whole of {@code /work} is the session's volume, so a repository added halfway through is a copy
     * into that volume and nothing else: the container, if there is one, is the one it was.
     */
    @Test
    void addReposBringsAnotherWorkingCopyIntoAConversationThatExists() throws Exception {
        Conversation c = sessions.create(defaults());
        sandbox.configService().addRepo(hostRepo("web"), Optional.empty(), Optional.empty());

        Conversation with = sessions.addRepos(c.id(), AddRepoOptions.of(List.of("web")));

        assertThat(with.repos()).containsExactly("api", "web");
        // every copy keeps the baseline it started from; the one added has its own
        assertThat(with.base().get("api")).isEqualTo(c.base().get("api"));
        assertThat(with.base().get("web").baseCommit()).isNotBlank();
        Path volume = runner.volumes.dir(c.container() + "-work");
        assertThat(volume.resolve("web/README.md")).exists();
        assertThat(volume.resolve("web/.git")).isDirectory();
        assertThat(runner.fake.calls()).doesNotContain(List.of("podman", "rm", "-f", c.container()));
        assertThat(sessions.show(c.id()).repos()).containsExactly("api", "web");
    }

    @Test
    void addReposHonoursTheHistoryChosenForThem() throws Exception {
        Conversation c = sessions.create(defaults());
        sandbox.configService().addRepo(hostRepo("web"), Optional.empty(), Optional.empty());

        Conversation with = sessions.addRepos(c.id(),
                new AddRepoOptions(List.of("web"), Optional.of(History.NONE), Optional.empty()));

        assertThat(with.base().get("web").history()).isEqualTo(History.NONE);
        assertThat(with.base().get("api").history()).isEqualTo(History.SHALLOW);
    }

    @Test
    void addReposRefusesWhatIsAlreadyThereAndWhatIsNotRegistered() {
        Conversation c = sessions.create(defaults());

        assertThatThrownBy(() -> sessions.addRepos(c.id(), AddRepoOptions.of(List.of("api"))))
                .isInstanceOf(SandboxException.class).hasMessageContaining("already in the conversation");
        assertThatThrownBy(() -> sessions.addRepos(c.id(), AddRepoOptions.of(List.of("nope"))))
                .isInstanceOf(SandboxException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> sessions.addRepos(c.id(), AddRepoOptions.of(List.of())))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
        assertThat(sessions.show(c.id()).repos()).containsExactly("api");
    }

    /** A tree must not appear under the agent while it is working in it, and the run owns the conversation file. */
    @Test
    void addReposIsRefusedWhileTheAgentIsRunning() throws Exception {
        Conversation c = sessions.create(defaults());
        sandbox.configService().addRepo(hostRepo("web"), Optional.empty(), Optional.empty());
        Path marker = sandbox.paths().state().resolve("runs").resolve(c.id() + ".json");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "{\"startedAt\":1}");

        assertThatThrownBy(() -> sessions.addRepos(c.id(), AddRepoOptions.of(List.of("web"))))
                .isInstanceOf(SandboxException.class).hasMessageContaining("busy");

        Files.delete(marker);
        assertThat(sessions.addRepos(c.id(), AddRepoOptions.of(List.of("web"))).repos()).containsExactly("api", "web");
    }

    /** A directory the agent made where the copy would go is not merged with the host's tree. */
    @Test
    void addReposRefusesANameTheSessionAlreadyUses() throws Exception {
        Conversation c = sessions.create(defaults());
        sandbox.configService().addRepo(hostRepo("web"), Optional.empty(), Optional.empty());
        sessions.volume(c).mkdirs("web");

        assertThatThrownBy(() -> sessions.addRepos(c.id(), AddRepoOptions.of(List.of("web"))))
                .isInstanceOf(SandboxException.class).hasMessageContaining("/work/web");
        assertThat(sessions.show(c.id()).repos()).containsExactly("api");
    }

    private Path hostRepo(String name) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve(name));
        WorkspaceTest.git(runner.real, repo, "init", "-q", "-b", "main");
        WorkspaceTest.git(runner.real, repo, "config", "user.email", "h@example.org");
        WorkspaceTest.git(runner.real, repo, "config", "user.name", "h");
        Files.writeString(repo.resolve("README.md"), name + "\n");
        WorkspaceTest.git(runner.real, repo, "add", "-A");
        WorkspaceTest.git(runner.real, repo, "commit", "-q", "-m", "init");
        return repo;
    }
}
