package no.syntakserror.lyngvi.core.render;

import java.util.List;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.config.Profile;
import no.syntakserror.lyngvi.core.config.SandboxConfig;
import no.syntakserror.lyngvi.core.gateway.ManifestIo;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import no.syntakserror.lyngvi.core.version.Version;

/** Everything the renderers read, gathered once per render. */
record RenderInputs(Paths paths, Profile profile, SandboxConfig config, String prefix,
                    List<AgentDescriptor> agents, Manifest manifest, String version) {

    static RenderInputs gather(Sandbox sandbox, String prefix) {
        List<AgentDescriptor> agents = AgentDescriptor.readAll(sandbox.paths().agents()).stream()
                .filter(a -> sandbox.profile().agents().enabled().contains(a.id()))
                .toList();
        return new RenderInputs(sandbox.paths(), sandbox.profile(), sandbox.config(), prefix,
                agents, ManifestIo.read(sandbox.paths().gatewayManifest()), Version.current().toString());
    }

    String gatewayIp() {
        return prefix + "." + manifest.ipSuffix();
    }

    String project() {
        return paths.projectName();
    }
}
