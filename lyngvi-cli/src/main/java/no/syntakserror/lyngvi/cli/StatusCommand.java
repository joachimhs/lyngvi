package no.syntakserror.lyngvi.cli;

import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.RepoInfo;
import no.syntakserror.lyngvi.core.contract.StatusReport;
import no.syntakserror.lyngvi.core.i18n.Messages;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi status}: show the sandbox configuration. */
@Command(name = "status", description = "Show the sandbox configuration and registered repositories.")
final class StatusCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Override
    public Integer call() {
        Context ctx = main.context();
        Sandbox sandbox = ctx.open();
        StatusReport s = sandbox.sandboxService().status();
        if (ctx.json()) {
            ctx.printJson(s);
            return 0;
        }
        Messages m = ctx.messages(sandbox);
        var out = ctx.out();
        out.println(m.get("status.header") + " (" + s.version() + ")");
        out.println(m.get("status.dir", s.sandboxDir()));
        out.println(m.get("status.profile", s.profile().name(), s.profile().label(), s.profile().pack()));
        out.println(m.get("status.ids", s.uid(), s.gid()));
        out.println(m.get("status.resources", s.resources().mem(), s.resources().memswap(), s.resources().cpus(), s.resources().pids()));
        out.println(m.get("status.agent", s.defaultAgent()));
        out.println(m.get("status.language", s.language()));
        out.println(m.get("status.repos", s.repos().size()));
        if (s.repos().isEmpty()) {
            out.println(m.get("status.norepos"));
        }
        for (RepoInfo r : s.repos()) {
            out.println(m.get("status.repo", r.path(), r.name()) + (r.history() == null ? "" : " (history=" + r.history() + ")"));
        }
        return 0;
    }
}
