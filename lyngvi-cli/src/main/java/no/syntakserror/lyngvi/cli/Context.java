package no.syntakserror.lyngvi.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.JsonOutput;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.Runner;

/** What every command needs: the resolved sandbox directory, output streams, platform and runner. */
final class Context {

    static final String HOME_ENV = "LYNGVI_HOME";

    private final Main main;
    private final Platform platform;
    private final Runner runner;
    private final java.io.InputStream stdin;

    Context(Main main, Platform platform, Runner runner) {
        this(main, platform, runner, System.in);
    }

    Context(Main main, Platform platform, Runner runner, java.io.InputStream stdin) {
        this.main = main;
        this.platform = platform;
        this.runner = runner;
        this.stdin = stdin;
    }

    java.io.InputStream stdin() {
        return stdin;
    }

    /** {@code --sandbox}, then {@code $LYNGVI_HOME}, then the working directory. */
    Path sandboxDir() {
        if (main.global().sandbox != null) {
            return main.global().sandbox.toAbsolutePath().normalize();
        }
        String home = platform.env().get(HOME_ENV);
        if (home != null && !home.isBlank()) {
            return Path.of(home).toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    Sandbox open() {
        return Sandbox.open(sandboxDir(), platform, runner);
    }

    Platform platform() {
        return platform;
    }

    Runner runner() {
        return runner;
    }

    boolean json() {
        return main.global().json;
    }

    PrintWriter out() {
        return main.out();
    }

    PrintWriter err() {
        return main.err();
    }

    /** Messages in the {@code --lang} language if given, else the sandbox's language, else English. */
    Messages messages(Sandbox sandbox) {
        String lang = main.global().lang;
        if (lang != null && !lang.isBlank()) return Messages.forLanguage(lang);
        return sandbox != null ? sandbox.messages() : Messages.forLanguage(null);
    }

    /** Prints the JSON envelope for a payload; used by every command in {@code --json} mode. */
    void printJson(Object payload) {
        out().println(JsonOutput.ok(payload));
    }
}
