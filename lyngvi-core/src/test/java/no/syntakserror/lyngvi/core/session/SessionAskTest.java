package no.syntakserror.lyngvi.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import no.syntakserror.lyngvi.core.api.AgentService;
import no.syntakserror.lyngvi.core.api.AskOptions;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.InitOptions;
import no.syntakserror.lyngvi.core.api.NewSessionOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.api.SessionService;
import no.syntakserror.lyngvi.core.ask.AgentEvent;
import no.syntakserror.lyngvi.core.ask.AgentRun;
import no.syntakserror.lyngvi.core.ask.Suggestion;
import no.syntakserror.lyngvi.core.render.Renderer;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.testing.HybridRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Asks the built-in mock adapter (no Podman): the run pipeline, persistence, busy marker and suggest. */
class SessionAskTest {

    @TempDir Path tmp;
    HybridRunner runner;
    Sandbox sandbox;
    SessionService sessions;

    @BeforeEach
    void setUp() throws Exception {
        runner = new HybridRunner(tmp.resolve("volumes"));
        runner.fake.ok("podman network ls", "[]").ok("podman ps", "[]").ok("podman image exists", "");
        Path repo = Files.createDirectories(tmp.resolve("api"));
        WorkspaceTest.git(runner.real, repo, "init", "-q", "-b", "main");
        WorkspaceTest.git(runner.real, repo, "config", "user.email", "h@example.org");
        WorkspaceTest.git(runner.real, repo, "config", "user.name", "h");
        Files.writeString(repo.resolve("README.md"), "hi\n");
        WorkspaceTest.git(runner.real, repo, "add", "-A");
        WorkspaceTest.git(runner.real, repo, "commit", "-q", "-m", "init");
        Platform platform = new Platform(Platform.Os.LINUX, tmp.resolve("home"), Map.of("XDG_STATE_HOME", tmp.resolve("xdg").toString()), 1234, 5678);
        Sandbox.init(tmp.resolve("sb"), new InitOptions(Optional.empty(), List.of(repo), Optional.empty(), false), platform, runner);
        Path profile = tmp.resolve("sb/profile.json");
        Files.writeString(profile, Files.readString(profile)
                .replace("\"enabled\": [\"claude-code\", \"opencode\"]", "\"enabled\": [\"claude-code\", \"opencode\", \"mock\", \"mock-sh\", \"ghost\"]"));
        sandbox = Sandbox.open(tmp.resolve("sb"), platform, runner);
        new Renderer(sandbox).render();
        sessions = sandbox.sessionService();
    }

    private Conversation newSession(String agent) {
        return sessions.create(new NewSessionOptions(Optional.of("Fix login"), Optional.empty(), Optional.of(agent), Optional.empty(),
                Optional.of(no.syntakserror.lyngvi.core.config.History.NONE), Optional.empty(), Optional.empty(), false));
    }

    private static AskOptions prompt(String text) {
        return new AskOptions(text, Optional.empty(), List.of());
    }

