package no.syntakserror.lyngvi.cli;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.ask.AgentUsage;
import no.syntakserror.lyngvi.core.ask.AuthStatus;
import no.syntakserror.lyngvi.core.ask.Model;
import no.syntakserror.lyngvi.core.ask.UsageLimit;
import no.syntakserror.lyngvi.core.contract.AgentInfo;
import no.syntakserror.lyngvi.core.contract.AgentUsageReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi agent list|status|models|login|logout|usage}. */
@Command(name = "agent", description = "Agents and their credentials.",
        subcommands = {AgentCommand.ListCommand.class, AgentCommand.StatusCommand.class, AgentCommand.ModelsCommand.class,
                       AgentCommand.LoginCommand.class, AgentCommand.LogoutCommand.class, AgentCommand.UsageCommand.class})
final class AgentCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    Main main() {
        return main;
    }

    @Override
    public Integer call() {
        return new ListCommand(this).call();
    }

    record AgentList(List<AgentInfo> agents) {}
    record ModelList(List<Model> models) {}
    record LoggedOut(String id, boolean loggedOut) {}

    @Command(name = "list", description = "List agents, whether an adapter exists, and login state.")
    static final class ListCommand implements Callable<Integer> {
        @ParentCommand private AgentCommand parent;
        ListCommand() {}
        ListCommand(AgentCommand parent) { this.parent = parent; }

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            List<AgentInfo> agents = sandbox.agentService().list();
            if (ctx.json()) {
                ctx.printJson(new AgentList(agents));
                return 0;
            }
            for (AgentInfo a : agents) {
                // A server of your own is the other way to get an answer, so an agent that can use one says where
                // it stands with it — otherwise 'not logged in' reads as 'cannot answer', which it need not be.
                String own = !a.canUseLocalModels() ? ""
                        : ", " + (a.localModels() == 0 ? "no local models" : a.localModels() + " local model(s)");
                ctx.out().println(a.id() + "  " + a.label() + "  [" + (a.available() ? "adapter" : "no adapter") + ", "
                        + (a.enabled() ? "enabled" : "not enabled in profile") + ", " + (a.loggedIn() ? "logged in" : "not logged in")
                        + own + "]");
            }
            return 0;
        }
    }

    @Command(name = "status", description = "Login state of one agent.")
    static final class StatusCommand implements Callable<Integer> {
        @ParentCommand private AgentCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            AuthStatus s = sandbox.agentService().status(id);
            if (ctx.json()) ctx.printJson(s);
            else ctx.out().println(id + ": " + (s.loggedIn() ? "logged in" : "not logged in") + " (" + s.mode() + (s.account() == null ? "" : ", " + s.account()) + ")");
            return 0;
        }
    }

    @Command(name = "models", description = "Models an agent offers.")
    static final class ModelsCommand implements Callable<Integer> {
        @ParentCommand private AgentCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;
        @Option(names = "--refresh", description = "Re-read the agent's own catalogue instead of the cached one.") private boolean refresh;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            List<Model> models = sandbox.agentService().models(id, refresh);
            if (ctx.json()) ctx.printJson(new ModelList(models));
            else for (Model m : models) ctx.out().println(m.id() + "  " + m.label() + (m.isDefault() ? "  (default)" : ""));
            return 0;
        }
    }

    @Command(name = "login", description = "Run the agent's login flow; URLs and codes are printed as they appear.")
    static final class LoginCommand implements Callable<Integer> {
        @ParentCommand private AgentCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;
        @Option(names = "--mode", paramLabel = "<mode>", description = "Auth mode, e.g. subscription or api-key.") private String mode;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            return sandbox.agentService().login(id, mode, line -> ctx.out().println(line));
        }
    }

    @Command(name = "logout", description = "Remove the agent's credentials.")
    static final class LogoutCommand implements Callable<Integer> {
        @ParentCommand private AgentCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            sandbox.agentService().logout(id);
            if (ctx.json()) ctx.printJson(new LoggedOut(id, true)); else ctx.out().println(id + ": logged out");
            return 0;
        }
    }

    @Command(name = "usage", description = "Account quotas (when the agent exposes them) and the tokens spent in this sandbox.")
    static final class UsageCommand implements Callable<Integer> {
        @ParentCommand private AgentCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            AgentUsageReport r = sandbox.agentService().usageReport(id);
            if (ctx.json()) {
                ctx.printJson(r);
                return 0;
            }
            AgentUsage account = r.account();
            if (!account.supported()) {
                ctx.out().println(id + ": account usage is not available for this agent"
                        + (account.note() == null ? "" : " (" + account.note() + ")"));
            } else {
                if (account.plan() != null) ctx.out().println("Plan: " + account.plan());
                if (account.note() != null) ctx.out().println(account.note());
                for (UsageLimit l : account.limits()) ctx.out().println("  " + limitLine(l));
                account.extra().forEach((k, v) -> ctx.out().println("  " + k + ": " + v));
            }
            AgentUsageReport.Totals t = r.local();
            ctx.out().println("In this sandbox: " + t.turns() + " turn(s), in " + t.input() + ", out " + t.output()
                    + ", cache " + (t.cacheRead() + t.cacheWrite()) + ", " + t.tokens() + " tokens total"
                    + (t.costUsd() == null ? "" : String.format(Locale.ROOT, ", $%.2f", t.costUsd())));
            for (AgentUsageReport.Group g : r.byModel()) {
                ctx.out().println("  " + (g.key().isEmpty() ? "(no model)" : g.key()) + ": " + g.totals().tokens() + " tokens over "
                        + g.totals().turns() + " turn(s)");
            }
            return 0;
        }

        private static String limitLine(UsageLimit l) {
            String used = UsageLimit.format(l.used());
            String of = l.limit() == null ? used : used + " / " + UsageLimit.format(l.limit());
            String unit = l.unit() == null ? "" : " " + l.unit();
            String percent = l.ratio().isPresent() ? String.format(Locale.ROOT, " (%d%%)", Math.round(l.ratio().getAsDouble() * 100)) : "";
            String reset = l.resetAt() == null ? "" : ", resets " + Instant.ofEpochMilli(l.resetAt());
            return l.label() + ": " + of + unit + percent + reset;
        }
    }
}
