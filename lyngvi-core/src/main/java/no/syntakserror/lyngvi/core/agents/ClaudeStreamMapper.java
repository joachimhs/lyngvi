package no.syntakserror.lyngvi.core.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.ask.AgentEvent;

/**
 * Claude Code {@code --output-format stream-json} → events (spec §6.3).
 * Written for the version pinned in {@code agents/claude-code/agent.json}; verify on upgrade.
 *
 * <ul>
 *   <li>{@code system/init} → {@code start} (session id, model)</li>
 *   <li>{@code assistant} content blocks: {@code thinking} → {@code reasoning}, {@code text} → {@code text}, {@code tool_use} → {@code tool_start}</li>
 *   <li>{@code user} content {@code tool_result} → {@code tool_end}</li>
 *   <li>{@code result} → {@code usage} then {@code done} ({@code error} first when {@code is_error})</li>
 * </ul>
 */
final class ClaudeStreamMapper implements StreamMapper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String agentId;
    private boolean done;
    private String sessionId;

    ClaudeStreamMapper(String agentId) {
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
        switch (type) {
            case "system" -> {
                if (n.path("subtype").asText("").equals("init")) {
                    sessionId = n.path("session_id").asText(null);
                    out.add(AgentEvent.start(agentId, sessionId, n.path("model").asText(null)));
                }
            }
            case "assistant" -> {
                for (JsonNode block : n.path("message").path("content")) {
                    switch (block.path("type").asText("")) {
                        case "text" -> {
                            // An empty text block before tool calls carries nothing to show.
                            String text = block.path("text").asText("");
                            if (!text.isBlank()) out.add(AgentEvent.text(text));
                        }
                        case "thinking" -> {
                            // Redacted or summarised thinking arrives as an empty block with only a signature: nothing to show.
                            String thinking = block.path("thinking").asText("");
                            if (!thinking.isBlank()) out.add(AgentEvent.reasoning(thinking));
                        }
                        case "tool_use" -> out.add(AgentEvent.toolStart(block.path("id").asText(""), block.path("name").asText(""), block.path("input").toString()));
                        default -> { }
                    }
                }
            }
            case "user" -> {
                for (JsonNode block : n.path("message").path("content")) {
                    if (block.path("type").asText("").equals("tool_result")) {
                        out.add(AgentEvent.toolEnd(block.path("tool_use_id").asText(""), contentText(block.path("content")), block.path("is_error").asBoolean(false)));
                    }
                }
            }
            case "result" -> {
                JsonNode u = n.path("usage");
                out.add(AgentEvent.usage(u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0),
                        u.path("cache_read_input_tokens").asLong(0), u.path("cache_creation_input_tokens").asLong(0),
                        n.hasNonNull("total_cost_usd") ? n.path("total_cost_usd").asDouble() : null, null, null));
                String subtype = n.path("subtype").asText("success");
                if (n.path("is_error").asBoolean(false) || !subtype.equals("success")) {
                    out.add(AgentEvent.error(subtype, n.hasNonNull("result") ? n.path("result").asText() : subtype.replace('_', ' ')));
                    out.add(AgentEvent.done(subtype.contains("max_turns") ? "max_turns" : "error"));
                } else {
                    out.add(AgentEvent.done("end_turn"));
                }
                done = true;
            }
            default -> { }
        }
        return out;
    }

    static String contentText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if (part.path("type").asText("").equals("text")) sb.append(part.path("text").asText(""));
        }
        return sb.toString();
    }

    @Override
    public List<AgentEvent> finish(int exitCode, boolean cancelled) {
        if (done) return List.of();
        done = true;
        if (cancelled) return List.of(AgentEvent.done("cancelled"));
        return List.of(AgentEvent.error("AGENT_EXIT", "claude ended with exit code " + exitCode + " without a result"), AgentEvent.done("error"));
    }
}
