package no.syntakserror.lyngvi.gateway.api;

/** What a handler gets from the runtime for one instance. The secret is never exposed. */
public interface PluginContext {

    Instance instance();

    /** The instance's {@code config}, deserialised to the plugin's {@link GatewayPlugin#configType()}. */
    <T> T config(Class<T> type);

    Upstream upstream();

    Allowlist allowlist();

    TextUtil text();

    Json json();

    Log log();

    /** Query {@code limit} clamped to the runtime's {@code MAX_LIMIT}; default 25 when absent or invalid. */
    int limit(Request request);
}
