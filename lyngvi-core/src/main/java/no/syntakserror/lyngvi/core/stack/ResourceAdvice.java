package no.syntakserror.lyngvi.core.stack;

import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.config.ResourceLimits;

/** Warnings when resource limits do not fit the machine (spec §6 in 1.1.2, kept). */
public final class ResourceAdvice {

    /** One piece of advice; {@code code} is stable for front-ends to translate. */
    public record Advice(String code, String message) {}

    private ResourceAdvice() {}

    public static List<Advice> evaluate(ResourceLimits limits, MachineInfo machine) {
        List<Advice> out = new ArrayList<>();
        if (!machine.available()) {
            out.add(new Advice("MACHINE_UNAVAILABLE", "The Podman machine is not available; limits cannot be checked against it"));
            return out;
        }
        long mem = ResourceSizes.parse(limits.mem());
        long memswap = ResourceSizes.parse(limits.memswap());
        double cpus = Double.parseDouble(limits.cpus());
        long pids = Long.parseLong(limits.pids());
        if (machine.memoryBytes() > 0 && mem > machine.memoryBytes()) {
            out.add(new Advice("MEM_EXCEEDS_MACHINE", "Memory limit " + ResourceSizes.format(mem) + " exceeds the machine's "
                    + ResourceSizes.format(machine.memoryBytes()) + "; the container would be OOM-killed"));
        }
        if (machine.cpus() > 0 && cpus > machine.cpus()) {
            out.add(new Advice("CPUS_EXCEED_MACHINE", "CPU limit " + limits.cpus() + " exceeds the machine's " + machine.cpus() + " cores"));
        }
        if (memswap <= mem) {
            out.add(new Advice("MEMSWAP_EQUALS_MEM", "memswap (" + limits.memswap() + ") is not above mem (" + limits.mem()
                    + "), so the container has no swap; 1.5 x mem is recommended"));
        }
        if (pids >= 0 && pids < 512) {
            out.add(new Advice("PIDS_LOW", "pids " + pids + " counts threads; a JVM alone uses 50-100, so 2048 is recommended"));
        }
        return out;
    }
}
