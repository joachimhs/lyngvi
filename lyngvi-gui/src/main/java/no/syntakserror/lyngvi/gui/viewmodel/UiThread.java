package no.syntakserror.lyngvi.gui.viewmodel;

/** Where UI updates run. The app binds it to the FX application thread; tests run it inline. */
@FunctionalInterface
public interface UiThread {
    void run(Runnable r);

    UiThread DIRECT = Runnable::run;

    /** Runs inline; tests use it, and viewmodels treat it as "no batching". */
    static UiThread direct() {
        return DIRECT;
    }
}
