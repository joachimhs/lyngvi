package no.syntakserror.lyngvi.cli;

import java.util.Map;
import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.JsonOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi build}: build the base image (and the gateway image when present). */
@Command(name = "build", description = "Build the base image from the rendered Containerfile.")
final class BuildCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Option(names = "--no-cache", description = "Rebuild every layer.")
    private boolean noCache;

    /** Final JSON line of a {@code --json} build. */
    record Built(String type, String image) {}

    @Override
    public Integer call() {
        Context ctx = main.context();
        Sandbox sandbox = ctx.open();
        String image = sandbox.stackService().build(noCache, line -> {
            if (ctx.json()) {
                ctx.out().println(JsonOutput.line(Map.of("type", "log", "line", line)));
            } else {
                ctx.out().println(line);
            }
        });
        if (ctx.json()) {
            ctx.printJson(new Built("done", image));
        } else {
            ctx.out().println(ctx.messages(sandbox).get("build.done", image));
        }
        return 0;
    }
}
