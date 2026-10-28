package no.syntakserror.lyngvi.gui.viewmodel;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import no.syntakserror.lyngvi.core.agents.LocalModelConfig;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.contract.AgentInfo;
import no.syntakserror.lyngvi.core.contract.AgentUsageReport;

/** Agents and their credentials (spec §9.3 «Agenter»). */
public final class AgentsViewModel extends BaseViewModel {

    private final ObservableList<AgentInfo> agents = FXCollections.observableArrayList();

    public AgentsViewModel(SandboxContext ctx, Async async, AppLog log) {
        super(ctx, async, log);
    }

    public ObservableList<AgentInfo> agents() {
        return agents;
    }

    public void refresh() {
        call(() -> sandbox().agentService().list(), agents::setAll);
    }

    /** Runs the login flow; each output line (URLs, codes) arrives on the UI thread; {@code onExit} gets the exit code. */
    public void login(String id, String mode, Consumer<String> onLine, IntConsumer onExit) {
        call(() -> sandbox().agentService().login(id, mode, line -> async.onUi(() -> onLine.accept(line))), exit -> {
            log.info("login for " + id + " ended with " + exit);
            onExit.accept(exit);
            refresh();
        });
    }

    /** Opens the interactive login in a terminal window; {@code onResult} gets false when the flow is not interactive. */
    public void loginInTerminal(String id, String mode, Consumer<Boolean> onResult) {
        call(() -> sandbox().agentService().loginInTerminal(id, mode).isPresent(), opened -> {
            log.info(opened ? "login for " + id + " opened in a terminal window" : "login for " + id + " is not interactive");
            onResult.accept(opened);
        });
    }

    public void status(String id, Consumer<no.syntakserror.lyngvi.core.ask.AuthStatus> onStatus) {
        call(() -> sandbox().agentService().status(id), onStatus, t -> onStatus.accept(new no.syntakserror.lyngvi.core.ask.AuthStatus(false, "unknown", null, null)));
    }

    public void logout(String id) {
        call(() -> { sandbox().agentService().logout(id); return sandbox().agentService().list(); }, a -> { log.info("logged out " + id); agents.setAll(a); });
    }

    public void models(String id, Consumer<List<Model>> onModels) {
        call(() -> sandbox().agentService().models(id), onModels, t -> onModels.accept(List.of()));
    }

    /** The agent's local model server as configured; an agent with no such notion fails, and the error dialog says so. */
    public void localModels(String id, Consumer<LocalModelConfig.State> onState) {
        call(() -> sandbox().agentService().localModels(id), onState);
    }

    /**
     * Saves the provider block and, when {@code apiKey} is not null, the server's key — blank removes it.
     * The key never comes back out: only {@link LocalModelConfig.State#apiKeySet()} says whether there is one.
     */
    public void saveLocalModels(String id, LocalModelConfig.Settings settings, String apiKey, Runnable onSaved) {
        call(() -> {
            if (apiKey != null) sandbox().agentService().setLocalModelApiKey(id, apiKey);
            sandbox().agentService().saveLocalModels(id, settings);
            return settings;
        }, saved -> {
            log.info("local models for " + id + ": " + saved.models().size() + " model(s) at " + saved.baseUrl()
                    + (apiKey == null ? "" : apiKey.isBlank() ? ", key removed" : ", key stored"));
            onSaved.run();
        });
    }

    /** Starts a container to ask the server itself, so it must not run on the UI thread. */
    public void probeLocalModels(String id, String baseUrl, String apiKey, Consumer<LocalModelConfig.Probe> onProbe, Consumer<Throwable> onError) {
        call(() -> sandbox().agentService().probeLocalModels(id, baseUrl, apiKey), onProbe, onError);
    }

    /** Account quotas plus the tokens this sandbox has spent on the agent; asking the agent can be slow, so it runs off the UI thread. */
    public void usageReport(String id, Consumer<AgentUsageReport> onReport, Consumer<Throwable> onError) {
        call(() -> sandbox().agentService().usageReport(id), onReport, onError);
    }
}
