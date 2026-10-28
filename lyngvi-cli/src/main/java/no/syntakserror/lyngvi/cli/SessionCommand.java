package no.syntakserror.lyngvi.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import no.syntakserror.lyngvi.core.api.AddRepoOptions;
import no.syntakserror.lyngvi.core.api.ApplyOptions;
import no.syntakserror.lyngvi.core.api.AskOptions;
import no.syntakserror.lyngvi.core.api.ExportOptions;
import no.syntakserror.lyngvi.core.contract.ApplyResult;
import no.syntakserror.lyngvi.core.contract.ExportResult;
import no.syntakserror.lyngvi.core.contract.FileChange;
import no.syntakserror.lyngvi.core.contract.ReviewResult;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.ask.AgentEvent;
import no.syntakserror.lyngvi.core.ask.AgentEvents;
import no.syntakserror.lyngvi.core.ask.AgentRun;
import no.syntakserror.lyngvi.core.ask.Suggestion;
import no.syntakserror.lyngvi.core.api.NewSessionOptions;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.CleanupReport;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.contract.SessionInfo;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.session.SessionExport;
import no.syntakserror.lyngvi.core.stack.ResourceSizes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi session …}: sessions, working copies and their containers. */
@Command(name = "session", description = "Create and manage sessions.",
        subcommands = {SessionCommand.NewCommand.class, SessionCommand.ListCommand.class, SessionCommand.ShowCommand.class,
                       SessionCommand.RenameCommand.class, SessionCommand.AddRepoCommand.class,
                       SessionCommand.StartCommand.class, SessionCommand.StopCommand.class,
                       SessionCommand.StatusCommand.class, SessionCommand.RemoveCommand.class, SessionCommand.GcCommand.class,
                       SessionCommand.AskCommand.class, SessionCommand.SuggestCommand.class, SessionCommand.FetchCommand.class,
                       SessionCommand.ExportCommand.class, SessionCommand.DiffCommand.class, SessionCommand.ReviewCommand.class,
                       SessionCommand.ApplyCommand.class})
