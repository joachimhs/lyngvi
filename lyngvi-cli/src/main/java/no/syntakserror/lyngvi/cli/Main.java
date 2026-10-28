package no.syntakserror.lyngvi.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.contract.JsonOutput;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.ProcessRunner;
import no.syntakserror.lyngvi.core.runtime.Runner;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Entry point of {@code lyngvi-cli}.
 *
 * <p>The CLI is a thin front-end: every subcommand maps its arguments to a
 * call into {@code lyngvi-core} and prints either human text or the JSON
 * envelope. Errors are {@link SandboxException}s from the core; their
 * {@link ErrorCode} decides the exit code.
 */
@Command(
        name = "lyngvi",
        mixinStandardHelpOptions = true,
        subcommands = {
            VersionCommand.class, InitCommand.class, StatusCommand.class, DoctorCommand.class,
            RebuildConfigCommand.class, UpgradeCommand.class, MigrateCommand.class, BuildCommand.class, UpCommand.class, DownCommand.class, LogsCommand.class,
            RepoCommand.class, DomainCommand.class, ResourcesCommand.class, ProfileCommand.class,
            SessionCommand.class, ShellCommand.class, AgentCommand.class, SkillCommand.class, GatewayCommand.class, CleanCommand.class
        },
        description = "A hardened, agent-agnostic sandbox for autonomous coding agents.")
public final class Main implements Callable<Integer> {

    @Mixin
    private GlobalOptions global = new GlobalOptions();

    @Spec
    private CommandSpec spec;

    private Context context;

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /** Builds the command line against the real platform and process runner. */
    public static CommandLine commandLine() {
        Platform platform = Platform.current();
        return commandLine(platform, new ProcessRunner(platform));
    }

    /** Builds the command line with an injected platform and runner; tests use this. */
    public static CommandLine commandLine(Platform platform, Runner runner) {
        return commandLine(platform, runner, System.in);
    }

    /** As above, with an injected standard input (for {@code --token-stdin} and prompts on stdin). */
    public static CommandLine commandLine(Platform platform, Runner runner, java.io.InputStream stdin) {
        Main main = new Main();
        main.context = new Context(main, platform, runner, stdin);
        CommandLine cmd = new CommandLine(main);
        cmd.setExecutionExceptionHandler(main::handleException);
        return cmd;
    }

    /** Running the root command without a subcommand is a usage error. */
    @Override
    public Integer call() {
        spec.commandLine().usage(err());
        return ErrorCode.INVALID_ARGUMENT.exitCode();
    }

    GlobalOptions global() {
        return global;
    }

    Context context() {
        return context;
    }

    PrintWriter out() {
        return spec.commandLine().getOut();
    }

    PrintWriter err() {
        return spec.commandLine().getErr();
    }

    private int handleException(Exception ex, CommandLine failed, CommandLine.ParseResult parseResult) {
        SandboxException e = ex instanceof SandboxException se
                ? se
                : new SandboxException(ErrorCode.INTERNAL, String.valueOf(ex.getMessage()), ex);
        if (global.json) {
            out().println(JsonOutput.error(e));
        } else {
            err().println("lyngvi: " + e.getMessage());
        }
        return e.exitCode();
    }
}
