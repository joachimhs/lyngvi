package no.syntakserror.lyngvi.gui.viewmodel;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.RepoEntry;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.config.ResourcePreset;
import no.syntakserror.lyngvi.core.contract.ProfileInfo;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.stack.BlockedRequest;
import no.syntakserror.lyngvi.core.stack.BlockedRequests;
import no.syntakserror.lyngvi.core.stack.DomainEntry;

/** Setup: repositories, domains, denied traffic, resources, profile, toolchains (spec §9.3). */
public final class ConfigViewModel extends BaseViewModel {

    private final ObservableList<RepoEntry> repos = FXCollections.observableArrayList();
    private final ObservableList<DomainEntry> domains = FXCollections.observableArrayList();
    private final ObservableList<BlockedRequest> blocked = FXCollections.observableArrayList();
    private final ObservableList<String> toolchains = FXCollections.observableArrayList();
    private final ObservableList<String> protectedBranches = FXCollections.observableArrayList();
    private final ObservableList<ResourcePreset> resourcePresets = FXCollections.observableArrayList();
    private final ObjectProperty<ResourceLimits> resources = new SimpleObjectProperty<>();
    private final ObjectProperty<ProfileInfo> profile = new SimpleObjectProperty<>();
    private final BooleanProperty followingBlocked = new SimpleBooleanProperty(false);
    private final BlockedRequests aggregate = new BlockedRequests();
    private volatile Runner.StreamHandle blockedStream;

    public ConfigViewModel(SandboxContext ctx, Async async, AppLog log) {
        super(ctx, async, log);
    }

    public ObservableList<RepoEntry> repos() { return repos; }
    public ObservableList<DomainEntry> domains() { return domains; }
    public ObservableList<BlockedRequest> blocked() { return blocked; }
    public ObservableList<String> toolchains() { return toolchains; }
    public ObservableList<String> protectedBranches() { return protectedBranches; }
    public ObjectProperty<ResourceLimits> resources() { return resources; }
    public ObservableList<ResourcePreset> resourcePresets() { return resourcePresets; }
    public ObjectProperty<ProfileInfo> profile() { return profile; }
    public BooleanProperty followingBlockedProperty() { return followingBlocked; }

    public void refreshAll() {
        refreshRepos();
        refreshDomains();
        refreshResources();
        refreshProfile();
        refreshToolchains();
        refreshProtectedBranches();
    }

    /**
     * Opens a port to the local model host in the profile and reloads the proxy — an explicit change to the egress
     * policy, so it is only ever done from a button that says so. Re-rendering runs a while: it belongs off the UI thread.
     */
    public void openLocalModelPort(int port, Consumer<List<Integer>> onOpen, Consumer<Throwable> onError) {
        call(() -> sandbox().configService().openLocalModelPort(port), ports -> {
            log.info("local model port " + port + " opened in the proxy (now " + ports + ")");
            onOpen.accept(ports);
        }, onError);
    }

    /** The egress policy for a model server, as the profile has it: which private host, and on which ports. */
    public String localModelHost() {
        return sandbox().configService().localModelHost();
    }

    public List<Integer> localModelPorts() {
        return sandbox().configService().localModelPorts();
    }

    /**
     * Lets one host out of the sandbox and reloads the proxy, then reports back. Widening the allowlist is a change
     * to the egress policy, so it is only ever done from a button that says so, and after the human has agreed.
     */
    public void allowDomain(String host, Runnable onAllowed, Consumer<Throwable> onError) {
        // DomainService.add reloads the proxy itself, so the host is out as soon as the list comes back.
        call(() -> { sandbox().domainService().add(host, Optional.empty()); return sandbox().domainService().list(); }, d -> {
            log.info("allowed " + host);
            domains.setAll(d);
            onAllowed.run();
        }, onError);
    }

    public void refreshProtectedBranches() {
        call(() -> sandbox().configService().protectedBranches(), protectedBranches::setAll);
    }

    /** Rewrites the sandbox's protected branch list; {@code onDone} runs after the profile is saved. */
    public void setProtectedBranches(List<String> branches, Runnable onDone) {
        call(() -> { sandbox().configService().setProtectedBranches(branches); return sandbox().configService().protectedBranches(); },
                b -> { log.info("protected branches: " + String.join(", ", b)); protectedBranches.setAll(b); onDone.run(); });
    }

