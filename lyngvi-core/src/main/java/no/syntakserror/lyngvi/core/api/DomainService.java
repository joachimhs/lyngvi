package no.syntakserror.lyngvi.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.render.Allowlists;
import no.syntakserror.lyngvi.core.session.Locks;
import no.syntakserror.lyngvi.core.stack.DomainEntry;

/** The egress allowlist's user section: {@code domain list|add|remove|reload}. */
public final class DomainService {

    private static final Pattern DOMAIN = Pattern.compile("\\.?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");

    private final Sandbox sandbox;

    DomainService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    /** Profile entries, then agent entries, then user entries. */
    public List<DomainEntry> list() {
        List<DomainEntry> out = new ArrayList<>();
        for (String d : sandbox.profile().egress().allow()) out.add(new DomainEntry(d, "profile", ""));
        for (AgentDescriptor a : AgentDescriptor.readAll(sandbox.paths().agents())) {
            if (!sandbox.profile().agents().enabled().contains(a.id())) continue;
            for (String h : a.egress()) out.add(new DomainEntry(h, "agent:" + a.id(), ""));
        }
        out.addAll(Allowlists.userEntries(sandbox.paths().allowedDomains()));
        return out;
    }

    public DomainEntry add(String domain, Optional<String> note) {
        String d = domain.strip().toLowerCase();
        if (!DOMAIN.matcher(d).matches()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Not a valid domain: '" + domain + "' (use example.com or .example.com for subdomains)",
                    Map.of("domain", domain));
        }
        for (DomainEntry e : list()) {
            if (e.domain().equals(d)) {
                String where = e.source().equals("user") ? "already in the user section" : "already allowed by the " + e.source() + " section";
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, d + " is " + where, Map.of("domain", d, "source", e.source()));
            }
        }
        DomainEntry entry = new DomainEntry(d, "user", note.orElse(""));
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            Allowlists.appendUser(sandbox.paths().allowedDomains(), entry);
        }
        sandbox.stackService().reloadProxy();
        return entry;
    }

    public DomainEntry remove(String domain) {
        String d = domain.strip().toLowerCase();
        for (DomainEntry e : list()) {
            if (e.domain().equals(d) && !e.source().equals("user")) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, d + " comes from the " + e.source() + " section and cannot be removed here",
                        Map.of("domain", d, "source", e.source()));
            }
        }
        DomainEntry removed;
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            removed = Allowlists.removeUser(sandbox.paths().allowedDomains(), d).orElseThrow(() -> new SandboxException(
                    ErrorCode.VALIDATION_FAILED, d + " is not in the user section of the allowlist", Map.of("domain", d)));
        }
        sandbox.stackService().reloadProxy();
        return removed;
    }

    /** Forces Squid to re-read the allowlist. Returns false when the proxy is not running. */
    public boolean reload() {
        return sandbox.stackService().reloadProxy();
    }
}
