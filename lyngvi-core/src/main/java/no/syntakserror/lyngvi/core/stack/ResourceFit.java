package no.syntakserror.lyngvi.core.stack;

import java.util.Optional;
import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.config.ResourcePreset;

/** How limits relate to the machine: whether one session fits, and how many fit at the same time. */
public final class ResourceFit {

    /** Returned by {@link #sessions} when the machine cannot be inspected. */
    public static final int UNKNOWN = -1;

    private ResourceFit() {}

    /**
     * How many sessions with these limits the machine holds at once. Only memory decides: a CPU quota is
     * time-shared, so more sessions than cores merely run slower, whereas memory is not — the limits are
     * caps rather than reservations, and when they are all drawn on at once the machine itself runs out.
     */
    public static int sessions(ResourceLimits limits, MachineInfo machine) {
        if (!machine.available() || machine.memoryBytes() <= 0) return UNKNOWN;
        try {
            long mem = ResourceSizes.parse(limits.mem());
            return mem <= 0 ? UNKNOWN : (int) (machine.memoryBytes() / mem);
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    /** False when a single session of this size is already more than the machine has. */
    public static boolean fits(ResourceLimits limits, MachineInfo machine) {
        return ResourceAdvice.evaluate(limits, machine).stream()
                .noneMatch(a -> a.code().equals("MEM_EXCEEDS_MACHINE") || a.code().equals("CPUS_EXCEED_MACHINE"));
    }

    /**
     * A preset sized to this machine: three quarters of its memory (rounded down to whole GiB) and every
     * core — one session at a time, nothing else running. Empty when the machine is unknown.
     */
    public static Optional<ResourcePreset> machineMax(MachineInfo machine) {
        if (!machine.available() || machine.memoryBytes() <= 0 || machine.cpus() <= 0) return Optional.empty();
        long gib = Math.max(1, (machine.memoryBytes() * 3 / 4) / (1L << 30));
        // memswap is the memory-plus-swap total, and 1.5 x mem is what ResourceAdvice asks for: never the same as mem.
        long swapTotal = Math.max(gib + 1, gib * 3 / 2);
        return Optional.of(new ResourcePreset("machine", null,
                new ResourceLimits(gib + "g", swapTotal + "g", machine.cpus() + ".0", "4096")));
    }
}
