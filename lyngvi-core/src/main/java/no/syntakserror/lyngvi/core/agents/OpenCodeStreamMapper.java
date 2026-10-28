package no.syntakserror.lyngvi.core.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.syntakserror.lyngvi.core.ask.AgentEvent;

/**
 * OpenCode {@code run --format json} → events (spec §6.4). Tolerant of two shapes: the
 * event-bus form ({@code type} + {@code properties.part / properties.info}) and flat objects
 * ({@code type: text/tool/error}). Written for the pinned version; verify on upgrade.
 *
 * <p>What {@code --format json} actually prints in 1.18.29 is the flat form: one line per finished part,
 * {@code {"type":"text|tool_use|step_start|step_finish","timestamp":…,"sessionID":…,"part":{…}}}, plus
 * {@code {"type":"error","error":{…}}}. A text part is printed once, complete, when its block ends — never
 * as deltas — and an empty one is printed for every step whose model went straight to its tool calls.
 * There is no session or message line, so the start event comes from the first part and the usage branch
 * below is for the event-bus form only ({@code capabilities.usage} is false for this agent).</p>
 */
final class OpenCodeStreamMapper implements StreamMapper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String agentId;
    private final Set<String> runningTools = new HashSet<>();
    private boolean started;
    private boolean done;

    OpenCodeStreamMapper(String agentId) {
        this.agentId = agentId;
    }

    @Override
    public List<AgentEvent> map(String line) {
        List<AgentEvent> out = new ArrayList<>();
        if (line == null || !line.stripLeading().startsWith("{")) return out;
        JsonNode n;
        try {
            n = JSON.readTree(line);
        } catch (Exception e) {
            return out;
        }
        String type = n.path("type").asText("");
        JsonNode props = n.path("properties");
        if (type.startsWith("session") && props.path("info").has("id")) {
            if (!started) {
                started = true;
                out.add(AgentEvent.start(agentId, props.path("info").path("id").asText(), n.path("model").asText(null)));
            }
            return out;
        }
        JsonNode part = props.has("part") ? props.path("part") : n.has("part") ? n.path("part") : null;
        if (part != null) {
            ensureStarted(out, part.path("sessionID").asText(null));
            switch (part.path("type").asText("")) {
                // opencode opens a text part when the model starts a text block and closes it when the block ends,
                // even when nothing was written — a step that went straight to its tool calls still prints a part
                // with an empty text. opencode's own printer trims and drops those; so do we, or every such step
                // leaves an empty section in the transcript.
                case "text" -> {
                    String text = part.path("text").asText("");
                    if (!text.isBlank()) out.add(AgentEvent.text(text));
                }
                case "reasoning" -> {
                    String text = part.path("text").asText("");
                    if (!text.isBlank()) out.add(AgentEvent.reasoning(text));
                }
                case "tool" -> {
                    String id = part.path("callID").asText(part.path("id").asText(""));
                    JsonNode state = part.path("state");
                    String status = state.path("status").asText("");
                    if (!runningTools.contains(id) && !status.equals("pending")) {
                        runningTools.add(id);
                        out.add(AgentEvent.toolStart(id, part.path("tool").asText(""), state.path("input").toString()));
                    }
                    if (status.equals("completed") || status.equals("error")) {
                        out.add(AgentEvent.toolEnd(id, state.path("output").asText(state.path("error").asText("")), status.equals("error")));
                        runningTools.remove(id);
                    }
                }
                default -> { }
            }
            return out;
        }
        JsonNode info = props.path("info");
        if (type.startsWith("message") && info.has("tokens")) {
            JsonNode t = info.path("tokens");
            out.add(AgentEvent.usage(t.path("input").asLong(0), t.path("output").asLong(0), t.path("cache").path("read").asLong(0),
                    t.path("cache").path("write").asLong(0), info.hasNonNull("cost") ? info.path("cost").asDouble() : null, null, null));
            if (info.has("finish")) {
                out.add(AgentEvent.done("end_turn"));
                done = true;
            }
            return out;
        }
        switch (type) {
            case "text" -> {
                String text = n.path("text").asText("");
                if (!text.isBlank()) out.add(AgentEvent.text(text));
            }
            case "error" -> {
                String code = "OPENCODE_ERROR";
                JsonNode errNode = n.path("error");
                if (n.hasNonNull("code")) {
                    code = n.path("code").asText("OPENCODE_ERROR");
                } else if (errNode.hasNonNull("name")) {
                    code = errNode.path("name").asText("OPENCODE_ERROR");
                }
                String message = "";
                if (n.hasNonNull("message")) {
                    message = n.path("message").asText("");
                } else if (errNode.path("data").hasNonNull("message")) {
                    message = errNode.path("data").path("message").asText("");
                } else if (errNode.isTextual()) {
                    message = errNode.asText("");
                } else if (!errNode.isMissingNode() && !errNode.isNull()) {
                    message = errNode.toString();
                }
                if (message.isBlank()) {
                    message = "OpenCode error (" + code + ")";
                }
                out.add(AgentEvent.error(code, message));
                out.add(AgentEvent.done("error"));
                done = true;
            }
            default -> { }
        }
        return out;
    }

    private void ensureStarted(List<AgentEvent> out, String sessionId) {
        if (!started) {
            started = true;
            out.add(AgentEvent.start(agentId, sessionId, null));
        }
    }

    @Override
    public List<AgentEvent> finish(int exitCode, boolean cancelled) {
        return finish(exitCode, cancelled, null);
    }

    @Override
    public List<AgentEvent> finish(int exitCode, boolean cancelled, String stderr) {
        if (done) return List.of();
        done = true;
        if (cancelled) return List.of(AgentEvent.done("cancelled"));
        if (exitCode == 0 && started) return List.of(AgentEvent.done("end_turn"));
        String msg = "opencode ended with exit code " + exitCode;
        if (stderr != null && !stderr.isBlank()) {
            msg += ": " + stderr.strip();
        }
        return List.of(AgentEvent.error("AGENT_EXIT", msg), AgentEvent.done("error"));
    }
}
