package no.syntakserror.lyngvi.gateway.api;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * A gateway plugin type (spec §8.6): how to talk to one class of systems.
 *
 * <p>Ship it in a jar with a {@code META-INF/services/no.syntakserror.lyngvi.gateway.api.GatewayPlugin}
 * entry and drop the jar into {@code gateway/plugins/}. The runtime enforces method, allowlist,
 * origin lock, size and time limits around every route; the plugin only maps requests to upstream calls.
 */
public interface GatewayPlugin {

    int API_VERSION = 1;

    /** The type name instances refer to in {@code gateway.manifest.json}. {@code ^[a-z][a-z0-9-]*$}. */
    String type();

    default String label() {
        return type();
    }

    /** Noun for allowlist entries, shown in the CLI and GUI ("pages", "issues"). */
    default String allowlistLabel() {
        return "entries";
    }

    default int apiVersion() {
        return API_VERSION;
    }

    /** Type of {@code instance.config}; the runtime deserialises it (unknown fields rejected). {@code Void} means none. */
    default Class<?> configType() {
        return Void.class;
    }

    /** Allowlist semantics: how a raw entry becomes a canonical key. */
    AllowlistResolver allowlist();

    /** The path under the base URL that every upstream call is prefixed with, e.g. {@code /rest/api}. Default: none. */
    default String restBase(Instance instance) {
        return "";
    }

    /** Per-instance semantics; the default ignores the instance. The built-in {@code http} plugin uses {@code allowlist.mode}. */
    default AllowlistResolver allowlistFor(Instance instance) {
        return allowlist();
    }

    /** The routes, matched in order. */
    List<Route> routes();

    /** Optional: a browser URL pasted by the agent, translated to a route of this plugin. */
    default Optional<RouteTarget> parseBrowserUrl(URI url) {
        return Optional.empty();
    }

    /** Optional: an upstream probe for {@code /healthz?deep=1}. */
    default HealthProbe health() {
        return HealthProbe.none();
    }
}
