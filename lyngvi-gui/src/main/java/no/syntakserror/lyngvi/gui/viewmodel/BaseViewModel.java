package no.syntakserror.lyngvi.gui.viewmodel;

import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import no.syntakserror.lyngvi.core.api.Sandbox;

/** Shared plumbing: the sandbox context, async execution, the app log, an error handler and a busy flag. */
public abstract class BaseViewModel {

    protected final SandboxContext ctx;
    protected final Async async;
    protected final AppLog log;
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private volatile Consumer<Throwable> errorHandler;

    protected BaseViewModel(SandboxContext ctx, Async async, AppLog log) {
        this.ctx = ctx;
        this.async = async;
        this.log = log;
        this.errorHandler = t -> log.error("unhandled", t);
    }

    public void setErrorHandler(Consumer<Throwable> handler) {
        this.errorHandler = handler;
    }

    public BooleanProperty busyProperty() {
        return busy;
    }

    protected Sandbox sandbox() {
        return ctx.sandbox();
    }

    protected void fail(Throwable t) {
        log.error(t.getMessage() == null ? t.toString() : t.getMessage(), null);
        errorHandler.accept(t);
    }

    /** Runs {@code work} in the background with the busy flag set; the result lands on the UI thread. */
    protected <T> void call(Supplier<T> work, Consumer<T> onResult) {
        call(work, onResult, this::fail);
    }

    protected <T> void call(Supplier<T> work, Consumer<T> onResult, Consumer<Throwable> onError) {
        busy.set(true);
        async.call(work, r -> {
            busy.set(false);
            onResult.accept(r);
        }, t -> {
            busy.set(false);
            onError.accept(t);
        });
    }

    protected void run(Runnable work, Runnable onDone) {
        call(() -> { work.run(); return null; }, r -> onDone.run());
    }
}
