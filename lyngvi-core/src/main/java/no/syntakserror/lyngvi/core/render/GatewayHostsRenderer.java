package no.syntakserror.lyngvi.core.render;

/** {@code proxy/gateway-hosts.txt}: hostnames Squid routes to the gateway. */
final class GatewayHostsRenderer {

    static final String HEADER = "# Hostnames routed to the gateway. Rendered by 'setup.sh rebuild-config' from gateway/gateway.manifest.json.\n";

    private GatewayHostsRenderer() {}

    static String render(RenderInputs in) {
        StringBuilder sb = new StringBuilder(HEADER);
        for (String host : in.manifest().hostnames()) {
            sb.append(host).append('\n');
        }
        return sb.toString();
    }
}
