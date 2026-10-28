package no.syntakserror.lyngvi.cli;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.JsonOutput;
import no.syntakserror.lyngvi.core.stack.BlockedRequest;
import no.syntakserror.lyngvi.core.stack.DomainEntry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi domain list|add|remove|reload|blocked}: the egress allowlist. */
@Command(name = "domain", description = "Manage the egress allowlist.",
        subcommands = {DomainCommand.ListCommand.class, DomainCommand.AddCommand.class, DomainCommand.RemoveCommand.class,
                       DomainCommand.ReloadCommand.class, DomainCommand.BlockedCommand.class})
final class DomainCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    Main main() {
        return main;
    }

    @Override
    public Integer call() {
        return new ListCommand(this).call();
    }

    record DomainList(List<DomainEntry> domains) {}
    record Changed(String domain, String source, String note, boolean reloaded) {}
    record Reloaded(boolean reloaded) {}
    record BlockedList(List<BlockedRequest> blocked) {}

    @Command(name = "list", description = "List allowed domains and where each comes from.")
    static final class ListCommand implements Callable<Integer> {
        @ParentCommand private DomainCommand parent;
        ListCommand() {}
        ListCommand(DomainCommand parent) { this.parent = parent; }

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            List<DomainEntry> domains = sandbox.domainService().list();
            if (ctx.json()) {
                ctx.printJson(new DomainList(domains));
                return 0;
            }
            ctx.out().println(ctx.messages(sandbox).get("domain.list.header"));
            for (DomainEntry d : domains) {
                ctx.out().println("  " + d.domain() + "  (" + d.source() + ")" + (d.note().isBlank() ? "" : "  # " + d.note()));
            }
            return 0;
        }
    }

    @Command(name = "add", description = "Allow a domain (.example.com also allows subdomains). Takes effect immediately when the proxy runs.")
    static final class AddCommand implements Callable<Integer> {
        @ParentCommand private DomainCommand parent;
        @Parameters(index = "0", paramLabel = "<domain>") private String domain;
        @Option(names = "--note", paramLabel = "<text>", description = "Why it is allowed; kept as a comment.") private String note;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            DomainEntry added = sandbox.domainService().add(domain, Optional.ofNullable(note));
            boolean reloaded = sandbox.domainService().reload();
            if (ctx.json()) {
                ctx.printJson(new Changed(added.domain(), added.source(), added.note(), reloaded));
            } else {
                ctx.out().println(ctx.messages(sandbox).get("domain.added", added.domain()));
                ctx.out().println(ctx.messages(sandbox).get(reloaded ? "domain.reloaded" : "domain.not.running"));
            }
            return 0;
        }
    }

    @Command(name = "remove", description = "Remove a domain from the user section of the allowlist.")
    static final class RemoveCommand implements Callable<Integer> {
        @ParentCommand private DomainCommand parent;
        @Parameters(index = "0", paramLabel = "<domain>") private String domain;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            DomainEntry removed = sandbox.domainService().remove(domain);
            boolean reloaded = sandbox.domainService().reload();
            if (ctx.json()) {
                ctx.printJson(new Changed(removed.domain(), removed.source(), removed.note(), reloaded));
            } else {
                ctx.out().println(ctx.messages(sandbox).get("domain.removed", removed.domain()));
                ctx.out().println(ctx.messages(sandbox).get(reloaded ? "domain.reloaded" : "domain.not.running"));
            }
            return 0;
        }
    }

    @Command(name = "reload", description = "Make the running proxy re-read the allowlist.")
    static final class ReloadCommand implements Callable<Integer> {
        @ParentCommand private DomainCommand parent;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            boolean reloaded = sandbox.domainService().reload();
            if (ctx.json()) {
                ctx.printJson(new Reloaded(reloaded));
            } else {
                ctx.out().println(ctx.messages(sandbox).get(reloaded ? "domain.reloaded" : "domain.not.running"));
            }
            return 0;
        }
    }

    @Command(name = "blocked", description = "Show hosts the proxy has denied, aggregated from its log.")
    static final class BlockedCommand implements Callable<Integer> {
        @ParentCommand private DomainCommand parent;
        @Option(names = {"--follow", "-f"}, description = "Keep streaming denied requests until interrupted.") private boolean follow;
        @Option(names = "--tail", paramLabel = "<n>", description = "Log lines to scan from the end (default: 1000).") private int tail = 1000;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            if (follow) {
                sandbox.stackService().follow(true, tail, d -> {
                    if (ctx.json()) {
                        ctx.out().println(JsonOutput.line(Map.of("ts", d.at(), "host", d.host(), "status", d.status(), "method", d.method(), "url", d.url())));
                    } else {
                        ctx.out().println(d.host() + "  " + d.method() + " " + d.url() + "  (" + d.status() + ")");
                    }
                });
                return 0;
            }
            List<BlockedRequest> blocked = sandbox.stackService().blocked(tail);
            if (ctx.json()) {
                ctx.printJson(new BlockedList(blocked));
                return 0;
            }
            if (blocked.isEmpty()) {
                ctx.out().println(ctx.messages(sandbox).get("domain.blocked.none"));
                return 0;
            }
            ctx.out().println(ctx.messages(sandbox).get("domain.blocked.header"));
            for (BlockedRequest b : blocked) {
                ctx.out().println("  " + b.host() + "  x" + b.count() + "  (" + b.status() + " " + b.method() + ")");
            }
            return 0;
        }
    }
}
