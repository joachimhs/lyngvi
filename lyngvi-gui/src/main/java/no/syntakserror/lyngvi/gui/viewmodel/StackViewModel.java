package no.syntakserror.lyngvi.gui.viewmodel;

import java.util.function.Consumer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import no.syntakserror.lyngvi.core.contract.DoctorReport;
import no.syntakserror.lyngvi.core.render.RenderReport;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.stack.MachineInfo;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.CleanOptions;
import no.syntakserror.lyngvi.core.api.CleanupService;
import no.syntakserror.lyngvi.core.contract.CleanupReport;
import no.syntakserror.lyngvi.core.contract.DiskReport;
import no.syntakserror.lyngvi.core.contract.SessionContainerInfo;
import no.syntakserror.lyngvi.core.stack.StackStatus;

/** The shared stack: status, up/down, build, doctor, rebuild-config, log following (spec §9.2). */
public final class StackViewModel extends BaseViewModel {

    private final ObservableList<StackStatus.Service> services = FXCollections.observableArrayList();
    private final ObservableList<SessionContainerInfo> sessionContainers = FXCollections.observableArrayList();
    private final StringProperty prefix = new SimpleStringProperty("");
    private final BooleanProperty logsRunning = new SimpleBooleanProperty(false);
    private volatile Runner.StreamHandle logStream;

    public StackViewModel(SandboxContext ctx, Async async, AppLog log) {
        super(ctx, async, log);
    }

    public ObservableList<StackStatus.Service> services() {
        return services;
    }

    /** Every session container Podman holds for the sandbox, orphans included (spec §5.3). */
    public ObservableList<SessionContainerInfo> sessionContainers() {
        return sessionContainers;
    }

    public StringProperty prefixProperty() {
        return prefix;
    }

    public BooleanProperty logsRunningProperty() {
        return logsRunning;
    }

    private void apply(StackStatus status) {
        prefix.set(status.prefix() == null ? "" : status.prefix());
        services.setAll(status.services());
    }

    public void refresh() {
        call(() -> Map.entry(sandbox().stackService().status(), sandbox().sessionService().containers()), e -> {
            apply(e.getKey());
            sessionContainers.setAll(e.getValue());
        });
    }

    /** Stops session containers idle longer than the profile's limit and removes orphan containers. */
    public void cleanup(Consumer<CleanupReport> onReport) {
        call(() -> sandbox().sessionService().cleanup(sandbox().profile().session().idleStopMinutes()), r -> {
            if (!r.stopped().isEmpty()) log.info("stopped idle session containers: " + String.join(", ", r.stopped()));
            if (!r.retired().isEmpty()) log.info("removed idle session containers: " + String.join(", ", r.retired()));
            if (!r.removed().isEmpty()) log.info("removed containers without a conversation: " + String.join(", ", r.removed()));
            onReport.accept(r);
            refresh();
        });
    }

    /** What the sandbox can reclaim: finished sessions, images of other versions, shared caches. */
    public void diskReport(Consumer<DiskReport> onReport) {
        call(() -> sandbox().cleanupService().report(CleanOptions.report()), onReport);
    }

    /** Removes what a report of the same options listed. The caller has shown the user that list first. */
    public void clean(CleanOptions options, Consumer<CleanupService.CleanResult> onDone) {
        call(() -> sandbox().cleanupService().clean(options), r -> {
            log.info("clean removed " + r.removed().size() + " object(s)");
            onDone.accept(r);
            refresh();
        });
    }

    public void removeOrphan(String container) {
        call(() -> { sandbox().sessionService().removeOrphan(container); return container; }, c -> { log.info("removed container " + c); refresh(); });
    }

    public void stopSession(String id) {
        call(() -> sandbox().sessionService().stop(id), info -> { log.info("stopped container " + info.container().name()); refresh(); });
    }

    public void up() {
        up(s -> {}, this::fail);
    }

