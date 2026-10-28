package no.syntakserror.lyngvi.cli;

import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.stack.StackStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi down}: stop the shared stack. */
@Command(name = "down", description = "Stop the shared stack.")
final class DownCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Override
    public Integer call() {
        Context ctx = main.context();
        Sandbox sandbox = ctx.open();
        StackStatus status = sandbox.stackService().down();
        StatusPrinter.print(ctx, sandbox, status, "stack.down");
        return 0;
    }
}
