package no.syntakserror.lyngvi.core.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import no.syntakserror.lyngvi.core.agents.LocalModelConfig;
import no.syntakserror.lyngvi.core.ask.AdapterRegistry;
import no.syntakserror.lyngvi.core.ask.AgentAdapter;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.ask.AgentUsage;
import no.syntakserror.lyngvi.core.ask.AuthStatus;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.contract.AgentInfo;
import no.syntakserror.lyngvi.core.contract.AgentUsageReport;
import no.syntakserror.lyngvi.core.session.ConversationStore;

/** {@code agent list|status|models|login|logout|usage}. */
public final class AgentService {

    private final Sandbox sandbox;
    private final AdapterRegistry registry;
    private final no.syntakserror.lyngvi.core.ask.AgentContext ctx;

    AgentService(Sandbox sandbox) {
        this.sandbox = sandbox;
        this.ctx = new no.syntakserror.lyngvi.core.ask.AgentContext(sandbox.paths(), sandbox.platform(), sandbox.runner(),
                sandbox.config().subnetPrefix().orElse(null), no.syntakserror.lyngvi.core.version.Version.current().toString(),
                sandbox.sessionVolumes());
        this.registry = new AdapterRegistry(ctx);
    }

    public AdapterRegistry registry() {
        return registry;
    }

    /** Every agent the profile enables plus every adapter present, with availability and login state. */
    public List<AgentInfo> list() {
        Set<String> ids = new LinkedHashSet<>(sandbox.profile().agents().enabled());
        ids.addAll(registry.ids());
        for (AgentDescriptor d : AgentDescriptor.readAll(sandbox.paths().agents())) ids.add(d.id());
        List<AgentInfo> out = new ArrayList<>();
        for (String id : ids) {
            Optional<AgentAdapter> adapter = registry.find(id);
            boolean enabled = sandbox.profile().agents().enabled().contains(id);
            String label = adapter.map(a -> a.descriptor().label())
                    .or(() -> AgentDescriptor.find(sandbox.paths().agents(), id).map(AgentDescriptor::label)).orElse(id);
            AuthStatus auth = adapter.map(this::safeAuth).orElse(null);
            // Whether the agent can use a server of your own at all is the descriptor's business; how many models
            // are configured is the configuration file's. The front-ends need both: one decides whether to offer.
            AgentDescriptor descriptor = adapter.map(AgentAdapter::descriptor).orElse(null);
            boolean canUseLocalModels = descriptor != null && descriptor.localModels() != null;
            int localModels = descriptor == null ? 0 : new LocalModelConfig(ctx).configured(descriptor).size();
            out.add(new AgentInfo(id, label, adapter.isPresent(), enabled,
                    auth != null && auth.loggedIn(), auth == null ? null : auth.mode(), auth == null ? null : auth.account(),
                    canUseLocalModels, localModels));
        }
        return out;
    }

    private AuthStatus safeAuth(AgentAdapter a) {
        try {
            return a.authStatus();
        } catch (RuntimeException e) {
            return new AuthStatus(false, "unknown", null, null);
        }
    }

    public AgentAdapter adapter(String id) {
        return registry.find(id).orElseThrow(() -> new SandboxException(ErrorCode.VALIDATION_FAILED,
                "No adapter for agent '" + id + "' (built-in or agents/" + id + "/adapter.sh)", Map.of("agent", id)));
    }

    public AuthStatus status(String id) {
        return adapter(id).authStatus();
    }

    public List<Model> models(String id) {
        return models(id, false);
    }

    /** With {@code refresh} the agent's own catalogue is re-read instead of the one cached under {@code state/agents/<id>/}. */
    public List<Model> models(String id, boolean refresh) {
        return adapter(id).models(refresh);
    }

    // ---- models on the developer's own machine (agent.json localModels) ----

    /** The provider block as configured (with the descriptor's default when there is none yet) and its key state. */
    public LocalModelConfig.State localModels(String id) {
        return new LocalModelConfig(ctx).state(descriptor(id));
    }

    /** Writes the provider block and drops the cached catalogue, so the models are offered right away. */
    public void saveLocalModels(String id, LocalModelConfig.Settings settings) {
        new LocalModelConfig(ctx).save(descriptor(id), settings);
    }

    /** Stores the local model server's API key with the agent's other credentials; a blank key removes it. */
    public void setLocalModelApiKey(String id, String key) {
        new LocalModelConfig(ctx).setApiKey(descriptor(id), key);
    }

    /** Asks the server itself which models it has, from a throwaway container on the internal network.
     *  {@code apiKey} tests a key that is not stored yet; null uses the stored one. */
    public LocalModelConfig.Probe probeLocalModels(String id, String baseUrl, String apiKey) {
        return new LocalModelConfig(ctx).probe(descriptor(id), baseUrl, apiKey);
    }

    private AgentDescriptor descriptor(String id) {
        return AgentDescriptor.find(sandbox.paths().agents(), id)
                .orElseThrow(() -> new SandboxException(ErrorCode.VALIDATION_FAILED,
                        "No agents/" + id + "/agent.json", Map.of("agent", id)));
    }

    public int login(String id, String mode, Consumer<String> onLine) {
        return adapter(id).login(mode, onLine);
    }

    /**
     * Opens the agent's interactive login in a new terminal window (GUI use; TUI flows cannot run inside a dialog).
     * Returns empty when the flow is not interactive: then {@link #login} streams its lines instead.
     */
    public Optional<no.syntakserror.lyngvi.core.runtime.TerminalLauncher.Launched> loginInTerminal(String id, String mode) {
        AgentAdapter a = adapter(id);
        Optional<List<String>> cmd = a.loginCommand(mode);
        if (cmd.isEmpty()) return Optional.empty();
        var launcher = new no.syntakserror.lyngvi.core.runtime.TerminalLauncher(sandbox.platform(), sandbox.runner());
        return Optional.of(launcher.open("login-" + id, cmd.get(), sandbox.paths().root(), "lyngvi login: " + a.descriptor().label()));
    }

    public void logout(String id) {
        adapter(id).logout();
    }

    public AgentUsage usage(String id) {
        return adapter(id).usage();
    }

    /**
     * What the agent's account reports plus what this sandbox has spent on it locally.
     * Works for an agent without an adapter too: the account part is then simply empty.
     */
    public AgentUsageReport usageReport(String id) {
        Optional<AgentAdapter> adapter = registry.find(id);
        String label = adapter.map(a -> a.descriptor().label())
                .or(() -> AgentDescriptor.find(sandbox.paths().agents(), id).map(AgentDescriptor::label)).orElse(id);
        AgentUsage account = adapter.map(this::safeUsage).orElseGet(AgentUsage::unsupported);
        return AgentUsageReport.of(id, label, account, new ConversationStore(sandbox.paths()).list());
    }

    private AgentUsage safeUsage(AgentAdapter a) {
        try {
            return a.usage();
        } catch (RuntimeException e) {
            return AgentUsage.unsupported(e.getMessage());
        }
    }
}
