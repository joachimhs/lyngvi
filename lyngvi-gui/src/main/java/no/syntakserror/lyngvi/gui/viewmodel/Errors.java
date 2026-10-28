package no.syntakserror.lyngvi.gui.viewmodel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.i18n.Messages;

/** Maps core exceptions to what a dialog needs (spec §9.5): a kind, a title, a message, details, alternatives. */
public final class Errors {

    public enum Kind { SESSION_BUSY, PROTECTED_BRANCH, NO_CHANGES, NOT_LOGGED_IN, NOT_INITIALIZED, GENERIC }

    /** A baseline the user can pick instead when a review found nothing. */
    public record Alternative(String from, String describe, int files) {}

    public record UiError(Kind kind, String title, String message, String details, List<Alternative> alternatives, Throwable cause) {}

    private Errors() {}

    public static UiError map(Throwable t, Messages m) {
        if (!(t instanceof SandboxException e)) {
            return new UiError(Kind.GENERIC, m.get("gui.error.generic.title"), String.valueOf(t.getMessage()), stack(t), List.of(), t);
        }
        Map<String, Object> data = e.data() == null ? Map.of() : e.data();
        String code = data.get("code") == null ? "" : String.valueOf(data.get("code"));
        Kind kind = switch (code) {
            case "SESSION_BUSY" -> Kind.SESSION_BUSY;
            case "PROTECTED_BRANCH" -> Kind.PROTECTED_BRANCH;
            case "NOT_LOGGED_IN" -> Kind.NOT_LOGGED_IN;
            default -> e.code() == ErrorCode.NO_CHANGES ? Kind.NO_CHANGES
                    : e.code() == ErrorCode.NOT_INITIALIZED ? Kind.NOT_INITIALIZED : Kind.GENERIC;
        };
        String title = switch (kind) {
            case SESSION_BUSY -> m.get("gui.error.busy.title");
            case PROTECTED_BRANCH -> m.get("gui.error.protected.title");
            case NO_CHANGES -> m.get("gui.error.nochanges.title");
            case NOT_LOGGED_IN -> m.get("gui.error.notloggedin.title");
            case NOT_INITIALIZED -> m.get("gui.error.notinitialized.title");
            case GENERIC -> m.get("gui.error.generic.title");
        };
        List<Alternative> alternatives = new ArrayList<>();
        if (data.get("alternatives") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> a) {
                    Object files = a.get("files");
                    alternatives.add(new Alternative(String.valueOf(a.get("from")), String.valueOf(a.get("describe")),
                            files instanceof Number n ? n.intValue() : 0));
                }
            }
        }
        StringBuilder details = new StringBuilder();
        for (String k : List.of("stderr", "stdout", "detail", "hint")) {
            if (data.get(k) != null && !String.valueOf(data.get(k)).isBlank()) details.append(k).append(": ").append(data.get(k)).append('\n');
        }
        if (details.isEmpty() && e.getCause() != null) details.append(stack(e.getCause()));
        return new UiError(kind, title, e.getMessage(), details.toString().strip(), alternatives, e);
    }

    private static String stack(Throwable t) {
        StringBuilder sb = new StringBuilder(t.toString()).append('\n');
        for (StackTraceElement el : t.getStackTrace()) sb.append("  at ").append(el).append('\n');
        return sb.toString();
    }
}
