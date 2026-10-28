package no.syntakserror.lyngvi.cli;

import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.ProfileInfo;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi profile show}. */
@Command(name = "profile", description = "Show the active profile.", subcommands = {ProfileCommand.ShowCommand.class, ProfileCommand.ProtectedCommand.class})
final class ProfileCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    Main main() {
        return main;
    }

    @Override
    public Integer call() {
        return new ShowCommand(this).call();
    }

    @Command(name = "show", description = "Show the active profile.")
    static final class ShowCommand implements Callable<Integer> {
        @ParentCommand
        private ProfileCommand parent;

        ShowCommand() {}

        ShowCommand(ProfileCommand parent) {
            this.parent = parent;
        }

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            ProfileInfo p = sandbox.configService().profile();
            if (ctx.json()) {
                ctx.printJson(p);
                return 0;
            }
            ctx.out().println(ctx.messages(sandbox).get("profile.header"));
            ctx.out().println("  name:  " + p.name());
            ctx.out().println("  label: " + p.label());
            ctx.out().println("  pack:  " + p.pack());
            return 0;
        }
    }

    record Protected(java.util.List<String> protectedBranches) {}

    @Command(name = "protected", description = "Branches deliveries may never land on (this sandbox's profile).",
            subcommands = {ProtectedCommand.ListCommand.class, ProtectedCommand.AddCommand.class, ProtectedCommand.RemoveCommand.class})
    static final class ProtectedCommand implements Callable<Integer> {
        @ParentCommand private ProfileCommand parent;
        ProfileCommand parent() { return parent; }

        @Override
        public Integer call() { return new ListCommand(this).call(); }

        @Command(name = "list", description = "List the protected branches.")
        static final class ListCommand implements Callable<Integer> {
            @ParentCommand private ProtectedCommand p;
            ListCommand() {}
            ListCommand(ProtectedCommand p) { this.p = p; }

            @Override
            public Integer call() {
                Context ctx = p.parent().main().context();
                java.util.List<String> branches = ctx.open().configService().protectedBranches();
                if (ctx.json()) ctx.printJson(new Protected(branches)); else branches.forEach(b -> ctx.out().println(b));
                return 0;
            }
        }

        @Command(name = "add", description = "Protect a branch.")
        static final class AddCommand implements Callable<Integer> {
            @ParentCommand private ProtectedCommand p;
            @picocli.CommandLine.Parameters(index = "0", paramLabel = "<branch>") private String branch;

            @Override
            public Integer call() {
                Context ctx = p.parent().main().context();
                var sandbox = ctx.open();
                java.util.List<String> branches = new java.util.ArrayList<>(sandbox.configService().protectedBranches());
                if (!branches.contains(branch)) branches.add(branch);
                sandbox.configService().setProtectedBranches(branches);
                if (ctx.json()) ctx.printJson(new Protected(branches)); else ctx.out().println("protected: " + String.join(", ", branches));
                return 0;
            }
        }

        @Command(name = "remove", description = "Allow deliveries to a branch again.")
        static final class RemoveCommand implements Callable<Integer> {
            @ParentCommand private ProtectedCommand p;
            @picocli.CommandLine.Parameters(index = "0", paramLabel = "<branch>") private String branch;

            @Override
            public Integer call() {
                Context ctx = p.parent().main().context();
                var sandbox = ctx.open();
                java.util.List<String> branches = new java.util.ArrayList<>(sandbox.configService().protectedBranches());
                branches.remove(branch);
                sandbox.configService().setProtectedBranches(branches);
                if (ctx.json()) ctx.printJson(new Protected(branches)); else ctx.out().println("protected: " + String.join(", ", branches));
                return 0;
            }
        }
    }
}
