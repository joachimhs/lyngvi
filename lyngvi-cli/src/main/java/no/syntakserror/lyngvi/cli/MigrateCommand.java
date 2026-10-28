package no.syntakserror.lyngvi.cli;

import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.contract.MigrateReport;
import no.syntakserror.lyngvi.core.migrate.Migrate112;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi migrate}: convert a 1.1.2 sandbox to this layout (spec §12). */
@Command(name = "migrate", description = "Convert a 1.1.2 sandbox in place: config, profile pack, gateway manifest, conversations, sessions.")
final class MigrateCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Override
    public Integer call() {
        Context ctx = main.context();
        MigrateReport report = Migrate112.run(ctx.sandboxDir(), ctx.platform(), ctx.runner());
        if (ctx.json()) {
            ctx.printJson(report);
            return 0;
        }
        if (report.alreadyMigrated()) {
            ctx.out().println("Already migrated (profile pack " + report.profilePack() + ").");
            return 0;
        }
        ctx.out().println("Migrated 1.1.2 sandbox: " + report.migratedConversations() + " conversation(s), " + report.migratedSessions() + " session(s), "
                + report.gatewayInstances() + " gateway instance(s); profile pack " + report.profilePack());
        ctx.out().println("Next steps:");
        for (String step : report.manualSteps()) ctx.out().println("  - " + step);
        return 0;
    }
}
