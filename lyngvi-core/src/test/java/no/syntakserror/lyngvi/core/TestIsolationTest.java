package no.syntakserror.lyngvi.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A test that builds a {@link no.syntakserror.lyngvi.core.runtime.Platform} on the developer's real home
 * must redirect {@code XDG_STATE_HOME} as well. Without it the host-wide subnet registry is written by the
 * test run, and the temporary sandboxes it records squat prefixes forever.
 */
class TestIsolationTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    // The whole constructor call, however many lines it spans: everything up to the statement's semicolon.
    private static final Pattern PLATFORM = Pattern.compile("new Platform\\(([^;]*);", Pattern.DOTALL);

    @Test
    void noTestBuildsAPlatformOnTheRealHomeWithoutRedirectingTheStateDirectory() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String rel = REPO.relativize(p).toString();
                if (!rel.endsWith("Test.java") || !rel.contains("/src/test/")) continue;
                Matcher m = PLATFORM.matcher(Files.readString(p));
                while (m.find()) {
                    String call = m.group(1);
                    if (call.contains("user.home") && !call.contains("XDG_STATE_HOME")) {
                        offenders.add(rel + ": " + call.replaceAll("\\s+", " ").strip());
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
