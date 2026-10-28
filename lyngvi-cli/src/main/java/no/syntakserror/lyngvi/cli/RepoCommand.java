package no.syntakserror.lyngvi.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.RepoEntry;
import no.syntakserror.lyngvi.core.contract.RepoInfo;
import no.syntakserror.lyngvi.core.i18n.Messages;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi repo list|add|remove}. */
@Command(name = "repo", description = "Manage registered repositories.",
        subcommands = {RepoCommand.ListCommand.class, RepoCommand.AddCommand.class, RepoCommand.RemoveCommand.class})
final class RepoCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    Main main() {
        return main;
    }

    @Override
    public Integer call() {
        return new ListCommand(this).call();
    }

    /** JSON payload of {@code repo list}. */
    record RepoList(List<RepoInfo> repos) {}

    @Command(name = "list", description = "List registered repositories.")
    static final class ListCommand implements Callable<Integer> {
        @ParentCommand
        private RepoCommand parent;

        ListCommand() {}

        ListCommand(RepoCommand parent) {
            this.parent = parent;
        }

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            List<RepoInfo> repos = sandbox.configService().repos().stream().map(RepoInfo::of).toList();
            if (ctx.json()) {
                ctx.printJson(new RepoList(repos));
                return 0;
            }
            Messages m = ctx.messages(sandbox);
            if (repos.isEmpty()) {
                ctx.out().println(m.get("repo.list.empty"));
                return 0;
            }
            ctx.out().println(m.get("repo.list.header"));
            for (RepoInfo r : repos) {
                ctx.out().println(m.get("status.repo", r.path(), r.name()) + (r.history() == null ? "" : " (history=" + r.history() + ")"));
            }
            return 0;
        }
    }

    @Command(name = "add", description = "Register a repository.")
    static final class AddCommand implements Callable<Integer> {
        @ParentCommand
        private RepoCommand parent;

        @Parameters(index = "0", paramLabel = "<path>", description = "Path to a git work tree on this machine.")
        private Path path;

        @Option(names = "--name", paramLabel = "<name>", description = "Mount name under /work (default: the directory name).")
        private String name;

        @Option(names = "--history", paramLabel = "<none|shallow|full>", description = "Override the profile's copy.history for this repository.")
        private String history;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            Optional<History> h;
            try {
                h = Optional.ofNullable(history).map(History::parse);
            } catch (IllegalArgumentException e) {
                throw new SandboxException(ErrorCode.INVALID_ARGUMENT, e.getMessage());
            }
            RepoEntry added = sandbox.configService().addRepo(path, Optional.ofNullable(name), h);
            if (ctx.json()) {
                ctx.printJson(RepoInfo.of(added));
            } else {
                ctx.out().println(ctx.messages(sandbox).get("repo.added", added.path(), added.name()));
            }
            return 0;
        }
    }

    @Command(name = "remove", description = "Remove a registered repository (by name or path).")
    static final class RemoveCommand implements Callable<Integer> {
        @ParentCommand
        private RepoCommand parent;

        @Parameters(index = "0", paramLabel = "<name|path>")
        private String repo;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            RepoEntry removed = sandbox.configService().removeRepo(repo);
            if (ctx.json()) {
                ctx.printJson(RepoInfo.of(removed));
            } else {
                ctx.out().println(ctx.messages(sandbox).get("repo.removed", removed.path()));
            }
            return 0;
        }
    }
}
