package no.syntakserror.lyngvi.cli;

import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.contract.VersionInfo;
import no.syntakserror.lyngvi.core.version.Version;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi version}: print the product version. */
@Command(name = "version", description = "Print the lyngvi version.")
final class VersionCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @picocli.CommandLine.Option(names = "--check", description = "Ask the release API whether a newer version exists.")
    private boolean check;

    @Override
    public Integer call() {
        Context ctx = main.context();
        if (check) {
            var info = new no.syntakserror.lyngvi.core.version.ReleaseCheck().check(Version.current(),
                    no.syntakserror.lyngvi.core.version.ReleaseCheck.apiFrom(ctx.platform().env()));
            if (ctx.json()) {
                ctx.printJson(info);
            } else if (info.upToDate()) {
                ctx.out().println(ctx.messages(null).get("version.uptodate", info.current()));
            } else {
                ctx.out().println(ctx.messages(null).get("version.newer", info.latest(), info.current(), info.releaseUrl() == null ? "" : info.releaseUrl()));
            }
            return 0;
        }
        VersionInfo info = new VersionInfo(Version.current().toString());
        if (ctx.json()) {
            ctx.printJson(info);
        } else {
            ctx.out().println(ctx.messages(null).get("version.line", info.version()));
        }
        return 0;
    }
}
