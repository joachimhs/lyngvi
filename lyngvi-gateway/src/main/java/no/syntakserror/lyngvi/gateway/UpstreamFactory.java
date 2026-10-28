package no.syntakserror.lyngvi.gateway;

import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Upstream;

/** Creates the upstream client for an instance; the harness swaps in a stub. */
@FunctionalInterface
public interface UpstreamFactory {
    Upstream create(Instance instance, String baseUrl, String restBase, String secret, Config config, MaskedLog log);
}
