package no.syntakserror.lyngvi.gateway.api;

/** Handles one matched request. Throw {@link GatewayException} for a specific status; anything else becomes 502. */
@FunctionalInterface
public interface Handler {
    Response handle(Request request, PluginContext ctx) throws Exception;
}
