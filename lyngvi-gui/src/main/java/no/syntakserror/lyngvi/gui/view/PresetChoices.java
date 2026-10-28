package no.syntakserror.lyngvi.gui.view;

import java.util.List;
import java.util.Optional;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.config.ResourcePreset;
import no.syntakserror.lyngvi.core.i18n.Messages;

/** How a resource preset reads in a picker, and which one a set of limits corresponds to. */
public final class PresetChoices {

    private PresetChoices() {}

    /** «Development - tests, bundlers, linters  (8g, 4.0 cpus)»: the two numbers that decide the choice. */
    public static String describe(ResourcePreset p, Messages m) {
        return p.label(m) + "  (" + p.limits().mem() + ", " + p.limits().cpus() + " cpus)";
    }

    /** The preset these limits came from, if any; empty means the limits are the user's own. */
    public static Optional<ResourcePreset> matching(List<ResourcePreset> presets, ResourceLimits limits) {
        return limits == null ? Optional.empty() : presets.stream().filter(p -> p.limits().equals(limits)).findFirst();
    }
}
