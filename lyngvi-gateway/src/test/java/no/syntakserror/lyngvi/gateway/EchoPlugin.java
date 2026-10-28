package no.syntakserror.lyngvi.gateway;

import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.gateway.api.AllowlistResolver;
import no.syntakserror.lyngvi.gateway.api.GatewayPlugin;
import no.syntakserror.lyngvi.gateway.api.Response;
import no.syntakserror.lyngvi.gateway.api.Route;

/** A minimal test plugin: {@code GET /echo/{word}} allowlisted by word. */
public class EchoPlugin implements GatewayPlugin {
    @Override public String type() { return "echo"; }
    @Override public String label() { return "Echo"; }
    @Override public AllowlistResolver allowlist() { return AllowlistResolver.exact(); }
    @Override public List<Route> routes() {
        return List.of(Route.get("/echo/{word}", r -> r.param("word"), (r, ctx) -> Response.json(200, ctx.json().write(Map.of("echo", r.param("word"))))));
    }
}
