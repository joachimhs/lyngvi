package no.syntakserror.lyngvi.core.ask;

import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.stack.SessionVolumes;

/**
 * What built-in adapters need from the sandbox.
 *
 * @param subnetPrefix the internal network's prefix, null before {@code rebuild-config}
 * @param version      the product version (image tags)
 * @param volumes      the session volumes; an adapter that writes files writes them there, not on the host
 */
public record AgentContext(Paths paths, Platform platform, Runner runner, String subnetPrefix, String version,
                           SessionVolumes volumes) {

    public String project() {
        return paths.projectName();
    }

    public String proxyUrl() {
        return "http://" + subnetPrefix + ".5:3128";
    }

    public String gatewayIp() {
        return subnetPrefix + ".10";
    }
}
