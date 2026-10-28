package no.syntakserror.lyngvi.core.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.ask.AgentEvent;
import org.junit.jupiter.api.Test;

class StreamMappersTest {

    private static List<AgentEvent> map(StreamMapper mapper, String fixture) throws Exception {
        List<AgentEvent> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/fixtures/" + fixture))) {
            out.addAll(mapper.map(line));
        }
        out.addAll(mapper.finish(0, false));
        return out;
    }

    @Test
    void claudeStreamJsonMapsToTheEventContract() throws Exception {
        List<AgentEvent> events = map(new ClaudeStreamMapper("claude-code"), "claude-code/turn.jsonl");

        assertThat(events).extracting(AgentEvent::type).containsExactly(
                "start", "reasoning", "text", "tool_start", "tool_end", "tool_start", "tool_end", "text", "usage", "done");
        AgentEvent.Start start = (AgentEvent.Start) events.get(0);
        assertThat(start.agent()).isEqualTo("claude-code");
        assertThat(start.agentSessionId()).isEqualTo("3f2b1c9e-0000-4000-8000-000000000001");
        assertThat(start.model()).isEqualTo("claude-sonnet-4-5-20250929");
        assertThat(((AgentEvent.Reasoning) events.get(1)).delta()).isEqualTo("The user wants a file.");
        assertThat(((AgentEvent.Text) events.get(2)).delta()).isEqualTo("I'll create the file.");
        AgentEvent.ToolStart tool = (AgentEvent.ToolStart) events.get(3);
        assertThat(tool.id()).isEqualTo("toolu_01");
        assertThat(tool.name()).isEqualTo("Write");
        assertThat(tool.input()).contains("\"file_path\":\"/work/api/NEW.md\"");
        AgentEvent.ToolEnd end = (AgentEvent.ToolEnd) events.get(4);
        assertThat(end.output()).isEqualTo("File created successfully at: /work/api/NEW.md");
        assertThat(end.isError()).isFalse();
        AgentEvent.ToolEnd failed = (AgentEvent.ToolEnd) events.get(6);
        assertThat(failed.output()).isEqualTo("cat: /nope: No such file");
        assertThat(failed.isError()).isTrue();
        AgentEvent.Usage usage = (AgentEvent.Usage) events.get(8);
        assertThat(usage.input()).isEqualTo(120);
        assertThat(usage.output()).isEqualTo(80);
        assertThat(usage.cacheRead()).isEqualTo(500);
        assertThat(usage.cacheWrite()).isEqualTo(10);
        assertThat(usage.costUsd()).isEqualTo(0.0123);
        assertThat(((AgentEvent.Done) events.get(9)).stopReason()).isEqualTo("end_turn");
    }

    @Test
    void claudeErrorResultsMapToErrorAndDone() throws Exception {
        List<AgentEvent> events = map(new ClaudeStreamMapper("claude-code"), "claude-code/error.jsonl");

        assertThat(events).extracting(AgentEvent::type).containsExactly("start", "usage", "error", "done");
        assertThat(((AgentEvent.Error) events.get(2)).code()).isEqualTo("error_max_turns");
        assertThat(((AgentEvent.Done) events.get(3)).stopReason()).isEqualTo("max_turns");
    }

    @Test
    void claudeIgnoresNoiseAndSynthesisesDoneWhenTheProcessEndsWithoutResult() {
        ClaudeStreamMapper m = new ClaudeStreamMapper("claude-code");

        assertThat(m.map("Warning: something on stderr-ish stdout")).isEmpty();
        assertThat(m.map("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\"}}")).isEmpty();
        List<AgentEvent> end = m.finish(1, false);
        assertThat(end).extracting(AgentEvent::type).containsExactly("error", "done");
        assertThat(((AgentEvent.Done) end.get(1)).stopReason()).isEqualTo("error");
        assertThat(new ClaudeStreamMapper("claude-code").finish(0, true)).extracting(AgentEvent::type).containsExactly("done");
    }

    @Test
    void opencodeEventsMapToTheEventContract() throws Exception {
        List<AgentEvent> events = map(new OpenCodeStreamMapper("opencode"), "opencode/turn.jsonl");

        assertThat(events).extracting(AgentEvent::type).containsExactly("start", "text", "tool_start", "tool_end", "text", "usage", "done");
        assertThat(((AgentEvent.Start) events.get(0)).agentSessionId()).isEqualTo("ses_01HZX");
        assertThat(((AgentEvent.ToolStart) events.get(2)).name()).isEqualTo("bash");
        assertThat(((AgentEvent.ToolStart) events.get(2)).id()).isEqualTo("call_1");
        assertThat(((AgentEvent.ToolEnd) events.get(3)).output()).isEqualTo("README.md");
        AgentEvent.Usage usage = (AgentEvent.Usage) events.get(5);
        assertThat(usage.input()).isEqualTo(50);
        assertThat(usage.cacheRead()).isEqualTo(5);
        assertThat(usage.costUsd()).isEqualTo(0.002);
    }

    /**
     * The shape {@code opencode run --format json} really prints (1.18.29): one line per finished part, no session
     * or message line. A step that goes straight to its tool calls still prints its text part, empty — and that
     * must not become an empty section in the transcript.
     */
    @Test
    void opencodeRunFormatJsonSkipsTheEmptyTextPartOfAToolOnlyStep() throws Exception {
        List<AgentEvent> events = map(new OpenCodeStreamMapper("opencode"), "opencode/run-format-json.jsonl");

        assertThat(events).extracting(AgentEvent::type).containsExactly(
                "start", "text", "tool_start", "tool_end", "tool_start", "tool_end", "text", "done");
        assertThat(((AgentEvent.Start) events.get(0)).agentSessionId()).isEqualTo("ses_01HZY");
        assertThat(((AgentEvent.Text) events.get(1)).delta()).isEqualTo("Let me look at the tests.");
        assertThat(((AgentEvent.ToolStart) events.get(4)).name()).isEqualTo("read");
        AgentEvent.ToolEnd failed = (AgentEvent.ToolEnd) events.get(5);
        assertThat(failed.output()).isEqualTo("File not found: /nope");
        assertThat(failed.isError()).isTrue();
        assertThat(((AgentEvent.Text) events.get(6)).delta()).isEqualTo("Done.");
        assertThat(((AgentEvent.Done) events.getLast()).stopReason()).isEqualTo("end_turn");
    }

    @Test
    void opencodeToleratesFlatShapes() {
        OpenCodeStreamMapper m = new OpenCodeStreamMapper("opencode");

        assertThat(m.map("{\"type\":\"text\",\"text\":\"plain\"}")).extracting(AgentEvent::type).containsExactly("text");
        assertThat(m.map("{\"type\":\"text\",\"text\":\"  \"}")).isEmpty();
        assertThat(m.map("{\"type\":\"error\",\"message\":\"boom\"}")).extracting(AgentEvent::type).containsExactly("error", "done");
        assertThat(m.map("garbage")).isEmpty();
    }

    @Test
    void opencodeStructuredErrorMapsToErrorAndDoneAndPreventsAgentExit() {
        OpenCodeStreamMapper m = new OpenCodeStreamMapper("opencode");
        String line = "{\"type\":\"error\",\"timestamp\":1773090724838,\"sessionID\":\"ses_01\",\"error\":{\"name\":\"ProviderModelNotFoundError\",\"data\":{\"message\":\"Model not found: qwen/qwen3.8-flash\",\"provider\":\"qwen\",\"model\":\"qwen3.8-flash\"}}}";
        List<AgentEvent> events = m.map(line);
        assertThat(events).extracting(AgentEvent::type).containsExactly("error", "done");
        AgentEvent.Error err = (AgentEvent.Error) events.get(0);
        assertThat(err.code()).isEqualTo("ProviderModelNotFoundError");
        assertThat(err.message()).isEqualTo("Model not found: qwen/qwen3.8-flash");
        assertThat(((AgentEvent.Done) events.get(1)).stopReason()).isEqualTo("error");

        assertThat(m.finish(1, false)).isEmpty();
    }

    @Test
    void opencodeFinishIncludesStderrWhenExitingWithError() {
        OpenCodeStreamMapper m = new OpenCodeStreamMapper("opencode");
        List<AgentEvent> events = m.finish(1, false, "syntax error in command");
        assertThat(events).extracting(AgentEvent::type).containsExactly("error", "done");
        assertThat(((AgentEvent.Error) events.get(0)).message()).isEqualTo("opencode ended with exit code 1: syntax error in command");
    }

    @Test
    void emptyThinkingAndTextBlocksProduceNoEvent() {
        ClaudeStreamMapper m = new ClaudeStreamMapper("claude-code");
        List<AgentEvent> events = m.map("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"abc\"},{\"type\":\"text\",\"text\":\"hi\"}]}}");
        assertThat(events).extracting(AgentEvent::type).containsExactly("text");
        assertThat(m.map("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"\"},{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{}}]}}"))
                .extracting(AgentEvent::type).containsExactly("tool_start");
    }
}
