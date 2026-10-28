package no.syntakserror.lyngvi.core.stack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.Runner;

/**
 * What the containers actually run on: the Podman machine on macOS, the host itself on Linux.
 *
 * @param memoryBytes total memory available to containers
 * @param diskBytes   disk available to containers (the VM disk on macOS, the root file system on Linux)
 */
public record MachineInfo(String name, String state, int cpus, long memoryBytes, long diskBytes, boolean available) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static MachineInfo unavailable() {
        return new MachineInfo("", "unavailable", 0, 0, 0, false);
    }

    public static MachineInfo read(Platform platform, Runner runner) {
        return switch (platform.os()) {
            case MACOS -> readMachine(runner);
            case LINUX -> readHost();
        };
    }

    private static MachineInfo readMachine(Runner runner) {
        Runner.Result r;
        try {
            r = runner.run(List.of("podman", "machine", "inspect"), null, Duration.ofSeconds(20));
        } catch (SandboxException e) {
            return unavailable();
        }
        if (!r.success() || r.stdout().isBlank()) return unavailable();
        try {
            JsonNode machines = JSON.readTree(r.stdout());
            if (!machines.isArray() || machines.isEmpty()) return unavailable();
            JsonNode m = machines.get(0);
            JsonNode res = m.path("Resources");
            return new MachineInfo(
                    m.path("Name").asText(""),
                    m.path("State").asText("unknown"),
                    res.path("CPUs").asInt(0),
                    res.path("Memory").asLong(0) * 1024 * 1024,
                    res.path("DiskSize").asLong(0) * 1024 * 1024 * 1024,
                    true);
        } catch (IOException e) {
            return unavailable();
        }
    }

    private static MachineInfo readHost() {
        long memory = 0;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemTotal:")) {
                    memory = Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux-like: fall back to the JVM's view
        }
        if (memory == 0) {
            memory = ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        }
        long disk = 0;
        try {
            FileStore store = Files.getFileStore(Path.of(System.getProperty("user.home")));
            disk = store.getTotalSpace();
        } catch (IOException ignored) {
            // leave as unknown
        }
        return new MachineInfo("host", "running", Runtime.getRuntime().availableProcessors(), memory, disk, true);
    }
}
