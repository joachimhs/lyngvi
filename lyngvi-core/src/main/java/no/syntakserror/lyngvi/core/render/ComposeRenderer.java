package no.syntakserror.lyngvi.core.render;

import java.nio.file.Files;
import java.nio.file.Path;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.core.runtime.Platform;

/** {@code docker-compose.yml} for the shared stack (spec §7.3). */
final class ComposeRenderer {

    private ComposeRenderer() {}

    static String render(RenderInputs in, Platform platform) {
        String compose = ContainerfileRenderer.substitute(InPlace.read(in.paths().composeTemplate()), in);
        String gatewayService;
        if (Files.isRegularFile(in.paths().gatewayContainerfile())) {
            gatewayService = ContainerfileRenderer.substitute(InPlace.read(in.paths().composeGatewayTemplate()), in)
                    .replace("{{CA_CERTS_DIR}}", platform.expand(in.profile().ca().certsDir()).toString())
                    .replace("{{GATEWAY_ALLOW_WRITE}}", String.join(",", in.profile().gateway().allowWrite()));
            StringBuilder envFiles = new StringBuilder();
            for (Instance i : in.manifest().instances()) {
                envFiles.append("      - ./gateway/secrets/").append(i.id()).append(".env\n");
            }
            if (envFiles.isEmpty()) {
                gatewayService = gatewayService.replace("    env_file:\n{{GATEWAY_ENV_FILES}}\n", "");
            } else {
                gatewayService = gatewayService.replace("{{GATEWAY_ENV_FILES}}\n", envFiles.toString());
            }
            gatewayService = gatewayService.stripTrailing();
        } else {
            gatewayService = "  # gateway: not built yet (no gateway/Containerfile); added by rebuild-config once it exists";
        }
        return compose.replace("{{GATEWAY_SERVICE}}", gatewayService);
    }

    static Path caCertsDir(RenderInputs in, Platform platform) {
        return platform.expand(in.profile().ca().certsDir());
    }
}
