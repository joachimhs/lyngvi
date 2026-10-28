package no.syntakserror.lyngvi.core.stack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates denied requests per host, first seen first. Bounded to keep memory flat when following a log. */
public final class BlockedRequests {

    static final int MAX_HOSTS = 500;
    private final Map<String, BlockedRequest> byHost = new LinkedHashMap<>();

    public synchronized BlockedRequest add(SquidLogParser.Denied d) {
        BlockedRequest prev = byHost.get(d.host());
        BlockedRequest next = prev == null
                ? new BlockedRequest(d.host(), 1, d.at(), d.at(), d.status(), d.method(), d.url())
                : new BlockedRequest(d.host(), prev.count() + 1, prev.firstSeen(), Math.max(prev.lastSeen(), d.at()), d.status(), d.method(), d.url());
        byHost.put(d.host(), next);
        if (byHost.size() > MAX_HOSTS) {
            byHost.remove(byHost.keySet().iterator().next());
        }
        return next;
    }

    /** Drops a host, e.g. after it was allowed. */
    public synchronized void remove(String host) {
        byHost.remove(host);
    }

    public synchronized List<BlockedRequest> snapshot() {
        return new ArrayList<>(byHost.values());
    }
}