    public void refreshRepos() {
        call(() -> sandbox().configService().repos(), repos::setAll);
    }

    public void refreshDomains() {
        call(() -> sandbox().domainService().list(), domains::setAll);
    }

    public void refreshResources() {
        call(() -> sandbox().configService().resources(), resources::set);
        // Inspecting the machine for the "this machine" preset takes a moment: it belongs off the UI thread, like this.
        call(() -> sandbox().configService().resourcePresets(), resourcePresets::setAll);
    }

    public void refreshProfile() {
        call(() -> sandbox().configService().profile(), profile::set);
    }

    public void refreshToolchains() {
        call(() -> sandbox().configService().toolchains(), toolchains::setAll);
    }

    public void addRepo(Path path, Optional<String> name, Optional<History> history) {
        call(() -> { sandbox().configService().addRepo(path, name, history); return sandbox().configService().repos(); },
                r -> { log.info("registered repository " + path); repos.setAll(r); });
    }

    public void removeRepo(String pathOrName) {
        call(() -> { sandbox().configService().removeRepo(pathOrName); return sandbox().configService().repos(); },
                r -> { log.info("removed repository " + pathOrName); repos.setAll(r); });
    }

    public void addDomain(String domain, Optional<String> note) {
        call(() -> { sandbox().domainService().add(domain, note); return sandbox().domainService().list(); },
                d -> { log.info("allowed domain " + domain); domains.setAll(d); });
    }

    public void removeDomain(String domain) {
        call(() -> { sandbox().domainService().remove(domain); return sandbox().domainService().list(); },
                d -> { log.info("removed domain " + domain); domains.setAll(d); });
    }

    public void reloadProxy(Consumer<Boolean> onResult) {
        call(() -> sandbox().domainService().reload(), onResult);
    }

    public void saveResources(ResourceLimits partial) {
        call(() -> sandbox().configService().setResources(partial), r -> { log.info("resource limits saved"); resources.set(r); });
    }

    /** The toolchains baked into the built base image, or empty when it is not built. */
    public void imageToolchains(Consumer<Optional<List<String>>> onResult) {
        call(() -> sandbox().stackService().imageToolchains(), onResult, t -> onResult.accept(Optional.empty()));
    }

    public List<String> availableToolchains() {
        return sandbox().configService().availableToolchains();
    }

    /** Tooltip text per toolchain, from the fragments' header lines; toolchains without one are absent. */
    public java.util.Map<String, String> toolchainDescriptions() {
        return sandbox().configService().toolchainDescriptions();
    }

    public void setToolchains(List<String> selected) {
        setToolchains(selected, () -> {});
    }

    /** Rewrites the profile's toolchain list; {@code onDone} runs when the save has landed, whether or not it succeeded. */
    public void setToolchains(List<String> selected, Runnable onDone) {
        call(() -> { sandbox().configService().setToolchains(selected); return sandbox().configService().toolchains(); },
                t -> { log.info("toolchains: " + String.join(", ", t)); toolchains.setAll(t); onDone.run(); },
                t -> { fail(t); onDone.run(); });
    }

    /** One pass over the proxy log. */
    public void refreshBlocked() {
        call(() -> sandbox().stackService().blocked(2000), blocked::setAll);
    }

    /** Allows a denied host, drops it from the list and refreshes the domains. */
    public void allowBlocked(String host) {
        call(() -> { sandbox().domainService().add(host, Optional.empty()); sandbox().domainService().reload(); return sandbox().domainService().list(); }, d -> {
            log.info("allowed " + host);
            domains.setAll(d);
            blocked.removeIf(b -> b.host().equals(host));
            aggregate.remove(host);
        });
    }

    public void startFollowingBlocked() {
        stopFollowingBlocked();
        try {
            blockedStream = sandbox().stackService().followBlocked(200, denied -> async.onUi(() -> {
                aggregate.add(denied);
                blocked.setAll(aggregate.snapshot());
            }));
            followingBlocked.set(true);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    public void stopFollowingBlocked() {
        Runner.StreamHandle h = blockedStream;
        if (h != null) {
            h.cancel();
            blockedStream = null;
        }
        followingBlocked.set(false);
    }
}
