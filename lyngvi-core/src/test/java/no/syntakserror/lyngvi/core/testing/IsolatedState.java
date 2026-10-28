package no.syntakserror.lyngvi.core.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * A throwaway {@code XDG_STATE_HOME} for tests that otherwise build a {@code Platform} on the real home.
 * Keeps the host-wide subnet registry out of reach: a test run must never record its temporary sandboxes
 * there, because nothing ever releases those entries again.
 */
public final class IsolatedState {

    private static final String DIR = create();

    private IsolatedState() {}

    /** One directory per JVM, so every test in the run shares it and none of them touch the real one. */
    public static String dir() {
        return DIR;
    }

    private static String create() {
        try {
            return Files.createTempDirectory("lyngvi-test-state").toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
