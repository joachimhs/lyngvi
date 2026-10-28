package no.syntakserror.lyngvi.core.config;

import java.util.List;
import java.util.Optional;
import no.syntakserror.lyngvi.core.i18n.Messages;

/**
 * A named set of limits for one kind of work, so a session can be sized for what it is about to do
 * instead of by one number that has to suit reading a diff and building a monorepo alike.
 *
 * <p>The profile owns the list ({@code resourcePresets}); {@link #BUILT_IN} is what a profile that
 * names none gets.
 *
 * @param id     stable id: what {@code --preset} takes and what front-ends translate
 * @param label  the profile's own label; {@code null} means "translate {@link #labelKey()}"
 * @param limits the four limits, all of them set
 */
public record ResourcePreset(String id, String label, ResourceLimits limits) {

    /**
     * Light review, everyday development, heavy builds. {@code memswap} is 1.5 x {@code mem} throughout,
     * the ratio {@link no.syntakserror.lyngvi.core.stack.ResourceAdvice} recommends, and {@code pids}
     * counts threads, so it stays well above what a JVM or a bundler starts.
     */
    public static final List<ResourcePreset> BUILT_IN = List.of(
            new ResourcePreset("review", null, new ResourceLimits("2g", "3g", "1.0", "1024")),
            new ResourcePreset("dev", null, new ResourceLimits("8g", "12g", "4.0", "2048")),
            new ResourcePreset("build", null, new ResourceLimits("16g", "24g", "6.0", "4096")));

    public static Optional<ResourcePreset> find(List<ResourcePreset> presets, String id) {
        return presets.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** The message key a front-end looks up when the profile gives no label of its own. */
    public String labelKey() {
        return "resources.preset." + id;
    }

    /** What to show a human: the profile's label, else the translated id, else the id as it stands. */
    public String label(Messages m) {
        if (label != null && !label.isBlank()) return label;
        String translated = m.get(labelKey());
        return translated.equals("[" + labelKey() + "]") ? id : translated;
    }
}
