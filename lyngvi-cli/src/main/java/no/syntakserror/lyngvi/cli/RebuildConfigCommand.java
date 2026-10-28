package no.syntakserror.lyngvi.cli;

import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.render.RenderReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi rebuild-config}: regenerate every generated file. */
@Command(name = "rebuild-config", description = "Regenerate Containerfile, docker-compose.yml, proxy configuration, AGENTS.md and the gateway certificate.")
final class RebuildConfigCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Override
    public Integer call() {
        Context ctx = main.context();
        Sandbox sandbox = ctx.open();
        RenderReport report = sandbox.sandboxService().rebuildConfig();
        if (ctx.json()) {
            ctx.printJson(report);
            return 0;
        }
        ctx.out().println(ctx.messages(sandbox).get("render.done", report.subnetPrefix()));
        for (String f : report.written()) {
            ctx.out().println("  " + f);
        }
        if (report.certificateGenerated()) {
            ctx.out().println(ctx.messages(sandbox).get("render.cert"));
        }
        return 0;
    }
}
