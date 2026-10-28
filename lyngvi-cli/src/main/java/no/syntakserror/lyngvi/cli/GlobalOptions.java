package no.syntakserror.lyngvi.cli;

import java.nio.file.Path;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * Options accepted before or after any subcommand.
 *
 * <p>Declared with {@link ScopeType#INHERIT} so {@code lyngvi --json version}
 * and {@code lyngvi version --json} mean the same thing.
 */
final class GlobalOptions {

    @Option(names = "--json", scope = ScopeType.INHERIT,
            description = "Machine-readable output on stdout; human text only on stderr.")
    boolean json;

    @Option(names = "--quiet", scope = ScopeType.INHERIT,
            description = "No progress messages on stderr.")
    boolean quiet;

    @Option(names = "--lang", scope = ScopeType.INHERIT, paramLabel = "<code>",
            description = "Override the UI language for this invocation (e.g. en, nb).")
    String lang;

    @Option(names = "--no-color", scope = ScopeType.INHERIT,
            description = "Never emit ANSI colour codes (default when stdout is not a terminal).")
    boolean noColor;

    @Option(names = "--yes", scope = ScopeType.INHERIT,
            description = "Answer yes to confirmations.")
    boolean yes;

    @Option(names = "--sandbox", scope = ScopeType.INHERIT, paramLabel = "<dir>",
            description = "Sandbox directory (default: $LYNGVI_HOME, then the working directory).")
    Path sandbox;
}