    @Test
    void askStreamsEventsAndPersistsBothMessages() throws Exception {
        Conversation c = newSession("mock");
        List<AgentEvent> events = new ArrayList<>();

        AgentRun run = sessions.ask(c.id(), prompt("add a file please"), events::add);
        AgentRun.Result result = run.waitFor();

        assertThat(result.stopReason()).isEqualTo("end_turn");
        assertThat(events).extracting(AgentEvent::type).containsExactly("start", "text", "tool_start", "tool_end", "text", "usage", "done");
        assertThat(events).extracting(AgentEvent::seq).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(events).allMatch(e -> e.ts() != null && e.ts() > 0);

        Conversation after = sessions.show(c.id());
        assertThat(after.messages()).hasSize(2);
        assertThat(after.messages().get(0).role()).isEqualTo("user");
        assertThat(after.messages().get(0).text()).isEqualTo("add a file please");
        Conversation.Message reply = after.messages().get(1);
        assertThat(reply.role()).isEqualTo("assistant");
        assertThat(reply.text()).isEqualTo("I will look at the repository.\n\nDone: wrote MOCK.md in api.");
        // the work list is the ordered timeline: text, tool, text
        assertThat(reply.work()).extracting(Conversation.WorkItem::type).containsExactly("text", "tool", "text");
        assertThat(reply.work().get(1).name()).isEqualTo("write_file");
        assertThat(reply.work().get(1).output()).isEqualTo("wrote MOCK.md");
        assertThat(reply.usage().input()).isEqualTo(10);
        assertThat(reply.stopReason()).isEqualTo("end_turn");
        assertThat(after.agentSessionId()).startsWith("mock-");
        assertThat(after.lastActiveAt()).isGreaterThanOrEqualTo(c.lastActiveAt());
        assertThat(runner.volumes.dir(c.container() + "-work").resolve("api/MOCK.md")).exists();
        assertThat(sessions.isBusy(c.id())).isFalse();
        assertThat(sessions.status(c.id()).busy()).isFalse();
    }

    @Test
    void secondTurnResumesTheAgentSession() {
        Conversation c = newSession("mock");
        sessions.ask(c.id(), prompt("one"), e -> {}).waitFor();
        String first = sessions.show(c.id()).agentSessionId();
        List<AgentEvent> events = new ArrayList<>();

        sessions.ask(c.id(), prompt("two"), events::add).waitFor();

        assertThat(((AgentEvent.Start) events.getFirst()).agentSessionId()).isEqualTo(first);
        assertThat(sessions.show(c.id()).messages()).hasSize(4);
    }

    /** The model picked for a turn is the conversation's from then on, so the next turn does not fall back. */
    @Test
    void theModelATurnRunsWithBecomesTheConversationsOwn() {
        Conversation c = newSession("mock");
        assertThat(c.model()).isNull();
        List<AgentEvent> events = new ArrayList<>();

        sessions.ask(c.id(), new AskOptions("one", Optional.of("openrouter/qwen/qwen3-coder"), List.of()), events::add).waitFor();

        assertThat(((AgentEvent.Start) events.getFirst()).model()).isEqualTo("openrouter/qwen/qwen3-coder");
        assertThat(sessions.show(c.id()).model()).isEqualTo("openrouter/qwen/qwen3-coder");

        events.clear();
        sessions.ask(c.id(), prompt("two"), events::add).waitFor();

        assertThat(((AgentEvent.Start) events.getFirst()).model()).isEqualTo("openrouter/qwen/qwen3-coder");
        assertThat(sessions.show(c.id()).model()).isEqualTo("openrouter/qwen/qwen3-coder");
    }

    @Test
    void failuresArePersistedWithStopReasonError() {
        Conversation c = newSession("mock");

        AgentRun.Result r = sessions.ask(c.id(), prompt("please fail"), e -> {}).waitFor();

        assertThat(r.stopReason()).isEqualTo("error");
        assertThat(r.error()).isNotNull();
        Conversation.Message reply = sessions.show(c.id()).messages().get(1);
        assertThat(reply.stopReason()).isEqualTo("error");
        assertThat(reply.text()).contains("the prompt asked for a failure");
    }

