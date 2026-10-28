package no.syntakserror.lyngvi.cli;

import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import java.util.List;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.stack.MachineInfo;
import no.syntakserror.lyngvi.core.stack.ResourceAdvice;
import no.syntakserror.lyngvi.core.stack.ResourceFit;
import no.syntakserror.lyngvi.core.stack.ResourceSizes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi resources show|set|presets}: default limits for new sessions. */
@Command(name = "resources", description = "Show or change default resource limits for new sessions.",
        subcommands = {ResourcesCommand.ShowCommand.class, ResourcesCommand.SetCommand.class,
                       ResourcesCommand.PresetsCommand.class, ResourcesCommand.MachineCommand.class})
final class ResourcesCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    Main main() {
        return main;
    }

    @Override
    public Integer call() {
        return new ShowCommand(this).call();
    }

    static void print(Context ctx, Sandbox sandbox, ResourceLimits r, String headerKey) {
        if (ctx.json()) {
            ctx.printJson(r);
            return;
        }
        Messages m = ctx.messages(sandbox);
        ctx.out().println(m.get(headerKey));
        ctx.out().println("  mem     = " + r.mem());
        ctx.out().println("  memswap = " + r.memswap());
        ctx.out().println("  cpus    = " + r.cpus());
        ctx.out().println("  pids    = " + r.pids());
    }

    @Command(name = "show", description = "Show the default limits.")
    static final class ShowCommand implements Callable<Integer> {
        @ParentCommand
        private ResourcesCommand parent;

        ShowCommand() {}

        ShowCommand(ResourcesCommand parent) {
            this.parent = parent;
        }

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            print(ctx, sandbox, sandbox.configService().resources(), "resources.header");
            return 0;
        }
    }

    @Command(name = "set", description = "Change one or more default limits, or take them all from a preset.")
    static final class SetCommand implements Callable<Integer> {
        @ParentCommand
        private ResourcesCommand parent;

        @Option(names = "--preset", paramLabel = "<id>", description = "Preset to start from; see 'resources presets'.")
        private String preset;

        @Option(names = "--mem", paramLabel = "<size>", description = "Memory limit, e.g. 8g or 512m.")
        private String mem;

        @Option(names = "--memswap", paramLabel = "<size>", description = "Memory plus swap total, e.g. 12g.")
        private String memswap;

        @Option(names = "--cpus", paramLabel = "<n>", description = "CPU count, e.g. 4.0.")
        private String cpus;

        @Option(names = "--pids", paramLabel = "<n>", description = "PID (thread) limit; -1 for unlimited.")
        private String pids;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            // A preset sets all four; the single options then override what the user named explicitly.
            ResourceLimits wanted = new ResourceLimits(mem, memswap, cpus, pids);
            if (preset != null) wanted = sandbox.configService().resourcePreset(preset).limits().merge(wanted);
            ResourceLimits updated = sandbox.configService().setResources(wanted);
            print(ctx, sandbox, updated, "resources.saved");
            return 0;
        }
    }

    /** JSON payload of {@code resources presets}. */
    record PresetReport(String id, String label, ResourceLimits limits, boolean fits, int sessions) {}

    @Command(name = "presets", description = "List the resource presets the profile offers.")
    static final class PresetsCommand implements Callable<Integer> {
        @ParentCommand
        private ResourcesCommand parent;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            MachineInfo machine = sandbox.stackService().machine();
            Messages m = ctx.messages(sandbox);
            List<PresetReport> reports = sandbox.configService().resourcePresets(machine).stream()
                    .map(p -> new PresetReport(p.id(), p.label(m), p.limits(),
                            ResourceFit.fits(p.limits(), machine), ResourceFit.sessions(p.limits(), machine)))
                    .toList();
            if (ctx.json()) {
                ctx.printJson(new PresetList(reports));
                return 0;
            }
            ctx.out().println(m.get("resources.presets.header"));
            for (PresetReport r : reports) {
                ResourceLimits l = r.limits();
                ctx.out().println("  " + r.id() + "  " + r.label());
                ctx.out().println("      mem=" + l.mem() + " memswap=" + l.memswap() + " cpus=" + l.cpus() + " pids=" + l.pids()
                        + (r.fits() ? "" : "  " + m.get("resources.preset.toobig"))
                        + (r.sessions() == ResourceFit.UNKNOWN ? "" : "  " + m.get("resources.preset.sessions", r.sessions())));
            }
            return 0;
        }
    }

    record PresetList(List<PresetReport> presets) {}

    /** JSON payload of {@code resources machine}. */
    record MachineReport(MachineInfo machine, ResourceLimits limits, List<ResourceAdvice.Advice> advice) {}

    @Command(name = "machine", description = "Show the Podman machine (or host) resources and whether the limits fit.")
    static final class MachineCommand implements Callable<Integer> {
        @ParentCommand
        private ResourcesCommand parent;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            MachineInfo machine = sandbox.stackService().machine();
            ResourceLimits limits = sandbox.configService().resources();
            List<ResourceAdvice.Advice> advice = ResourceAdvice.evaluate(limits, machine);
            if (ctx.json()) {
                ctx.printJson(new MachineReport(machine, limits, advice));
                return 0;
            }
            Messages m = ctx.messages(sandbox);
            if (!machine.available()) {
                ctx.out().println(m.get("machine.unavailable"));
            } else {
                ctx.out().println(m.get("machine.header", machine.name(), machine.state()));
                ctx.out().println("  cpus   = " + machine.cpus());
                ctx.out().println("  memory = " + ResourceSizes.format(machine.memoryBytes()));
                ctx.out().println("  disk   = " + ResourceSizes.format(machine.diskBytes()));
                int sessions = ResourceFit.sessions(limits, machine);
                if (sessions != ResourceFit.UNKNOWN) ctx.out().println("  " + m.get("resources.preset.sessions", sessions));
            }
            if (advice.isEmpty()) {
                ctx.out().println(m.get("machine.fits"));
            }
            for (ResourceAdvice.Advice a : advice) {
                ctx.out().println("  " + a.code() + ": " + a.message());
            }
            return 0;
        }
    }
}
