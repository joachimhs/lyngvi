package no.syntakserror.lyngvi.gui.viewmodel;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.InitOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.contract.InitReport;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.Runner;

/** The one open sandbox (or none). Viewmodels read the core through it; listeners hear when it changes. */
public final class SandboxContext {

    private final Platform platform;
    private final Runner runner;
    private volatile Sandbox sandbox;
    private volatile SandboxException lastError;
    private volatile Messages messages = Messages.forLanguage(null);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public SandboxContext(Platform platform, Runner runner) {
        this.platform = platform;
        this.runner = runner;
    }

    public Platform platform() {
        return platform;
    }

    public Runner runner() {
        return runner;
    }

    public boolean isOpen() {
        return sandbox != null;
    }

    /** The open sandbox; throws {@code NOT_INITIALIZED} when none is open. */
    public Sandbox sandbox() {
        Sandbox s = sandbox;
        if (s == null) {
            throw new SandboxException(ErrorCode.NOT_INITIALIZED, "No sandbox is open");
        }
        return s;
    }

    public Optional<Path> dir() {
        Sandbox s = sandbox;
        return s == null ? Optional.empty() : Optional.of(s.paths().root());
    }

    public Messages messages() {
        return messages;
    }

    public SandboxException lastError() {
        return lastError;
    }

    /** Opens a sandbox directory; false (with {@link #lastError()}) when it is not initialised. */
    public synchronized boolean open(Path dir) {
        try {
            Sandbox s = Sandbox.open(dir, platform, runner);
            set(s);
            return true;
        } catch (SandboxException e) {
            lastError = e;
            return false;
        }
    }

    public synchronized InitReport init(Path dir, Optional<Path> profilePack, List<Path> repos, Optional<String> agent) {
        InitReport report = Sandbox.init(dir, new InitOptions(profilePack, repos, agent, false), platform, runner);
        set(Sandbox.open(dir, platform, runner));
        return report;
    }

    private void set(Sandbox s) {
        sandbox = s;
        lastError = null;
        messages = s.messages();
        for (Runnable l : listeners) l.run();
    }

    /** True when the directory is a 1.1.2 sandbox that {@link #migrate} can convert. */
    public boolean needsMigration(Path dir) {
        return no.syntakserror.lyngvi.core.migrate.Migrate112.needsMigration(dir);
    }

    /** Converts a 1.1.2 sandbox and opens it. */
    public synchronized no.syntakserror.lyngvi.core.contract.MigrateReport migrate(Path dir) {
        var report = no.syntakserror.lyngvi.core.migrate.Migrate112.run(dir, platform, runner);
        set(Sandbox.open(dir, platform, runner));
        return report;
    }

    /** Re-reads configuration and profile from disk. */
    public void reload() {
        Sandbox s = sandbox;
        if (s != null) s.reload();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }
}
