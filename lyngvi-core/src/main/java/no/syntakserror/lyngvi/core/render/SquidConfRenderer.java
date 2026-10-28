package no.syntakserror.lyngvi.core.render;

import java.util.stream.Collectors;
import no.syntakserror.lyngvi.core.config.Profile;

/** {@code proxy/squid.conf} from its template; the gateway peer only when instances exist (spec §7.4). */
final class SquidConfRenderer {

    private SquidConfRenderer() {}

    static String render(RenderInputs in) {
        String template = InPlace.read(in.paths().squidConfTemplate());
        boolean hasGateway = !in.manifest().hostnames().isEmpty();
        String acl = hasGateway
                ? "acl gateway_hosts dstdomain \"/etc/squid/gateway-hosts.txt\"\n"
                  + "cache_peer " + in.gatewayIp() + " parent 8080 0 no-query originserver name=gateway\n"
                  + "cache_peer_access gateway allow gateway_hosts\n"
                  + "cache_peer_access gateway deny all\n"
                  + "never_direct allow gateway_hosts"
                : "# gateway: no instances configured (gateway/gateway.manifest.json)";
        String access = hasGateway
                ? "http_access allow !CONNECT gateway_hosts\nhttp_access deny CONNECT gateway_hosts"
                : "# gateway: no instances configured";
        Profile.LocalModels local = in.profile().localModels();
        String localAcl = local.enabled()
                ? "acl local_models_host dstdomain " + local.host() + "\n"
                  + "acl local_models_port port " + local.ports().stream().map(String::valueOf).collect(Collectors.joining(" "))
                : "# local models: no ports open (profile.json localModels.ports)";
        String localAccess = local.enabled()
                ? "http_access allow local_models_host local_models_port"
                : "# local models: no ports open";
        return template.replace("{{GATEWAY_ACL}}", acl).replace("{{GATEWAY_ACCESS}}", access)
                .replace("{{LOCAL_MODELS_ACL}}", localAcl).replace("{{LOCAL_MODELS_ACCESS}}", localAccess);
    }
}