    @Test
    void cancelEndsWithCancelled() throws Exception {
        Conversation c = newSession("mock");
        CountDownLatch started = new CountDownLatch(1);

        AgentRun run = sessions.ask(c.id(), prompt("please hang"), e -> {
            if (e instanceof AgentEvent.Start) started.countDown();
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sessions.isBusy(c.id())).isTrue();
        run.cancel();
        AgentRun.Result r = run.waitFor();

        assertThat(r.stopReason()).isEqualTo("cancelled");
        assertThat(sessions.show(c.id()).messages().get(1).stopReason()).isEqualTo("cancelled");
        assertThat(sessions.isBusy(c.id())).isFalse();
    }

    @Test
    void aBusySessionRejectsASecondAsk() throws Exception {
        Conversation c = newSession("mock");
        CountDownLatch started = new CountDownLatch(1);
        AgentRun run = sessions.ask(c.id(), prompt("please hang"), e -> {
            if (e instanceof AgentEvent.Start) started.countDown();
        });
        started.await(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> sessions.ask(c.id(), prompt("again"), e -> {}))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("busy");
        run.cancel();
        run.waitFor();
    }

    @Test
    void attachmentsAreCopiedIntoTheSessionAndPassedAsContainerPaths(@TempDir Path other) throws Exception {
        Conversation c = newSession("mock");
        Path image = Files.writeString(other.resolve("shot.png"), "png");

        sessions.ask(c.id(), new AskOptions("look", Optional.empty(), List.of(image)), e -> {}).waitFor();

        assertThat(sandbox.paths().sessionAttachments(c.id()).resolve("shot.png")).exists();
        assertThat(sessions.show(c.id()).messages().get(0).attachments()).containsExactly("/work/_attachments/shot.png");
    }

    @Test
    void theExternalMockAdapterWorksThroughTheSamePipeline() {
        Conversation c = newSession("mock-sh");
        List<AgentEvent> events = new ArrayList<>();

        AgentRun.Result r = sessions.ask(c.id(), prompt("go"), events::add).waitFor();

        assertThat(r.stopReason()).isEqualTo("end_turn");
        assertThat(sessions.show(c.id()).messages()).hasSize(2);
        // The adapter is handed the session's volume, which is the only way in to the working copy; what it
        // writes is not asserted here, because the write is a podman of its own and this test has no podman.
        assertThat(events).extracting(AgentEvent::type).contains("tool_start", "tool_end");
    }

    @Test
    void suggestUsesTheAdapterAndFallsBackOnFailure() {
        Conversation c = newSession("mock");

        Suggestion s = sessions.suggest(c.id());
        assertThat(s.branch()).isEqualTo("agent/fix-login");
        assertThat(s.message()).startsWith("Mock:");

        Conversation broken = sessions.create(new NewSessionOptions(Optional.of("Weird: Title!"), Optional.empty(), Optional.of("claude-code"), Optional.empty(),
                Optional.of(no.syntakserror.lyngvi.core.config.History.NONE), Optional.empty(), Optional.empty(), false));
        Suggestion fallback = sessions.suggest(broken.id());
        assertThat(fallback.branch()).isEqualTo("agent/weird-title");
        assertThat(fallback.message()).isEqualTo("Weird: Title!");
    }

    @Test
    void askWithoutAnAdapterForTheAgentFails() {
        Conversation c = newSession("ghost");

        assertThatThrownBy(() -> sessions.ask(c.id(), prompt("x"), e -> {}))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void agentServiceListsAdaptersWithProfileAndAuthState() {
        AgentService agents = sandbox.agentService();

        var list = agents.list();
        assertThat(list).extracting("id").contains("mock", "mock-sh", "claude-code", "opencode");
        var mock = list.stream().filter(a -> a.id().equals("mock")).findFirst().orElseThrow();
        assertThat(mock.enabled()).isTrue();
        assertThat(mock.available()).isTrue();
        assertThat(mock.loggedIn()).isTrue();
        var claude = list.stream().filter(a -> a.id().equals("claude-code")).findFirst().orElseThrow();
        assertThat(claude.available()).isTrue();
        assertThat(claude.loggedIn()).isFalse();
        var ghost = list.stream().filter(a -> a.id().equals("ghost")).findFirst().orElseThrow();
        assertThat(ghost.available()).isFalse();
        assertThat(agents.models("mock")).extracting("id").containsExactly("mock-1");
        assertThat(agents.models("claude-code")).extracting("id").containsExactly("sonnet", "opus", "fable", "haiku");
        assertThat(agents.status("mock-sh").account()).isEqualTo("mock");
        assertThatThrownBy(() -> agents.models("ghost")).isInstanceOf(SandboxException.class);
    }
}
