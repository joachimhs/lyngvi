package no.syntakserror.lyngvi.core.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunTest {

    @Test
    void specificErrorIsNotOverwrittenByAgentExit() {
        List<AgentEvent> events = new ArrayList<>();
        AgentRun run = new AgentRun(events::add, () -> {});

        run.accept(AgentEvent.start("opencode", "ses_1", "model_1"));
        run.accept(AgentEvent.error("ProviderModelNotFoundError", "Model not found: qwen/qwen3.8-flash"));
        run.accept(AgentEvent.error("AGENT_EXIT", "opencode ended with exit code 1"));
        run.accept(AgentEvent.done("error"));
        run.finish(1);

        AgentRun.Result result = run.waitFor();
        assertThat(result.error()).isEqualTo("Model not found: qwen/qwen3.8-flash");
        assertThat(result.assistantMessage().text()).contains("Model not found: qwen/qwen3.8-flash");
        assertThat(result.assistantMessage().text()).doesNotContain("opencode ended with exit code 1");
    }
}
