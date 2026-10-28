package no.syntakserror.lyngvi.cli;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.CleanOptions;
import no.syntakserror.lyngvi.core.api.CleanupService;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.contract.DiskReport;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.core.stack.ResourceSizes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code lyngvi clean}: what this sandbox has left lying about, and removing it.
 *
 * <p>It prints and stops unless {@code --yes} is given, and without {@code --older-than} it considers only
 * objects whose conversation is gone. Both defaults are deliberate: a working copy is the one place
 * undelivered work lives, and this command is the only one that removes many at a time.
 */
@Command(name = "clean", description = "Show what this sandbox can reclaim — finished sessions, old images, caches — and remove it with --yes.")
final class CleanCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Option(names = "--containers", description = "Session containers (they are rebuilt on the next ask).") private boolean containers;
    @Option(names = "--work", description = "Working copies: session volumes and copies an older version left on the host.") private boolean work;
    @Option(names = "--images", description = "Images of other versions of Lyngvi that nothing runs.") private boolean images;
    @Option(names = "--cache", description = "Shared tool caches (everything in them can be downloaded again).") private boolean cache;
    @Option(names = "--all", description = "All of the above.") private boolean all;
    @Option(names = "--older-than", paramLabel = "<days>",
            description = "Also take objects of conversations untouched this long; without it, only objects whose conversation is gone.")
    private int olderThanDays;
    @Option(names = "--include-undelivered",
            description = "Allow removing a working copy from a conversation that never delivered anything. Those changes are not anywhere else.")
    private boolean includeUndelivered;

    @Override
    public Integer call() {
        Context ctx = main.context();
        Sandbox sandbox = ctx.open();
        boolean yes = main.global().yes;
        CleanOptions options = new CleanOptions(kinds(), olderThanDays, includeUndelivered, !yes);
        CleanupService service = sandbox.cleanupService();
        DiskReport report = service.report(options);
        Messages m = ctx.messages(sandbox);
        if (report.reclaimable().isEmpty()) {
            if (ctx.json()) {
                ctx.printJson(report);
                return 0;
            }
            ctx.out().println(m.get("clean.nothing"));
            return no.syntakserror.lyngvi.core.api.ErrorCode.NO_CHANGES.exitCode();
        }
        if (!yes) {
            if (ctx.json()) {
                ctx.printJson(report);
            } else {
                print(ctx, m, report);
            }
            return 0;
        }
        CleanupService.CleanResult result = service.clean(options);
        if (ctx.json()) {
            ctx.printJson(result);
        } else {
            ctx.out().println(m.get("clean.removed", result.removed().size(), ResourceSizes.format(result.freedBytes())));
        }
        return 0;
    }

    private void print(Context ctx, Messages m, DiskReport report) {
        for (DiskReport.Entry e : report.reclaimable()) {
            ctx.out().println("  " + e.kind() + "  " + e.name() + "  "
                    + (e.bytes() < 0 ? "?" : ResourceSizes.format(e.bytes())) + "  " + m.get("clean.reason." + e.reason()));
        }
        ctx.out().println(m.get("clean.summary", report.reclaimable().size(),
                ResourceSizes.format(report.reclaimableBytes()) + (report.sizesIncomplete() ? "+" : "")));
        ctx.out().println(m.get("clean.confirm"));
    }

    private Set<String> kinds() {
        if (all) return CleanOptions.ALL;
        Set<String> kinds = new LinkedHashSet<>();
        if (containers) kinds.add(CleanOptions.CONTAINERS);
        if (work) kinds.add(CleanOptions.WORK);
        if (images) kinds.add(CleanOptions.IMAGES);
        if (cache) kinds.add(CleanOptions.CACHE);
        // Nothing named: report everything, which is what someone who types `clean` to look wants.
        return kinds.isEmpty() ? CleanOptions.ALL : kinds;
    }
}
