package no.syntakserror.lyngvi.core.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.config.DotEnv;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.core.runtime.Subnets;
import no.syntakserror.lyngvi.core.session.Locks;

/** Orchestrates {@code rebuild-config} in the order of spec §4.5. */
public final class Renderer {

    private final Sandbox sandbox;

    public Renderer(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public RenderReport render() {
        Paths paths = sandbox.paths();
        try (Locks.Held ignored = Locks.sandbox(paths)) {
            Subnets subnets = new Subnets(sandbox.platform().stateDir().resolve("subnets.txt"), sandbox.runner());
            String prefix = subnets.allocate(paths.root(), sandbox.config().subnetPrefix().orElse(null));
            if (!sandbox.config().subnetPrefix().map(prefix::equals).orElse(false)) {
                sandbox.updateConfigLocked(sandbox.config().withSubnetPrefix(prefix));
            }
            RenderInputs in = RenderInputs.gather(sandbox, prefix);
            List<String> written = new ArrayList<>();

            // Generated files: bind-mounted ones in place, the rest atomically.
            if (InPlace.write(paths.allowedDomains(), AllowlistRenderer.render(in))) written.add(rel(paths, paths.allowedDomains()));
            if (InPlace.write(paths.gatewayHosts(), GatewayHostsRenderer.render(in))) written.add(rel(paths, paths.gatewayHosts()));
            if (InPlace.write(paths.squidConf(), SquidConfRenderer.render(in))) written.add(rel(paths, paths.squidConf()));
            if (InPlace.write(paths.agentsMd(), AgentsMdRenderer.render(in))) written.add(rel(paths, paths.agentsMd()));
            InPlace.atomic(paths.containerfile(), ContainerfileRenderer.render(in));
            written.add(rel(paths, paths.containerfile()));
            InPlace.atomic(paths.compose(), ComposeRenderer.render(in, sandbox.platform()));
            written.add(rel(paths, paths.compose()));
            for (AgentDescriptor agent : in.agents()) {
                if (!agent.hasImage()) continue;
                Path cf = paths.agentContainerfile(agent.id());
                InPlace.atomic(cf, ContainerfileRenderer.renderAgent(in, agent));
                written.add(rel(paths, cf));
            }

            mkdirs(paths.gatewayAllowlists(), paths.gatewaySecrets(), paths.gatewayCerts(), paths.gatewayPlugins(),
                    paths.conversations(), paths.agentsState(), paths.locks(), paths.sessions(), paths.skills(),
                    ComposeRenderer.caCertsDir(in, sandbox.platform()));
            // The skills directory of each enabled agent follows the sandbox's skills; a sandbox whose state was
            // wiped gets them back here, without waiting for the next message.
            sandbox.skillService().syncAll();
            for (Instance i : in.manifest().instances()) {
                Path allowlist = paths.gatewayAllowlists().resolve(i.allowlist().file());
                if (!Files.exists(allowlist)) {
                    InPlace.write(allowlist, "# Allowlisted " + i.allowlist().label() + " for gateway instance '" + i.id() + "'. One entry per line.\n");
                    written.add(rel(paths, allowlist));
                }
                Path secret = paths.gatewaySecrets().resolve(i.id() + ".env");
                if (!Files.exists(secret)) {
                    // compose lists every instance's env file; an empty one keeps 'up' working until the secret is set.
                    InPlace.write(secret, "");
                    restrict(secret);
                }
                Path example = paths.gatewaySecrets().resolve(i.id() + ".env.example");
                InPlace.write(example, "# Copy to " + i.id() + ".env and fill in. Mounted only into the gateway container.\n"
                        + i.baseUrlEnv() + "=https://" + i.hostname() + "\n" + i.secret().env() + "=\n");
            }
            DotEnv.ensure(paths.dotEnv(), paths.dotEnvExample());

            CertGenerator certs = new CertGenerator(sandbox.runner(), paths.gatewayCerts());
            boolean generated = certs.ensure(in.manifest().hostnames(), in.gatewayIp(), false);
            if (generated) written.add(rel(paths, certs.cert()));
            return new RenderReport(prefix, written, generated);
        }
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX file system: nothing to tighten.
        }
    }

    private static String rel(Paths paths, Path file) {
        return paths.root().relativize(file).toString();
    }

    private static void mkdirs(Path... dirs) {
        for (Path d : dirs) {
            try {
                Files.createDirectories(d);
            } catch (IOException e) {
                throw new SandboxException(ErrorCode.INTERNAL, "Cannot create " + d + ": " + e.getMessage(), e);
            }
        }
    }
}
