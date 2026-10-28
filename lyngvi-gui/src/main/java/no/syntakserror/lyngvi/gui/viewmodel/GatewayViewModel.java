package no.syntakserror.lyngvi.gui.viewmodel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import no.syntakserror.lyngvi.core.contract.GatewayInstanceInfo;
import no.syntakserror.lyngvi.core.contract.GatewayPluginInfo;
import no.syntakserror.lyngvi.core.contract.SecretStatus;

/** The gateway: instances, plugins, secrets, allowlists, status, certificate (spec §8.8). */
public final class GatewayViewModel extends BaseViewModel {

    private final ObservableList<GatewayInstanceInfo> instances = FXCollections.observableArrayList();
    private final ObservableList<GatewayPluginInfo> plugins = FXCollections.observableArrayList();

    public GatewayViewModel(SandboxContext ctx, Async async, AppLog log) {
        super(ctx, async, log);
    }

    public ObservableList<GatewayInstanceInfo> instances() { return instances; }
    public ObservableList<GatewayPluginInfo> plugins() { return plugins; }

    public void refresh() {
        call(() -> Map.entry(sandbox().gatewayService().list(), sandbox().gatewayService().plugins()), e -> {
            instances.setAll(e.getKey());
            plugins.setAll(e.getValue());
        });
    }

    public void addInstance(String id, String plugin, String label, String hostname, List<String> aliases, String scheme, String mode, Map<String, Object> config) {
        call(() -> { sandbox().gatewayService().instanceAdd(id, plugin, label, hostname, aliases, scheme, mode, config); return sandbox().gatewayService().list(); },
                l -> { log.info("added gateway instance " + id); instances.setAll(l); });
    }

    public void removeInstance(String id) {
        call(() -> { sandbox().gatewayService().instanceRemove(id); return sandbox().gatewayService().list(); },
                l -> { log.info("removed gateway instance " + id); instances.setAll(l); });
    }

    public void secretSet(String id, String baseUrl, String token) {
        call(() -> { sandbox().gatewayService().secretSet(id, baseUrl, token); return sandbox().gatewayService().list(); },
                l -> { log.info("secret updated for " + id); instances.setAll(l); });
    }

    public void secretStatus(String id, Consumer<SecretStatus> onStatus) {
        call(() -> sandbox().gatewayService().secretStatus(id), onStatus);
    }

    public void secretClear(String id) {
        call(() -> { sandbox().gatewayService().secretClear(id); return sandbox().gatewayService().list(); }, instances::setAll);
    }

    public List<String> allowlist(String id) {
        return sandbox().gatewayService().allowList(id);
    }

    public void allowAdd(String id, String entry) {
        call(() -> { sandbox().gatewayService().allowAdd(id, entry); return sandbox().gatewayService().list(); }, instances::setAll);
    }

    public void allowRemove(String id, String entry) {
        call(() -> { sandbox().gatewayService().allowRemove(id, entry); return sandbox().gatewayService().list(); }, instances::setAll);
    }

    public void status(boolean deep, Consumer<Map<String, Object>> onStatus, Consumer<Throwable> onError) {
        call(() -> sandbox().gatewayService().status(deep), onStatus, onError);
    }

    public void restart() {
        run(() -> sandbox().gatewayService().restart(), () -> log.info("gateway restarted"));
    }

    public void rebuild(boolean noCache) {
        run(() -> sandbox().gatewayService().rebuild(noCache, line -> async.onUi(() -> log.info(line))), () -> log.info("gateway rebuilt"));
    }

    public void certRenew(Consumer<Path> onCert) {
        call(() -> sandbox().gatewayService().certRenew(), c -> { log.info("new gateway certificate " + c); onCert.accept(c); });
    }

    public void pluginInstall(Path jar) {
        call(() -> { sandbox().gatewayService().pluginInstall(jar); return sandbox().gatewayService().plugins(); },
                p -> { log.info("installed plugin " + jar.getFileName()); plugins.setAll(p); });
    }

    public void pluginRemove(String jarName) {
        call(() -> { sandbox().gatewayService().pluginRemove(jarName); return sandbox().gatewayService().plugins(); }, plugins::setAll);
    }
}
