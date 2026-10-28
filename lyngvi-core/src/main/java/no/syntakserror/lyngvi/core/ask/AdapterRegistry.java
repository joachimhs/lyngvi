package no.syntakserror.lyngvi.core.ask;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.agents.ClaudeCodeAdapter;
import no.syntakserror.lyngvi.core.agents.MockAdapter;
import no.syntakserror.lyngvi.core.agents.OpenCodeAdapter;

/**
 * Built-in adapters plus external ones under {@code agents/<id>/adapter.sh}.
 * Built-ins driven by an {@code agent.json} (Claude Code, OpenCode) need that file in the sandbox;
 * an external adapter with the same id as a built-in one wins (spec §6.2).
 */
public final class AdapterRegistry {

    private final Map<String, AgentAdapter> adapters = new LinkedHashMap<>();

    public AdapterRegistry(AgentContext ctx) {
        adapters.put(MockAdapter.ID, new MockAdapter(ctx.volumes()));
        for (AgentDescriptor d : AgentDescriptor.readAll(ctx.paths().agents())) {
            if (Files.isRegularFile(d.dir().resolve("adapter.sh"))) {
                adapters.put(d.id(), new ProcessAdapter(d, ctx.runner()));
            } else if (d.id().equals(ClaudeCodeAdapter.ID)) {
                adapters.put(d.id(), new ClaudeCodeAdapter(d, ctx));
            } else if (d.id().equals(OpenCodeAdapter.ID)) {
                adapters.put(d.id(), new OpenCodeAdapter(d, ctx));
            }
        }
    }

    public List<String> ids() {
        return List.copyOf(adapters.keySet());
    }

    public Optional<AgentAdapter> find(String id) {
        return Optional.ofNullable(adapters.get(id));
    }

    public List<AgentAdapter> all() {
        return List.copyOf(adapters.values());
    }
}
