package no.syntakserror.lyngvi.core.ask;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One event of an agent run (spec §5.5). Adapters produce events without
 * {@code ts}/{@code seq}; the core stamps them with {@link #withMeta}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AgentEvent.Start.class, name = "start"),
    @JsonSubTypes.Type(value = AgentEvent.Text.class, name = "text"),
    @JsonSubTypes.Type(value = AgentEvent.Reasoning.class, name = "reasoning"),
    @JsonSubTypes.Type(value = AgentEvent.ToolStart.class, name = "tool_start"),
    @JsonSubTypes.Type(value = AgentEvent.ToolEnd.class, name = "tool_end"),
    @JsonSubTypes.Type(value = AgentEvent.Notice.class, name = "notice"),
    @JsonSubTypes.Type(value = AgentEvent.Usage.class, name = "usage"),
    @JsonSubTypes.Type(value = AgentEvent.Error.class, name = "error"),
    @JsonSubTypes.Type(value = AgentEvent.Done.class, name = "done")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface AgentEvent permits AgentEvent.Start, AgentEvent.Text, AgentEvent.Reasoning, AgentEvent.ToolStart,
        AgentEvent.ToolEnd, AgentEvent.Notice, AgentEvent.Usage, AgentEvent.Error, AgentEvent.Done {

    /** The wire name: {@code start}, {@code text}, ... */
    @JsonIgnore
    String type();

    Long ts();

    Long seq();

    AgentEvent withMeta(long ts, long seq);

    record Start(String agent, String agentSessionId, String model, Long ts, Long seq) implements AgentEvent {
        public String type() { return "start"; }
        public AgentEvent withMeta(long t, long s) { return new Start(agent, agentSessionId, model, t, s); }
    }

    record Text(String delta, Long ts, Long seq) implements AgentEvent {
        public String type() { return "text"; }
        public AgentEvent withMeta(long t, long s) { return new Text(delta, t, s); }
    }

    record Reasoning(String delta, Long ts, Long seq) implements AgentEvent {
        public String type() { return "reasoning"; }
        public AgentEvent withMeta(long t, long s) { return new Reasoning(delta, t, s); }
    }

    record ToolStart(String id, String name, String input, Long ts, Long seq) implements AgentEvent {
        public String type() { return "tool_start"; }
        public AgentEvent withMeta(long t, long s) { return new ToolStart(id, name, input, t, s); }
    }

    record ToolEnd(String id, String output, boolean isError, Long ts, Long seq) implements AgentEvent {
        public String type() { return "tool_end"; }
        public AgentEvent withMeta(long t, long s) { return new ToolEnd(id, output, isError, t, s); }
    }

    record Notice(String level, String text, Long ts, Long seq) implements AgentEvent {
        public String type() { return "notice"; }
        public AgentEvent withMeta(long t, long s) { return new Notice(level, text, t, s); }
    }

    record Usage(long input, long output, long cacheRead, long cacheWrite, Double costUsd, Long contextUsed, Long contextMax,
                 Long ts, Long seq) implements AgentEvent {
        public String type() { return "usage"; }
        public AgentEvent withMeta(long t, long s) { return new Usage(input, output, cacheRead, cacheWrite, costUsd, contextUsed, contextMax, t, s); }
    }

    record Error(String code, String message, Long ts, Long seq) implements AgentEvent {
        public String type() { return "error"; }
        public AgentEvent withMeta(long t, long s) { return new Error(code, message, t, s); }
    }

    record Done(String stopReason, Long ts, Long seq) implements AgentEvent {
        public String type() { return "done"; }
        public AgentEvent withMeta(long t, long s) { return new Done(stopReason, t, s); }
    }

    static Start start(String agent, String agentSessionId, String model) { return new Start(agent, agentSessionId, model, null, null); }
    static Text text(String delta) { return new Text(delta, null, null); }
    static Reasoning reasoning(String delta) { return new Reasoning(delta, null, null); }
    static ToolStart toolStart(String id, String name, String input) { return new ToolStart(id, name, input, null, null); }
    static ToolEnd toolEnd(String id, String output, boolean isError) { return new ToolEnd(id, output, isError, null, null); }
    static Notice notice(String level, String text) { return new Notice(level, text, null, null); }
    static Usage usage(long in, long out, long cacheRead, long cacheWrite, Double costUsd, Long contextUsed, Long contextMax) {
        return new Usage(in, out, cacheRead, cacheWrite, costUsd, contextUsed, contextMax, null, null);
    }
    static Error error(String code, String message) { return new Error(code, message, null, null); }
    static Done done(String stopReason) { return new Done(stopReason, null, null); }
}
