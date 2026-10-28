package no.syntakserror.lyngvi.gateway.api;

import java.util.function.Function;

/**
 * One route. {@code pathPattern} is a literal path with {@code {name}} segments, e.g. {@code /issue/{key}}.
 * When {@code allowlistKey} is set, the runtime checks the allowlist for that key <em>before</em> the handler runs.
 */
public record Route(String method, String pathPattern, Function<Request, String> allowlistKey, Handler handler) {

    public static Route get(String pathPattern, Handler handler) {
        return new Route("GET", pathPattern, null, handler);
    }

    public static Route get(String pathPattern, Function<Request, String> allowlistKey, Handler handler) {
        return new Route("GET", pathPattern, allowlistKey, handler);
    }

    /** A write route; only served when the instance has the {@code write} capability and the profile allows it. */
    public static Route write(String method, String pathPattern, Function<Request, String> allowlistKey, Handler handler) {
        return new Route(method, pathPattern, allowlistKey, handler);
    }
}
