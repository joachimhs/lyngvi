package no.syntakserror.lyngvi.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.InitOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.InitReport;
import no.syntakserror.lyngvi.core.contract.RepoInfo;
import no.syntakserror.lyngvi.core.i18n.Messages;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi init}: create a sandbox directory. */
@Command(name = "init", description = "Initialise a sandbox directory from a profile pack.")
final class InitCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Option(names = "--profile", paramLabel = "<dir>", description = "Profile pack directory (default: the bundled default profile).")
    private Path profilePack;

    @Option(names = "--repo", paramLabel = "<path>", description = "Repository to register; repeatable.")
    private List<Path> repos = List.of();

    @Option(names = "--agent", paramLabel = "<id>", description = "Default agent (default: the profile's).")
    private String agent;

    @Override
    public Integer call() {
        Context ctx = main.context();
        InitOptions options = new InitOptions(Optional.ofNullable(profilePack), repos, Optional.ofNullable(agent), main.global().yes);
        InitReport report = Sandbox.init(ctx.sandboxDir(), options, ctx.platform(), ctx.runner());
        CliInstaller.install(ctx.sandboxDir(), CliInstaller.ownJar());
        if (ctx.json()) {
            ctx.printJson(report);
            return 0;
        }
        Messages m = ctx.messages(ctx.open());
        ctx.out().println(m.get("init.done", report.sandboxDir()));
        ctx.out().println(m.get("init.profile", report.profile().name(), report.profile().label()));
        for (RepoInfo r : report.repos()) {
            ctx.out().println(m.get("init.repo", r.path(), r.name()));
        }
        return 0;
    }
}
