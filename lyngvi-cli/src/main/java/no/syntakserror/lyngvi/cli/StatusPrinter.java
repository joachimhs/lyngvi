package no.syntakserror.lyngvi.cli;

import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.stack.StackStatus;

/** Shared output for {@code up}, {@code down} and the stack part of {@code status}. */
final class StatusPrinter {

    private StatusPrinter() {}

    static void print(Context ctx, Sandbox sandbox, StackStatus status, String headerKey) {
        if (ctx.json()) {
            ctx.printJson(status);
            return;
        }
        var m = ctx.messages(sandbox);
        ctx.out().println(m.get(headerKey, status.prefix() == null ? "-" : status.prefix()));
        for (StackStatus.Service s : status.services()) {
            String state = s.state().isBlank() ? m.get("stack.absent") : s.state() + (s.health().isBlank() ? "" : " (" + s.health() + ")");
            ctx.out().println("  " + s.name() + ": " + state + "  [" + s.container() + "]");
        }
    }
}
