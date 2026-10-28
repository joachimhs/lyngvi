package no.syntakserror.lyngvi.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.version.Version;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;

class MainTest {

    private record Run(int exit, String out, String err) {}

    /** A subcommand that fails the way core services fail; registered by the test only. */
    @Command(name = "fail-for-test")
    static final class FailForTest implements Runnable {
        @Override
        public void run() {
            throw new SandboxException(ErrorCode.NOT_INITIALIZED, "test failure", Map.of("dir", "/nowhere"));
        }
    }

    private static Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = Main.commandLine();
        cmd.addSubcommand(new FailForTest());
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        int exit = cmd.execute(args);
        return new Run(exit, out.toString(), err.toString());
    }

    @Test
    void versionPrintsProductNameAndVersion() {
        Run r = run("version");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEqualTo("lyngvi " + Version.current() + System.lineSeparator());
        assertThat(r.err()).isEmpty();
    }

    @Test
    void versionJsonPrintsTheEnvelopeOnly() {
        Run r = run("--json", "version");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEqualTo("{\"ok\":true,\"version\":\"" + Version.current() + "\"}" + System.lineSeparator());
        assertThat(r.err()).isEmpty();
    }

    @Test
    void jsonFlagIsAcceptedAfterTheSubcommandToo() {
        Run r = run("version", "--json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).startsWith("{\"ok\":true");
    }

    @Test
    void unknownCommandExitsWithInvalidArgument() {
        Run r = run("frobnicate");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("frobnicate");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void noArgumentsShowsUsageAndExitsWithInvalidArgument() {
        Run r = run();

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Usage: lyngvi");
    }

    @Test
    void sandboxExceptionBecomesAJsonErrorEnvelopeAndItsExitCode() {
        Run r = run("--json", "fail-for-test");

        assertThat(r.exit()).isEqualTo(3);
        assertThat(r.out()).isEqualTo(
                "{\"ok\":false,\"error\":{\"code\":\"NOT_INITIALIZED\",\"message\":\"test failure\","
                        + "\"data\":{\"dir\":\"/nowhere\"}}}" + System.lineSeparator());
        assertThat(r.err()).isEmpty();
    }

    @Test
    void sandboxExceptionWithoutJsonGoesToStderrWithItsExitCode() {
        Run r = run("fail-for-test");

        assertThat(r.exit()).isEqualTo(3);
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).isEqualTo("lyngvi: test failure" + System.lineSeparator());
    }
}
