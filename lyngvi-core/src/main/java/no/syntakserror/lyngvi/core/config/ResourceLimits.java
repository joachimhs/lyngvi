package no.syntakserror.lyngvi.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resource limits of an agent container, in the string forms podman accepts.
 *
 * @param mem     memory limit, e.g. {@code 8g}, {@code 512m}, {@code 1073741824}
 * @param memswap memory plus swap total (not swap alone), same format as {@code mem}
 * @param cpus    CPU count, decimal, e.g. {@code 4.0}
 * @param pids    PID (thread) limit, integer, or {@code -1} for unlimited
 */
public record ResourceLimits(String mem, String memswap, String cpus, String pids) {

    private static final Pattern MEMORY = Pattern.compile("[0-9]+[bkmgBKMG]?");
    private static final Pattern CPUS = Pattern.compile("[0-9]+(\\.[0-9]+)?");
    private static final Pattern PIDS = Pattern.compile("-?[0-9]+");

    public static boolean isValidMemory(String value) {
        return value != null && MEMORY.matcher(value).matches();
    }

    public static boolean isValidCpus(String value) {
        return value != null && CPUS.matcher(value).matches();
    }

    public static boolean isValidPids(String value) {
        return value != null && PIDS.matcher(value).matches();
    }

    /** Names of fields whose values do not match their format; empty when all are valid. */
    public List<String> invalidFields() {
        List<String> bad = new ArrayList<>();
        if (!isValidMemory(mem)) bad.add("mem");
        if (!isValidMemory(memswap)) bad.add("memswap");
        if (!isValidCpus(cpus)) bad.add("cpus");
        if (!isValidPids(pids)) bad.add("pids");
        return bad;
    }

    /** A copy with the non-null fields of {@code override} applied. */
    public ResourceLimits merge(ResourceLimits override) {
        return new ResourceLimits(
                override.mem != null ? override.mem : mem,
                override.memswap != null ? override.memswap : memswap,
                override.cpus != null ? override.cpus : cpus,
                override.pids != null ? override.pids : pids);
    }
}
