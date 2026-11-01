package no.syntakserror.lyngvi.gui;

import java.util.ResourceBundle;
import no.syntakserror.lyngvi.core.i18n.Messages;
import no.syntakserror.lyngvi.gui.view.Languages;
import no.syntakserror.lyngvi.gui.viewmodel.AgentsViewModel;
import no.syntakserror.lyngvi.gui.viewmodel.AppLog;
import no.syntakserror.lyngvi.gui.viewmodel.Async;
import no.syntakserror.lyngvi.gui.viewmodel.ConfigViewModel;
import no.syntakserror.lyngvi.gui.viewmodel.GatewayViewModel;
import no.syntakserror.lyngvi.gui.viewmodel.LenientBundle;
import no.syntakserror.lyngvi.gui.viewmodel.SandboxContext;
import no.syntakserror.lyngvi.gui.viewmodel.SessionViewModel;
import no.syntakserror.lyngvi.gui.viewmodel.Settings;
import no.syntakserror.lyngvi.gui.viewmodel.SkillsViewModel;
import no.syntakserror.lyngvi.gui.viewmodel.StackViewModel;

/**
 * Everything a controller needs, created once by {@link App} and injected through the FXML controller factory.
 *
 * <p>{@code languages} is the odd one out: the colouring rules for source files are the user's, not the sandbox's,
 * so they come from the home directory rather than from the core, and they are read once per window.
 */
public record Services(SandboxContext ctx, Settings settings, AppLog log, Async async, StackViewModel stack, ConfigViewModel config,
                       SessionViewModel sessions, GatewayViewModel gateway, AgentsViewModel agents, SkillsViewModel skills,
                       Languages languages) {

    public static Services create(SandboxContext ctx, Settings settings, AppLog log, Async async) {
        return new Services(ctx, settings, log, async, new StackViewModel(ctx, async, log), new ConfigViewModel(ctx, async, log),
                new SessionViewModel(ctx, async, log), new GatewayViewModel(ctx, async, log), new AgentsViewModel(ctx, async, log),
                new SkillsViewModel(ctx, async, log), Languages.inHome(ctx.platform().home()));
    }

    public Messages messages() {
        return ctx.messages();
    }

    /** Views read the bundle through {@link LenientBundle}: a missing key is a marker and a log line, not a crash. */
    public ResourceBundle bundle() {
        return new LenientBundle(ctx.messages().bundle(), log);
    }
}
