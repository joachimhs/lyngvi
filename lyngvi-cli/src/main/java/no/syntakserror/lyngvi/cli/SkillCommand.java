package no.syntakserror.lyngvi.cli;

import java.util.List;
import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.SkillInfo;
import no.syntakserror.lyngvi.core.skills.SkillSync;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * {@code lyngvi skill list|show|new|remove|enable|disable|sync}: the sandbox's skills (spec §3.6).
 *
 * <p>Editing a skill is editing a file — {@code skills/<name>/SKILL.md} — so the CLI has no editor of its own;
 * {@code show} prints the path and the content, and the GUI has the editor.
 */
@Command(name = "skill", description = "Manage the skills this sandbox gives its agents.",
        subcommands = {SkillCommand.ListCommand.class, SkillCommand.ShowCommand.class, SkillCommand.NewCommand.class,
                       SkillCommand.RemoveCommand.class, SkillCommand.EnableCommand.class, SkillCommand.DisableCommand.class,
                       SkillCommand.SyncCommand.class})
final class SkillCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    Main main() {
        return main;
    }

    @Override
    public Integer call() {
        return new ListCommand(this).call();
    }

    record SkillList(List<SkillInfo> skills, List<String> agents) {}
    record SkillShown(SkillInfo skill, String content) {}
    record Changed(SkillInfo skill) {}
    record Synced(List<SkillSync.Result> agents) {}

    /** One line per skill: the flag, the name, the description, and the problem when it has one. */
    private static void print(Context ctx, Sandbox sandbox, List<SkillInfo> skills) {
        if (skills.isEmpty()) {
            ctx.out().println(ctx.messages(sandbox).get("skill.list.none"));
            return;
        }
        ctx.out().println(ctx.messages(sandbox).get("skill.list.header"));
        for (SkillInfo s : skills) {
            ctx.out().println("  [" + (s.enabled() ? "x" : " ") + "] " + s.name()
                    + (s.description().isBlank() ? "" : "  " + s.description()));
            if (!s.valid()) ctx.out().println("        ! " + s.problem());
        }
    }

    @Command(name = "list", description = "List the skills in this sandbox and whether each one is switched on.")
    static final class ListCommand implements Callable<Integer> {
        @ParentCommand private SkillCommand parent;
        ListCommand() {}
        ListCommand(SkillCommand parent) { this.parent = parent; }

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            List<SkillInfo> skills = sandbox.skillService().list();
            List<String> agents = sandbox.skillService().agentsTakingSkills();
            if (ctx.json()) {
                ctx.printJson(new SkillList(skills, agents));
                return 0;
            }
            print(ctx, sandbox, skills);
            ctx.out().println(agents.isEmpty()
                    ? ctx.messages(sandbox).get("skill.agents.none")
                    : ctx.messages(sandbox).get("skill.agents", String.join(", ", agents)));
            return 0;
        }
    }

    @Command(name = "show", description = "Print a skill's SKILL.md.")
    static final class ShowCommand implements Callable<Integer> {
        @ParentCommand private SkillCommand parent;
        @Parameters(index = "0", paramLabel = "<name>") private String name;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            SkillInfo skill = sandbox.skillService().show(name);
            String content = sandbox.skillService().read(name);
            if (ctx.json()) {
                ctx.printJson(new SkillShown(skill, content));
                return 0;
            }
            ctx.out().println(skill.path());
            ctx.out().println();
            ctx.out().println(content);
            return 0;
        }
    }

    @Command(name = "new", description = "Create a skill folder with a SKILL.md to fill in.")
    static final class NewCommand implements Callable<Integer> {
        @ParentCommand private SkillCommand parent;
        @Parameters(index = "0", paramLabel = "<name>") private String name;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            SkillInfo created = sandbox.skillService().create(name);
            if (ctx.json()) {
                ctx.printJson(new Changed(created));
            } else {
                ctx.out().println(ctx.messages(sandbox).get("skill.created", created.name(), created.path()));
            }
            return 0;
        }
    }

    @Command(name = "remove", description = "Delete a skill and its folder.")
    static final class RemoveCommand implements Callable<Integer> {
        @ParentCommand private SkillCommand parent;
        @Parameters(index = "0", paramLabel = "<name>") private String name;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            SkillInfo removed = sandbox.skillService().remove(name);
            if (ctx.json()) {
                ctx.printJson(new Changed(removed));
            } else {
                ctx.out().println(ctx.messages(sandbox).get("skill.removed", removed.name()));
            }
            return 0;
        }
    }

    @Command(name = "enable", description = "Switch a skill on. Takes effect on the next message in every conversation.")
    static final class EnableCommand implements Callable<Integer> {
        @ParentCommand private SkillCommand parent;
        @Parameters(index = "0", paramLabel = "<name>") private String name;

        @Override
        public Integer call() {
            return SkillCommand.setEnabled(parent, name, true);
        }
    }

    @Command(name = "disable", description = "Switch a skill off without deleting it.")
    static final class DisableCommand implements Callable<Integer> {
        @ParentCommand private SkillCommand parent;
        @Parameters(index = "0", paramLabel = "<name>") private String name;

        @Override
        public Integer call() {
            return SkillCommand.setEnabled(parent, name, false);
        }
    }

    private static int setEnabled(SkillCommand parent, String name, boolean enabled) {
        Context ctx = parent.main().context();
        Sandbox sandbox = ctx.open();
        SkillInfo changed = sandbox.skillService().setEnabled(name, enabled);
        if (ctx.json()) {
            ctx.printJson(new Changed(changed));
        } else {
            ctx.out().println(ctx.messages(sandbox).get(enabled ? "skill.enabled" : "skill.disabled", changed.name()));
        }
        return 0;
    }

    @Command(name = "sync", description = "Copy the switched-on skills into the agents' configuration directories.")
    static final class SyncCommand implements Callable<Integer> {
        @ParentCommand private SkillCommand parent;

        @Override
        public Integer call() {
            Context ctx = parent.main().context();
            Sandbox sandbox = ctx.open();
            List<SkillSync.Result> results = sandbox.skillService().syncAll();
            if (ctx.json()) {
                ctx.printJson(new Synced(results));
                return 0;
            }
            for (SkillSync.Result r : results) {
                if (!r.supported()) continue;
                ctx.out().println(ctx.messages(sandbox).get("skill.synced", r.agent(),
                        String.valueOf(r.copied().size()), String.valueOf(r.removed().size())));
            }
            return 0;
        }
    }
}
