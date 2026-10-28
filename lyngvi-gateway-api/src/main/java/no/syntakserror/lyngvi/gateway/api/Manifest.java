package no.syntakserror.lyngvi.gateway.api;

import java.util.ArrayList;
import java.util.List;

/** {@code gateway/gateway.manifest.json} (spec §8.3). Shared by the host core and the runtime. */
public record Manifest(int schemaVersion, int ipSuffix, List<Instance> instances) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public static Manifest empty() {
        return new Manifest(CURRENT_SCHEMA_VERSION, 10, List.of());
    }

    public Manifest {
        instances = List.copyOf(instances);
    }

    /** All hostnames and aliases, deduplicated, in manifest order. */
    public List<String> hostnames() {
        List<String> out = new ArrayList<>();
        for (Instance i : instances) {
            if (i.hostname() != null && !i.hostname().isBlank() && !out.contains(i.hostname())) out.add(i.hostname());
            for (String a : i.hostAliases()) {
                if (a != null && !a.isBlank() && !out.contains(a)) out.add(a);
            }
        }
        return out;
    }

    public Instance instance(String id) {
        return instances.stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
    }
}