final class SessionCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    Main main() {
        return main;
    }

    @Override
    public Integer call() {
        return new ListCommand(this).call();
    }

    record SessionList(List<SessionInfo> sessions) {}
    record Removed(String id, boolean removed, boolean conversationKept) {}
    record Stopped(List<String> stopped, List<String> retired, List<String> removed, List<String> removedVolumes) {}

    static void printInfo(Context ctx, Sandbox sandbox, SessionInfo s) {
        Messages m = ctx.messages(sandbox);
        String state = s.container().state().isBlank() ? m.get("stack.absent") : s.container().state()
                + (s.container().health().isBlank() ? "" : " (" + s.container().health() + ")");
        ctx.out().println(s.id() + "  " + s.title());
        ctx.out().println("  " + m.get("session.agent", s.agent()) + "  " + m.get("session.repos", String.join(", ", s.repos())));
        ctx.out().println("  " + m.get("session.container", s.container().name(), state));
    }

    @Command(name = "new", description = "Create a session: working copies of the repositories and a conversation file.")
    static final class NewCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Option(names = "--title", paramLabel = "<text>") private String title;
        @Option(names = "--repos", paramLabel = "<name,...>", split = ",", description = "Registered repositories to include (default: all).") private List<String> repos;
        @Option(names = "--no-repos", description = "No working copies; a plain question-and-answer session.") private boolean noRepos;
        @Option(names = "--agent", paramLabel = "<id>") private String agent;
        @Option(names = "--model", paramLabel = "<model>") private String model;
        @Option(names = "--history", paramLabel = "<none|shallow|full>") private String history;
        @Option(names = "--depth", paramLabel = "<n>") private Integer depth;
        @Option(names = "--preset", paramLabel = "<id>", description = "Resource preset for this session; see 'resources presets'.") private String preset;
        @Option(names = "--mem", paramLabel = "<size>") private String mem;
        @Option(names = "--memswap", paramLabel = "<size>") private String memswap;
        @Option(names = "--cpus", paramLabel = "<n>") private String cpus;
        @Option(names = "--pids", paramLabel = "<n>") private String pids;
        @Option(names = "--cache", paramLabel = "<off|shared|readonly>",
                description = "Shared tool caches for this session (default: the profile's). Use off for code you do not trust.")
        private String cache;

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
            // A preset sets all four; the single options then override what the user named explicitly.
            Optional<ResourceLimits> limits = preset == null && mem == null && memswap == null && cpus == null && pids == null
                    ? Optional.empty() : Optional.of(new ResourceLimits(mem, memswap, cpus, pids));
            if (preset != null) {
                ResourceLimits fromPreset = sandbox.configService().resourcePreset(preset).limits();
                limits = limits.map(fromPreset::merge);
            }
            Conversation c = sandbox.sessionService().create(new NewSessionOptions(Optional.ofNullable(title),
                    Optional.ofNullable(repos), Optional.ofNullable(agent), Optional.ofNullable(model), h,
                    Optional.ofNullable(depth), limits, noRepos, Optional.ofNullable(cache)));
            if (ctx.json()) {
                ctx.printJson(c);
            } else {
                Messages m = ctx.messages(sandbox);
                ctx.out().println(m.get("session.created", c.id(), c.title()));
                for (String r : c.repos()) {
                    Conversation.RepoBase b = c.base().get(r);
                    ctx.out().println("  " + r + " -> /work/" + r + "  (" + b.history().json() + ", base " + b.baseCommit().substring(0, 8)
                            + (b.hostDirty() ? ", host had uncommitted changes" : "") + ")");
                }
            }
            return 0;
        }
    }

    @Command(name = "list", description = "List sessions.")
    static final class ListCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        ListCommand() {}
        ListCommand(SessionCommand parent) { this.parent = parent; }

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            List<SessionInfo> sessions = sandbox.sessionService().list();
            if (ctx.json()) {
                ctx.printJson(new SessionList(sessions));
                return 0;
            }
            if (sessions.isEmpty()) {
                ctx.out().println(ctx.messages(sandbox).get("session.none"));
            }
            for (SessionInfo s : sessions) {
                printInfo(ctx, sandbox, s);
            }
            return 0;
        }
    }

    @Command(name = "show", description = "Show a session's conversation file.")
    static final class ShowCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            Conversation c = sandbox.sessionService().show(id);
            if (ctx.json()) {
                ctx.printJson(c);
            } else {
                printInfo(ctx, sandbox, sandbox.sessionService().status(c.id()));
                for (String r : c.repos()) {
                    Conversation.RepoBase b = c.base().get(r);
                    ctx.out().println("  " + r + ": base " + b.baseCommit().substring(0, 8) + " (" + b.history().json() + ", host " + b.hostBranch() + "@" + b.hostSha().substring(0, 8) + ")");
                }
                ctx.out().println("  deliveries: " + c.deliveries().size() + ", messages: " + c.messages().size());
            }
            return 0;
        }
    }

    @Command(name = "rename", description = "Change a session's title.")
    static final class RenameCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;
        @Parameters(index = "1..*", paramLabel = "<title>") private String[] title;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            Conversation c = sandbox.sessionService().rename(id, String.join(" ", Arrays.asList(title)));
            if (ctx.json()) ctx.printJson(c); else ctx.out().println(ctx.messages(sandbox).get("session.renamed", c.id(), c.title()));
            return 0;
        }
    }

    @Command(name = "add-repo", description = "Add working copies of registered repositories to a session that already exists.")
    static final class AddRepoCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;
        @Parameters(index = "1..*", paramLabel = "<name>", description = "Registered repositories to add.") private List<String> repos;
        @Option(names = "--history", paramLabel = "<none|shallow|full>") private String history;
        @Option(names = "--depth", paramLabel = "<n>") private Integer depth;

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
            List<String> wanted = repos == null ? List.of() : repos;
            Conversation c = sandbox.sessionService().addRepos(id, new AddRepoOptions(wanted, h, Optional.ofNullable(depth)));
            if (ctx.json()) {
                ctx.printJson(c);
            } else {
                Messages m = ctx.messages(sandbox);
                ctx.out().println(m.get("session.repos.added", c.id(), String.join(", ", wanted)));
                for (String r : wanted) {
                    Conversation.RepoBase b = c.base().get(r);
                    ctx.out().println("  " + r + " -> /work/" + r + "  (" + b.history().json() + ", base " + b.baseCommit().substring(0, 8)
                            + (b.hostDirty() ? ", host had uncommitted changes" : "") + ")");
                }
            }
            return 0;
        }
    }

    @Command(name = "start", description = "Start the session's container.")
    static final class StartCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            SessionInfo s = sandbox.sessionService().start(id);
            if (ctx.json()) ctx.printJson(s); else printInfo(ctx, sandbox, s);
            return 0;
        }
    }

    @Command(name = "stop", description = "Stop the session's container (the working copy is kept).")
    static final class StopCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            SessionInfo s = sandbox.sessionService().stop(id);
            if (ctx.json()) ctx.printJson(s); else printInfo(ctx, sandbox, s);
            return 0;
        }
    }

    @Command(name = "status", description = "Show a session and its container state.")
    static final class StatusCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            SessionInfo s = sandbox.sessionService().status(id);
            if (ctx.json()) ctx.printJson(s); else printInfo(ctx, sandbox, s);
            return 0;
        }
    }

    @Command(name = "remove", description = "Remove the container and the working copy; the conversation too unless kept.")
    static final class RemoveCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;
        @Option(names = "--keep-conversation", description = "Keep the conversation file.") private boolean keep;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            String full = sandbox.sessionService().resolve(id);
            sandbox.sessionService().remove(full, keep);
            if (ctx.json()) ctx.printJson(new Removed(full, true, keep)); else ctx.out().println(ctx.messages(sandbox).get("session.removed", full));
            return 0;
        }
    }

    @Command(name = "fetch", description = "Copy a directory out of the session — by default the agent's output — onto the host.")
    static final class FetchCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;
        @Option(names = "--path", paramLabel = "<dir>", description = "Directory inside the session (default: output).") private String path = "output";
        @Option(names = "--to", paramLabel = "<dir>", description = "Where to put it (default: ./<name>).") private Path to;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            Conversation c = sandbox.sessionService().show(id);
            String rel = path.replaceAll("^/+", "").replaceAll("/+$", "");
            if (rel.isBlank() || rel.contains("..")) {
                throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "--path must be a directory inside the session");
            }
            Path dest = (to != null ? to : Path.of(rel.substring(rel.lastIndexOf('/') + 1))).toAbsolutePath().normalize();
            sandbox.sessionService().volume(c).copyOutDir(rel, dest);
            if (ctx.json()) ctx.printJson(new Fetched(c.id(), rel, dest.toString()));
            else ctx.out().println(ctx.messages(sandbox).get("session.fetched", rel, dest.toString()));
            return 0;
        }
    }

    record Fetched(String id, String path, String destination) {}

    @Command(name = "export", description = "Write the whole session — every working copy and the agent's output — to one zip on the host.")
    static final class ExportCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;
        @Option(names = "--to", paramLabel = "<file|dir>", description = "Where to write it (default: ./lyngvi-<title>-<time>.zip).") private Path to;
        @Option(names = "--no-git", description = "Leave the working copies' .git directories out.") private boolean noGit;
        @Option(names = "--all", description = "Keep build output and dependencies (node_modules, target, ...) too.") private boolean all;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            Conversation c = sandbox.sessionService().show(id);
            Path dest = to != null ? to : Path.of(SessionExport.fileName(c.title(), System.currentTimeMillis()));
            ExportResult r = sandbox.sessionService().export(c.id(), new ExportOptions(dest, !noGit, all));
            if (ctx.json()) {
                ctx.printJson(r);
            } else {
                Messages m = ctx.messages(sandbox);
                ctx.out().println(m.get("session.exported", r.files(), ResourceSizes.format(r.bytes()), r.path()));
                if (!r.excluded().isEmpty()) ctx.out().println(m.get("session.exported.excluded", String.join(", ", r.excluded())));
                if (r.partial()) ctx.out().println(m.get("session.exported.partial"));
            }
            return 0;
        }
    }

    @Command(name = "gc", description = "Stop containers of sessions idle longer than the limit, and remove containers without a session.")
    static final class GcCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Option(names = "--idle", paramLabel = "<minutes>", description = "Idle limit (default: the profile's session.idleStopMinutes).") private Integer idle;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            int minutes = idle != null ? idle : sandbox.profile().session().idleStopMinutes();
            CleanupReport report = sandbox.sessionService().cleanup(minutes);
            if (ctx.json()) {
                ctx.printJson(new Stopped(report.stopped(), report.retired(), report.removed(), report.removedVolumes()));
            } else {
                ctx.out().println(ctx.messages(sandbox).get("session.gc", report.stopped().size(), minutes));
                if (!report.removed().isEmpty()) ctx.out().println(ctx.messages(sandbox).get("session.gc.orphans", report.removed().size(), String.join(", ", report.removed())));
                if (!report.retired().isEmpty()) {
                    ctx.out().println(ctx.messages(sandbox).get("session.gc.retired", report.retired().size(), String.join(", ", report.retired())));
                }
                if (!report.removedVolumes().isEmpty()) {
                    ctx.out().println(ctx.messages(sandbox).get("session.gc.volumes", report.removedVolumes().size(), String.join(", ", report.removedVolumes())));
                }
            }
            return 0;
        }
    }

    @Command(name = "ask", description = "Send a message to the session's agent. The prompt comes from --prompt-file or stdin; --json streams JSONL events.")
    static final class AskCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;
        @Option(names = "--prompt-file", paramLabel = "<file>", description = "File holding the prompt (default: stdin).") private Path promptFile;
        @Option(names = "--model", paramLabel = "<model>") private String model;
        @Option(names = "--attach", paramLabel = "<file>", description = "Attachment; repeatable.") private List<Path> attachments = List.of();

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            String prompt;
            try {
                prompt = promptFile != null ? Files.readString(promptFile) : new String(ctx.stdin().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Cannot read the prompt: " + e.getMessage());
            }
            if (prompt.isBlank()) {
                throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "The prompt is empty");
            }
            String full = sandbox.sessionService().resolve(id);
            AgentRun run = sandbox.sessionService().ask(full, new AskOptions(prompt, Optional.ofNullable(model), attachments), event -> {
                if (ctx.json()) {
                    ctx.out().println(AgentEvents.toJson(event));
                } else {
                    printText(ctx, event);
                }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(run::cancel));
            AgentRun.Result result = run.waitFor();
            if (!ctx.json()) {
                ctx.out().println();
            }
            return "end_turn".equals(result.stopReason()) || "cancelled".equals(result.stopReason()) ? 0 : 1;
        }

        private static void printText(Context ctx, AgentEvent e) {
            switch (e) {
                case AgentEvent.Text t -> { ctx.out().print(t.delta()); ctx.out().flush(); }
                case AgentEvent.ToolStart t -> ctx.out().println("\n[tool] " + t.name() + " " + (t.input() == null ? "" : t.input()));
                case AgentEvent.ToolEnd t -> ctx.out().println("[tool] " + (t.isError() ? "error: " : "") + (t.output() == null ? "" : t.output()));
                case AgentEvent.Notice n -> ctx.out().println("\n[" + n.level() + "] " + n.text());
                case AgentEvent.Error er -> ctx.out().println("\n[error] " + er.message());
                default -> { }
            }
        }
    }

    @Command(name = "suggest", description = "Ask the agent for a branch name and commit message for the current changes.")
    static final class SuggestCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            Suggestion s = sandbox.sessionService().suggest(id);
            if (ctx.json()) ctx.printJson(s); else ctx.out().println(s.branch() + "\n" + s.message());
            return 0;
        }
    }

    @Command(name = "diff", description = "Print the unified diff of the session's changes since the baseline.")
    static final class DiffCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;
        @Option(names = "--from", paramLabel = "<start|last|n>") private String from;
        @Option(names = "--repo", paramLabel = "<name>") private String repo;
        @Option(names = "--path", paramLabel = "<file>") private String path;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            String diff = sandbox.deliveryService().diff(id, Optional.ofNullable(from), Optional.ofNullable(repo), Optional.ofNullable(path));
            if (ctx.json()) {
                ctx.printJson(java.util.Map.of("diff", diff));
            } else {
                ctx.out().print(diff);
                ctx.out().flush();
            }
            return 0;
        }
    }

    @Command(name = "review", description = "Classify the session's changes into apply / hold / reject and check them against the host repository.")
    static final class ReviewCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;
        @Option(names = "--from", paramLabel = "<start|last|n>") private String from;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            ReviewResult r = sandbox.deliveryService().review(id, Optional.ofNullable(from));
            if (ctx.json()) {
                ctx.printJson(r);
                return 0;
            }
            Messages m = ctx.messages(sandbox);
            ctx.out().println(m.get("review.header", r.from().describe()));
            for (var e : r.repos().entrySet()) {
                ReviewResult.RepoReview rr = e.getValue();
                ctx.out().println(e.getKey() + "  (host " + rr.host().branch() + (rr.host().isProtected() ? ", protected" : "")
                        + (rr.host().dirty() ? ", uncommitted changes" : "") + ")");
                for (FileChange f : rr.files()) {
                    ctx.out().println("  " + String.format("%-7s", f.bucket()) + " " + String.format("%-8s", f.change()) + " " + f.path()
                            + "  +" + f.additions() + " -" + f.deletions()
                            + (f.reason() == null ? "" : "  " + f.reason()) + (f.conflict() ? "  CONFLICT" : ""));
                }
            }
            return 0;
        }
    }

    @Command(name = "apply", description = "Apply selected changes to a working branch of the host repository and record the delivery.")
    static final class ApplyCommand implements Callable<Integer> {
        @ParentCommand private SessionCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") private String id;
        @Option(names = "--branch", required = true, paramLabel = "<name>") private String branch;
        @Option(names = "--message", paramLabel = "<text>", description = "Commit message (required with --mode commit).") private String message;
        @Option(names = "--mode", paramLabel = "<commit|stage>", defaultValue = "commit") private String mode;
        @Option(names = "--repo", paramLabel = "<name>", description = "Repository to deliver; repeatable (default: all with changes).") private List<String> repos = List.of();
        @Option(names = "--include", paramLabel = "<path>", description = "File to deliver; repeatable (default: the apply bucket). Held files must be included explicitly.") private List<String> includes = List.of();
        @Option(names = "--from", paramLabel = "<start|last|n>") private String from;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            if (!mode.equals("commit") && !mode.equals("stage")) {
                throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "--mode must be commit or stage");
            }
            if (mode.equals("commit") && (message == null || message.isBlank())) {
                throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "--message is required with --mode commit");
            }
            ApplyResult a = sandbox.deliveryService().apply(id, new ApplyOptions(branch, Optional.ofNullable(message), mode, repos, includes, Optional.ofNullable(from)));
            if (ctx.json()) {
                ctx.printJson(a);
                return 0;
            }
            Messages m = ctx.messages(sandbox);
            for (var e : a.repos().entrySet()) {
                ApplyResult.RepoOutcome o = e.getValue();
                ctx.out().println(m.get("apply.repo", e.getKey(), o.files(), o.branch(), o.hostCommit() == null ? m.get("apply.staged") : o.hostCommit().substring(0, 8)));
                if (o.stashed()) ctx.out().println("  " + m.get(o.stashPopConflict() ? "apply.stash.conflict" : "apply.stash.ok"));
                if (!o.conflicts().isEmpty()) ctx.out().println("  " + m.get("apply.conflicts", String.join(", ", o.conflicts())));
            }
            for (String n : a.notices()) ctx.out().println("  " + n);
            return 0;
        }
    }
}
