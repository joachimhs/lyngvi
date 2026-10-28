package no.syntakserror.lyngvi.cli;

import java.util.Map;
import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.JsonOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi logs <proxy|gateway>}: stream a service log. JSONL with {@code --json}. */
@Command(name = "logs", description = "Show or follow the log of a stack service.")
final class LogsCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Parameters(index = "0", paramLabel = "<proxy|gateway>")
    private String service;

    @Option(names = {"--follow", "-f"}, description = "Keep streaming until interrupted.")
    private boolean follow;

    @Option(names = "--tail", paramLabel = "<n>", description = "Number of lines from the end (default: 100).")
    private int tail = 100;

    @Override
    public Integer call() {
        Context ctx = main.context();
        Sandbox sandbox = ctx.open();
        sandbox.stackService().logs(service, follow, tail, line -> {
            if (ctx.json()) {
                ctx.out().println(JsonOutput.line(
                        Map.of("ts", System.currentTimeMillis(), "service", service, "line", line)));
            } else {
                ctx.out().println(line);
            }
        });
        return 0;
    }
}
