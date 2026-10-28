package no.syntakserror.lyngvi.core.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the bundled agents/mock-sh/adapter.sh for real, in local mode (no Podman). */
class ProcessAdapterTest {

    private static final Path ADAPTER_DIR = Path.of("..", "agents", "mock-sh").toAbsolutePath().normalize();

    private static ProcessAdapter adapter() {
        return new ProcessAdapter(AgentDescriptor.read(ADAPTER_DIR.resolve("agent.json")), new ProcessRunner(Platform.current()));
    }

    private static AskRequest request(Path tmp, String prompt) throws Exception {
        Path session = Files.createDirectories(tmp.resolve("session"));
        Files.createDirectories(session.resolve("work/api"));
        Files.createDirectories(session.resolve("attachments"));
        Path promptFile = Files.writeString(session.resolve("attachments/prompt.txt"), prompt);
        return new AskRequest("6f1c", session, "lyngvi-sb-6f1c", null, null, List.of("api"), null, "mock-1", promptFile, List.of(), true);
    }

    @Test
    void streamsTheEventSequenceAndWritesTheFile(@TempDir Path tmp) throws Exception {
        AskRequest req = request(tmp, "please add a file");
        List<AgentEvent> events = new ArrayList<>();

        AgentRunHandle run = adapter().ask(req, events::add);
        int exit = run.waitFor();

        assertThat(exit).isZero();
        assertThat(events).extracting(AgentEvent::type).containsExactly("start", "text", "tool_start", "tool_end", "text", "usage", "done");
        assertThat(((AgentEvent.Start) events.getFirst()).agentSessionId()).startsWith("mock-");
        assertThat(((AgentEvent.Done) events.getLast()).stopReason()).isEqualTo("end_turn");
        assertThat(Files.readString(req.sessionDir().resolve("work/api/MOCK.md"))).contains("mock-sh");
    }

    @Test
    void resumesWithTheGivenAgentSessionId(@TempDir Path tmp) throws Exception {
        AskRequest req = request(tmp, "again");
        AskRequest resumed = new AskRequest(req.sessionId(), req.sessionDir(), req.container(), req.volume(), req.image(), req.repos(), "mock-42", req.model(), req.promptFile(), req.attachments(), true);
        List<AgentEvent> events = new ArrayList<>();

        adapter().ask(resumed, events::add).waitFor();

        assertThat(((AgentEvent.Start) events.getFirst()).agentSessionId()).isEqualTo("mock-42");
    }

    @Test
    void errorsFromTheAdapterArriveAsErrorThenDone(@TempDir Path tmp) throws Exception {
        List<AgentEvent> events = new ArrayList<>();

        adapter().ask(request(tmp, "please fail"), events::add).waitFor();

        assertThat(events).extracting(AgentEvent::type).containsExactly("start", "error", "done");
        assertThat(((AgentEvent.Done) events.getLast()).stopReason()).isEqualTo("error");
    }

    @Test
    void cancelKillsTheProcessAndEndsWithCancelled(@TempDir Path tmp) throws Exception {
        List<AgentEvent> events = new ArrayList<>();
        CountDownLatch started = new CountDownLatch(1);

        AgentRunHandle run = adapter().ask(request(tmp, "please hang"), e -> {
            events.add(e);
            if (e instanceof AgentEvent.Start) started.countDown();
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        run.cancel();
        run.waitFor();

        assertThat(events.getLast()).isInstanceOf(AgentEvent.Done.class);
        assertThat(((AgentEvent.Done) events.getLast()).stopReason()).isEqualTo("cancelled");
    }

    @Test
    void suggestModelsAndAuthStatusParseTheAdapterJson(@TempDir Path tmp) throws Exception {
        ProcessAdapter a = adapter();

        Suggestion s = a.suggest(request(tmp, "x"), "diff --git a/x b/x");
        assertThat(s.branch()).isEqualTo("agent/mock-change");
        assertThat(s.message()).isEqualTo("Mock: apply the requested change");
        assertThat(a.models()).extracting("id").containsExactly("mock-1", "mock-2");
        assertThat(a.models().getFirst().isDefault()).isTrue();
        assertThat(a.authStatus().loggedIn()).isTrue();
        AgentUsage usage = a.usage();
        assertThat(usage.supported()).isTrue();
        assertThat(usage.plan()).isEqualTo("Mock Pro");
        assertThat(usage.limits()).extracting(UsageLimit::id).containsExactly("session", "weekly");
        assertThat(usage.limits().getLast().level()).isEqualTo(UsageLimit.Level.DANGER);
        assertThat(a.descriptor().id()).isEqualTo("mock-sh");
    }

    @Test
    void aMissingDoneBecomesDoneWithError(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("broken"));
        Files.writeString(dir.resolve("agent.json"), "{\"id\":\"broken\",\"label\":\"Broken\",\"egress\":[]}");
        Files.writeString(dir.resolve("adapter.sh"), "#!/bin/sh\nprintf '{\"type\":\"start\",\"agent\":\"broken\",\"agentSessionId\":\"b\",\"model\":\"m\"}\\n'\nprintf '{\"type\":\"text\",\"delta\":\"partial\"}\\n'\nexit 3\n");
        dir.resolve("adapter.sh").toFile().setExecutable(true);
        ProcessAdapter broken = new ProcessAdapter(AgentDescriptor.read(dir.resolve("agent.json")), new ProcessRunner(Platform.current()));
        List<AgentEvent> events = new ArrayList<>();

        int exit = broken.ask(request(tmp, "x"), events::add).waitFor();

        assertThat(exit).isEqualTo(3);
        assertThat(events).extracting(AgentEvent::type).containsExactly("start", "text", "error", "done");
        assertThat(((AgentEvent.Done) events.getLast()).stopReason()).isEqualTo("error");
    }

    @Test
    void environmentCarriesTheContract(@TempDir Path tmp) throws Exception {
        AskRequest req = request(tmp, "x");

        Map<String, String> env = ProcessAdapter.environment("ask", req, Map.of("EXTRA", "1"));

        assertThat(env).containsEntry("AS_SESSION_ID", "6f1c").containsEntry("AS_CONTAINER", "lyngvi-sb-6f1c")
                .containsEntry("AS_SESSION_DIR", req.sessionDir().toString()).containsEntry("AS_REPOS", "api")
                .containsEntry("AS_MODEL", "mock-1").containsEntry("AS_PROMPT_FILE", req.promptFile().toString())
                .containsEntry("AS_WORKDIR", "/work").containsEntry("AS_LOCAL", "1").containsEntry("AS_AGENT_SESSION_ID", "")
                .containsEntry("AS_ATTACHMENTS", "").containsEntry("EXTRA", "1");
    }
}
