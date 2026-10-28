package no.syntakserror.lyngvi.cli;

import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.stack.StackStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi up}: start the shared stack. */
@Command(name = "up", description = "Start the shared stack (proxy and gateway).")
final class UpCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Override
    public Integer call() {
        Context ctx = main.context();
        Sandbox sandbox = ctx.open();
        StackStatus status = sandbox.stackService().up();
        StatusPrinter.print(ctx, sandbox, status, "stack.up");
        return 0;
    }
}
