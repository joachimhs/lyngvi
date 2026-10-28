package no.syntakserror.lyngvi.gateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.api.Health;

/** The {@code /healthz} document (spec §8.4 point 6): status per instance, plugin, routes, allowlist and write mode. */
public final class HealthReport {

    private final Config config;
    private final Map<String, String> plugins;
    private final List<String> warnings;

    public HealthReport(Config config, Map<String, String> plugins, List<String> warnings) {
        this.config = config;
        this.plugins = plugins;
        this.warnings = warnings;
    }

    public Map<String, Object> build(Collection<InstanceRuntime> instances) {
        return build(instances, false);
    }

    public Map<String, Object> build(Collection<InstanceRuntime> instances, boolean deep) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("version", config.version());
        out.put("tokenRequired", config.token() != null);
        out.put("tls", config.tlsPort() > 0);
        List<Map<String, Object>> list = new ArrayList<>();
        for (InstanceRuntime r : instances) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("id", r.instanceSpec().id());
            i.put("label", r.instanceSpec().label());
            i.put("plugin", r.plugin().type());
            i.put("pluginSource", r.source());
            i.put("hostname", r.instanceSpec().hostname());
            i.put("hostAliases", r.instanceSpec().hostAliases());
            i.put("configured", r.configured());
            i.put("secretPresent", r.secretPresent());
            i.put("missing", r.missing());
            i.put("status", r.status());
            if (deep) {
                Health h = r.probe();
                i.put("upstream", h == null ? null : Map.of("ok", h.ok(), "status", h.status(), "reason", String.valueOf(h.reason()), "user", String.valueOf(h.user())));
            }
            i.put("write", r.writeAllowed());
            i.put("capabilities", r.instanceSpec().capabilities());
            i.put("routes", r.routes().stream().map(cr -> cr.route().method() + " " + cr.route().pathPattern()).toList());
            try {
                var a = r.allowlist().describe();
                Map<String, Object> al = new LinkedHashMap<>();
                al.put("enforced", a.enforced());
                al.put("label", a.label());
                al.put("entries", a.count());
                al.put("keys", a.resolved().size());
                i.put("allowlist", al);
            } catch (RuntimeException e) {
                i.put("allowlist", Map.of("error", String.valueOf(e.getMessage())));
            }
            list.add(i);
        }
        out.put("instances", list);
        out.put("plugins", plugins);
        out.put("warnings", warnings);
        return out;
    }
}
