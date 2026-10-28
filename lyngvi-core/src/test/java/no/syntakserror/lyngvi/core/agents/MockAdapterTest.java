package no.syntakserror.lyngvi.core.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.ask.AgentEvent;
import no.syntakserror.lyngvi.core.ask.AskRequest;
import no.syntakserror.lyngvi.core.stack.SessionVolumes;
import no.syntakserror.lyngvi.core.testing.HybridRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MockAdapterTest {

    private static AskRequest request(Path tmp, String prompt) throws Exception {
        Path session = Files.createDirectories(tmp.resolve("session"));
        Path promptFile = Files.writeString(session.resolve("prompt.txt"), prompt);
        return new AskRequest("6f1c", session, "lyngvi-sb-6f1c", "lyngvi-sb-6f1c-work", "img", List.of("api"), null, null, promptFile, List.of(), true);
    }

    private static HybridRunner runner(Path tmp) {
        return new HybridRunner(tmp.resolve("volumes"));
    }

    private static SessionVolumes volumes(HybridRunner runner, Path tmp) {
        return new SessionVolumes(runner, tmp, tmp.resolve("tmp"), "sb", "img", 1, 1);
    }

    @Test
    void writesTheFileIntoTheSessionVolumeAndEmitsTheSequence(@TempDir Path tmp) throws Exception {
        HybridRunner runner = runner(tmp);
        SessionVolumes volumes = volumes(runner, tmp);
        volumes.of("lyngvi-sb-6f1c", "6f1c").create();
        volumes.of("lyngvi-sb-6f1c").mkdirs("api");
        List<AgentEvent> events = new ArrayList<>();

        new MockAdapter(volumes).ask(request(tmp, "add a file"), events::add).waitFor();

        assertThat(events).extracting(AgentEvent::type).containsExactly("start", "text", "tool_start", "tool_end", "text", "usage", "done");
        assertThat(Files.readString(runner.volumes.dir("lyngvi-sb-6f1c-work").resolve("api/MOCK.md"))).contains("mock");
        // nothing of the agent's work is on the host: the file only exists inside the volume
        assertThat(tmp.resolve("session/work")).doesNotExist();
    }

    @Test
    void reportsAFailedWriteInsteadOfThrowing(@TempDir Path tmp) throws Exception {
        HybridRunner runner = runner(tmp);
        List<AgentEvent> events = new ArrayList<>();

        new MockAdapter(volumes(runner, tmp)).ask(request(tmp, "add a file"), events::add).waitFor();

        assertThat(events).extracting(AgentEvent::type).contains("tool_end");
        assertThat(events.getLast()).isInstanceOf(AgentEvent.Done.class);
    }

    @Test
    void failPromptProducesErrorAndSuggestIsDeterministic(@TempDir Path tmp) throws Exception {
        MockAdapter mock = new MockAdapter(volumes(runner(tmp), tmp));
        List<AgentEvent> events = new ArrayList<>();

        mock.ask(request(tmp, "please fail now"), events::add).waitFor();

        assertThat(events).extracting(AgentEvent::type).containsExactly("start", "error", "done");
        assertThat(mock.suggest(request(tmp, "Fix the login redirect"), "diff").branch()).isEqualTo("agent/fix-the-login-redirect");
        assertThat(mock.models()).extracting("id").containsExactly("mock-1");
        assertThat(mock.authStatus().loggedIn()).isTrue();
        assertThat(mock.descriptor().id()).isEqualTo("mock");
    }
}
