package no.syntakserror.lyngvi.cli;

import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.UpgradeReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi upgrade}: refresh the bundled files to this version and re-render. */
@Command(name = "upgrade", description = "Bring the sandbox's bundled files (images, proxy, templates, agents, gateway) up to this version and re-render.")
final class UpgradeCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Override
    public Integer call() {
        Context ctx = main.context();
        Sandbox sandbox = ctx.open();
        UpgradeReport report = sandbox.sandboxService().upgrade();
        CliInstaller.install(sandbox.paths().root(), CliInstaller.ownJar());
        if (ctx.json()) {
            ctx.printJson(report);
            return 0;
        }
        var m = ctx.messages(sandbox);
        if (report.updated().isEmpty()) {
            ctx.out().println(m.get("upgrade.uptodate", report.to()));
        } else {
            ctx.out().println(m.get("upgrade.done", report.from() == null ? "?" : report.from(), report.to(), report.updated().size()));
            for (String f : report.updated()) ctx.out().println("  " + f);
            ctx.out().println(m.get("upgrade.hint"));
        }
        return 0;
    }
}
