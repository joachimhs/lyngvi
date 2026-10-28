package no.syntakserror.lyngvi.cli;

import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.DoctorReport;
import no.syntakserror.lyngvi.core.doctor.Check;
import no.syntakserror.lyngvi.core.i18n.Messages;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi doctor}: preflight checks. Exit 1 when anything fails. */
@Command(name = "doctor", description = "Check host tools, identity and configuration.")
final class DoctorCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @picocli.CommandLine.Option(names = "--deep", description = "Also run an egress self-test from a throwaway container (needs the built image and a running stack).")
    private boolean deep;

    @Override
    public Integer call() {
        Context ctx = main.context();
        Sandbox sandbox = ctx.open();
        DoctorReport report = sandbox.sandboxService().doctor(deep);
        if (ctx.json()) {
            ctx.printJson(report);
        } else {
            Messages m = ctx.messages(sandbox);
            var out = ctx.out();
            for (Check c : report.checks()) {
                out.println(symbol(c.level()) + " " + c.id() + ": " + c.message());
                if (!c.hint().isBlank()) {
                    out.println(m.get("doctor.hint", c.hint()));
                }
            }
            out.println();
            out.println(m.get("doctor.summary", report.ok(), report.warn(), report.fail()));
        }
        return report.healthy() ? 0 : 1;
    }

    private static String symbol(Check.Level level) {
        return switch (level) {
            case OK -> "ok  ";
            case WARN -> "warn";
            case FAIL -> "FAIL";
        };
    }
}