    /** Starts the stack and hands the caller the status it ended in; {@code onError} keeps a wizard in charge of its own failure. */
    public void up(Consumer<StackStatus> onStarted, Consumer<Throwable> onError) {
        call(() -> sandbox().stackService().up(), s -> { log.info("stack started"); apply(s); onStarted.accept(s); }, onError);
    }

    public void down() {
        call(() -> sandbox().stackService().down(), s -> { log.info("stack stopped"); apply(s); });
    }

    public void build(boolean noCache) {
        build(noCache, () -> {});
    }

    public void build(boolean noCache, Runnable onDone) {
        build(noCache, line -> {}, onDone, this::fail);
    }

    /**
     * Builds with every output line handed to {@code onLine} as well as to the console, so a view can show the
     * build where the user is looking; {@code onError} keeps a wizard in charge of its own failure.
     */
    public void build(boolean noCache, Consumer<String> onLine, Runnable onDone, Consumer<Throwable> onError) {
        call(() -> sandbox().stackService().build(noCache, line -> async.onUi(() -> { log.info(line); onLine.accept(line); })),
                image -> { log.info("built " + image); onDone.run(); }, onError);
    }

    public void doctor(Consumer<DoctorReport> onReport) {
        doctor(onReport, this::fail);
    }

    public void doctor(Consumer<DoctorReport> onReport, Consumer<Throwable> onError) {
        call(() -> sandbox().sandboxService().doctor(), r -> {
            for (var c : r.checks()) {
                String line = c.id() + ": " + c.message() + (c.hint() == null || c.hint().isBlank() ? "" : " (" + c.hint() + ")");
                switch (c.level()) {
                    case OK -> log.info(line);
                    case WARN -> log.warn(line);
                    case FAIL -> log.error(line, null);
                }
            }
            onReport.accept(r);
        }, onError);
    }

    public void rebuildConfig() {
        rebuildConfig(r -> {});
    }

    public void rebuildConfig(Consumer<RenderReport> onReport) {
        rebuildConfig(onReport, this::fail);
    }

    public void rebuildConfig(Consumer<RenderReport> onReport, Consumer<Throwable> onError) {
        call(() -> sandbox().sandboxService().rebuildConfig(), r -> {
            log.info("configuration rendered (subnet " + r.subnetPrefix() + ".0/24): " + String.join(", ", r.written()));
            prefix.set(r.subnetPrefix());
            onReport.accept(r);
        }, onError);
    }

    /** Bundled files older than this app? Decides whether the upgrade button is shown. */
    public void upgradeNeeded(Consumer<Boolean> onResult) {
        call(() -> !no.syntakserror.lyngvi.core.template.Templates.stale(sandbox().paths().root()).isEmpty(), onResult, t -> onResult.accept(false));
    }

    public void upgrade(Consumer<no.syntakserror.lyngvi.core.contract.UpgradeReport> onReport) {
        call(() -> sandbox().sandboxService().upgrade(), r -> {
            log.info("upgraded sandbox to " + r.to() + ": " + String.join(", ", r.updated()));
            onReport.accept(r);
        });
    }

    public void machine(Consumer<MachineInfo> onInfo) {
        call(() -> sandbox().stackService().machine(), onInfo);
    }

    /** Follows a service log until {@link #stopLogs()}; lines arrive on the UI thread. */
    public void startLogs(String service, Consumer<String> onLine) {
        stopLogs();
        try {
            logStream = sandbox().stackService().streamLogs(service, 200, line -> async.onUi(() -> onLine.accept(line)));
            logsRunning.set(true);
            Runner.StreamHandle h = logStream;
            Thread.ofVirtual().start(() -> {
                h.waitFor();
                async.onUi(() -> { if (logStream == h) { logsRunning.set(false); logStream = null; } });
            });
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    public void stopLogs() {
        Runner.StreamHandle h = logStream;
        if (h != null) {
            h.cancel();
            logStream = null;
        }
        logsRunning.set(false);
    }
}
