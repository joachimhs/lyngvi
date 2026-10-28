package no.syntakserror.lyngvi.cli;

import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.contract.SessionInfo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi shell [<id>]}: an interactive shell in a session's container (started first when needed). */
@Command(name = "shell", description = "Open a shell in a session's container.")
final class ShellCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<id>", description = "Session id (default: the only session, or the newest).")
    private String id;

    @Override
    public Integer call() {
        Context ctx = main.context();
        Sandbox sandbox = ctx.open();
        String target = id;
        if (target == null) {
            var sessions = sandbox.sessionService().list();
            if (sessions.isEmpty()) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, "No sessions; create one with 'setup.sh session new'");
            }
            target = sessions.stream().map(SessionInfo::id).findFirst().orElseThrow();
        }
        return sandbox.sessionService().shell(target);
    }
}
