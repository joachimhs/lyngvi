package no.syntakserror.lyngvi.core.stack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.contract.DiskReport;
import no.syntakserror.lyngvi.core.runtime.Runner;

/**
 * Sizes of containers and volumes, read from {@code podman system df -v --format json}.
 *
 * <p>That command is the only one that knows what a volume takes, but its field names have not been
 * stable between Podman versions, so this parser asks for several spellings and gives up quietly: an
 * object it cannot size is reported as {@link DiskReport#UNKNOWN_SIZE}, never as zero. Everything else in
 * the inventory — what exists, and what is reclaimable — comes from commands the core already relies on,
 * so a sandbox on an unexpected Podman still gets a working {@code clean}, only without the numbers.
 */
public final class DiskSizes {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CONTAINER_NAME = List.of("Names", "Name", "ContainerID", "Id");
    private static final List<String> CONTAINER_SIZE = List.of("RWSize", "Size", "SizeRw");
    private static final List<String> VOLUME_NAME = List.of("VolumeName", "Name");
    private static final List<String> VOLUME_SIZE = List.of("Size", "ReclaimableSize");

    private final Map<String, Long> containers = new HashMap<>();
    private final Map<String, Long> volumes = new HashMap<>();

    private DiskSizes() {}

    /** Reads the sizes; an empty instance (everything unknown) when Podman cannot be asked. */
    public static DiskSizes read(Runner runner, Path cwd) {
        DiskSizes sizes = new DiskSizes();
        Runner.Result r;
        try {
            r = runner.run(List.of("podman", "system", "df", "-v", "--format", "json"), cwd, SessionVolume.SHORT);
        } catch (SandboxException e) {
            return sizes;
        }
        if (!r.success() || r.stdout().isBlank()) return sizes;
        try {
            JsonNode root = JSON.readTree(r.stdout());
            collect(root.path("Containers"), CONTAINER_NAME, CONTAINER_SIZE, sizes.containers);
            collect(root.path("Volumes"), VOLUME_NAME, VOLUME_SIZE, sizes.volumes);
        } catch (Exception ignored) {
            // an output shape we do not know: sizes stay unknown, the inventory does not
        }
        return sizes;
    }

    private static void collect(JsonNode array, List<String> nameFields, List<String> sizeFields, Map<String, Long> into) {
        if (!array.isArray()) return;
        for (JsonNode node : array) {
            String name = first(node, nameFields);
            long size = size(node, sizeFields);
            if (name != null && size >= 0) into.put(name, size);
        }
    }

    private static String first(JsonNode node, List<String> fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isArray() && !value.isEmpty()) return value.get(0).asText();
            if (value.isTextual() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    /** Podman has reported sizes both as numbers and as strings like {@code "166.5MB"}. */
    private static long size(JsonNode node, List<String> fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) return value.asLong();
            if (value.isTextual()) {
                long parsed = ResourceSizes.parseHuman(value.asText());
                if (parsed >= 0) return parsed;
            }
        }
        return -1;
    }

    public long container(String name) {
        return containers.getOrDefault(name, DiskReport.UNKNOWN_SIZE);
    }

    public long volume(String name) {
        return volumes.getOrDefault(name, DiskReport.UNKNOWN_SIZE);
    }
}
