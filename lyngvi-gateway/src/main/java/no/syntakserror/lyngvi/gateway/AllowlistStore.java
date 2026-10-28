package no.syntakserror.lyngvi.gateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.syntakserror.lyngvi.gateway.api.Allowlist;
import no.syntakserror.lyngvi.gateway.api.AllowlistInfo;
import no.syntakserror.lyngvi.gateway.api.AllowlistResolver;
import no.syntakserror.lyngvi.gateway.api.Upstream;

/**
 * An instance's allowlist (spec §8.4 point 4): the file is re-read on every lookup when its
 * modification time changed; entries that need an upstream lookup are cached for five minutes.
 * An empty file denies everything.
 */
public final class AllowlistStore implements Allowlist {

    static final long RESOLVE_TTL_MS = 5 * 60_000L;

    private final Path file;
    private final String label;
    private final boolean enforced;
    private final AllowlistResolver resolver;
    private final Upstream upstream;
    private final MaskedLog log;
    private long lastModified = -1;
    private List<String> entries = List.of();
    private final Map<String, Cached> resolved = new HashMap<>();

    private record Cached(List<String> keys, long at) {}

    public AllowlistStore(Path file, String label, boolean enforced, AllowlistResolver resolver, Upstream upstream, MaskedLog log) {
        this.file = file;
        this.label = label;
        this.enforced = enforced;
        this.resolver = resolver;
        this.upstream = upstream;
        this.log = log;
    }

    @Override
    public boolean enforced() {
        return enforced;
    }

    private synchronized void reload() {
        try {
            long mtime = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
            if (mtime == lastModified) return;
            lastModified = mtime;
            List<String> out = new ArrayList<>();
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file)) {
                    String l = line.strip();
                    if (l.isEmpty() || l.startsWith("#")) continue;
                    out.add(l.split("\\s+")[0]);
                }
            }
            entries = out;
        } catch (IOException e) {
            log.warn("cannot read allowlist " + file + ": " + e.getMessage());
            entries = List.of();
        }
    }

    /** Current keys: parsed entries plus cached resolutions of the others. */
    synchronized Set<String> keys() {
        reload();
        Set<String> keys = new HashSet<>();
        long now = System.currentTimeMillis();
        for (String entry : entries) {
            String direct = resolver.parse(entry);
            if (direct != null) {
                keys.add(direct);
                continue;
            }
            Cached c = resolved.get(entry);
            if (c == null || now - c.at() > RESOLVE_TTL_MS) {
                List<String> r;
                try {
                    r = resolver.resolve(entry, upstream);
                } catch (RuntimeException e) {
                    log.warn("cannot resolve allowlist entry '" + entry + "': " + e.getMessage());
                    r = List.of();
                }
                c = new Cached(r == null ? List.of() : r, now);
                resolved.put(entry, c);
            }
            keys.addAll(c.keys());
        }
        return keys;
    }

    @Override
    public boolean allows(String key) {
        if (!enforced) return true;
        if (key == null || key.isBlank()) return false;
        return resolver.matches(key, keys());
    }

    @Override
    public AllowlistInfo describe() {
        Set<String> keys = keys();
        return new AllowlistInfo(enforced, label, file.toString(), entries.size(), List.copyOf(entries), new ArrayList<>(keys).stream().sorted().toList());
    }
}
