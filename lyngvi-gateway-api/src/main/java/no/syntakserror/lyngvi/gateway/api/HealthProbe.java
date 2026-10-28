package no.syntakserror.lyngvi.gateway.api;

/** An optional upstream probe for {@code /healthz?deep=1}. */
@FunctionalInterface
public interface HealthProbe {

    Health probe(PluginContext ctx);

    static HealthProbe none() {
        return ctx -> null;
    }
}
