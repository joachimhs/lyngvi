package no.syntakserror.lyngvi.core.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import no.syntakserror.lyngvi.core.ask.AgentContext;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;

/** Claude Code inside the session container (spec §6.3). Behaviour comes from {@code agents/claude-code/agent.json}. */
public final class ClaudeCodeAdapter extends ContainerCliAdapter {

    public static final String ID = "claude-code";

    public ClaudeCodeAdapter(AgentDescriptor descriptor, AgentContext ctx) {
        super(descriptor, ctx);
    }

    @Override
    protected StreamMapper newMapper() {
        return new ClaudeStreamMapper(descriptor.id());
    }

    /** {@code .credentials.json} → the subscription type (e.g. {@code max}) when present. */
    @Override
    protected String accountFrom(Path file) {
        try {
            JsonNode n = new ObjectMapper().readTree(Files.readString(file));
            JsonNode oauth = n.path("claudeAiOauth");
            if (oauth.isMissingNode()) return null;
            return oauth.path("subscriptionType").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected boolean fileHasCredentials(Path file) {
        try {
            return new ObjectMapper().readTree(Files.readString(file)).has("claudeAiOauth");
        } catch (Exception e) {
            return false;
        }
    }
}
