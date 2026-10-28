package no.syntakserror.lyngvi.gui.viewmodel;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs core calls on a background executor and delivers the outcome on the UI thread (spec §9.1 point 3). */
public final class Async {

    private final UiThread ui;
    private final Executor background;

    public Async(UiThread ui, Executor background) {
        this.ui = ui;
        this.background = background;
    }

    public static Async virtualThreads(UiThread ui) {
        return new Async(ui, r -> Thread.ofVirtual().start(r));
    }

    public UiThread ui() {
        return ui;
    }

    public Executor background() {
        return background;
    }

    public <T> void call(Supplier<T> work, Consumer<T> onResult, Consumer<Throwable> onError) {
        background.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (Throwable t) {
                ui.run(() -> onError.accept(t));
                return;
            }
            ui.run(() -> onResult.accept(result));
        });
    }

    public void run(Runnable work, Runnable onDone, Consumer<Throwable> onError) {
        call(() -> { work.run(); return null; }, r -> onDone.run(), onError);
    }

    /** Marshals a value to the UI thread. */
    public void onUi(Runnable r) {
        ui.run(r);
    }
}
