package no.syntakserror.lyngvi.core.doctor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.StackService;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.gateway.ManifestIo;
import no.syntakserror.lyngvi.gateway.api.Manifest;
import no.syntakserror.lyngvi.core.render.CertGenerator;
import no.syntakserror.lyngvi.core.runtime.Platform;
import no.syntakserror.lyngvi.core.runtime.Subnets;
import no.syntakserror.lyngvi.core.stack.MachineInfo;

/** Checks that need the rendered files and Podman: machine, generated files, subnet, mounts, certificate, image. */
public final class PodmanChecks {

    private static final Pattern WORK_MOUNT = Pattern.compile(":/work(/|:|\\s|$)");
    /** When leftovers are worth saying out loud rather than just counting. */
    private static final int RECLAIMABLE_WARN = 20;
    private static final long BYTES_WARN = 5L << 30;

    private PodmanChecks() {}

    public static List<Check> run(Sandbox sandbox) {
        List<Check> checks = new ArrayList<>();
        Paths p = sandbox.paths();
        if (sandbox.platform().os() == Platform.Os.MACOS) {
            checks.add(machine(sandbox));
        }
        checks.add(generated(p));
        if (Files.isRegularFile(p.compose())) {
            checks.add(subnet(sandbox));
            checks.add(composeMounts(p));
            checks.add(cert(sandbox));
            checks.add(image(sandbox));
            checks.add(disk(sandbox));
        }
        return checks;
    }

    /**
     * What the sandbox has left lying about. Sessions accumulate working copies and containers that
     * nothing removes by itself, and a version bump leaves a whole set of images behind; none of it is
     * visible unless something counts it. A warning at worst — there is nothing wrong with a sandbox that
     * has a lot of sessions, only something to do about it.
     */
    private static Check disk(Sandbox sandbox) {
        no.syntakserror.lyngvi.core.contract.DiskReport report;
        try {
            report = sandbox.cleanupService().report(no.syntakserror.lyngvi.core.api.CleanOptions.report());
        } catch (RuntimeException e) {
            return Check.ok("disk", "disk usage not read: " + e.getMessage());
        }
        int count = report.reclaimable().size();
        if (count == 0) {
            return Check.ok("disk", "nothing left over from finished sessions");
        }
        String size = report.reclaimableBytes() > 0
                ? no.syntakserror.lyngvi.core.stack.ResourceSizes.format(report.reclaimableBytes())
                        + (report.sizesIncomplete() ? "+" : "")
                : "unknown size";
        String message = count + " object(s) can be reclaimed (" + size + ")";
        String hint = "Run 'setup.sh clean' to see them, and 'setup.sh clean --yes' to remove them";
        return count >= RECLAIMABLE_WARN || report.reclaimableBytes() >= BYTES_WARN
                ? Check.warn("disk", message, hint)
                : Check.ok("disk", message + "; " + hint.toLowerCase(java.util.Locale.ROOT));
    }

    private static Check machine(Sandbox sandbox) {
        MachineInfo m = MachineInfo.read(sandbox.platform(), sandbox.runner());
        if (!m.available()) {
            return Check.fail("machine", "No Podman machine found", "Create and start one: podman machine init && podman machine start");
        }
        if (!"running".equalsIgnoreCase(m.state())) {
            return Check.fail("machine", "Podman machine '" + m.name() + "' is " + m.state(), "Start it: podman machine start");
        }
        return Check.ok("machine", "Podman machine '" + m.name() + "' is running (" + m.cpus() + " CPUs, "
                + no.syntakserror.lyngvi.core.stack.ResourceSizes.format(m.memoryBytes()) + ")");
    }

    private static Check generated(Paths p) {
        List<Path> outputs = List.of(p.containerfile(), p.compose(), p.squidConf(), p.allowedDomains(), p.gatewayHosts(), p.agentsMd());
        List<String> missing = new ArrayList<>();
        for (Path o : outputs) {
            if (!Files.isRegularFile(o)) missing.add(p.root().relativize(o).toString());
        }
        if (!missing.isEmpty()) {
            return Check.fail("generated", "Generated files missing: " + String.join(", ", missing), "Run 'setup.sh rebuild-config'");
        }
        long newestSource = newest(List.of(p.profileJson(), p.sandboxConfig(), p.templates(), p.imageBase(), p.image().resolve("fragments"),
                p.agents(), p.squidConfTemplate(), p.gatewayManifest()));
        long oldestOutput = outputs.stream().mapToLong(PodmanChecks::mtime).min().orElse(0);
        if (newestSource > oldestOutput) {
            return Check.warn("generated", "Generated files are older than their sources", "Run 'setup.sh rebuild-config'");
        }
        return Check.ok("generated", "generated files present and current");
    }

    private static Check subnet(Sandbox sandbox) {
        String prefix = sandbox.config().subnetPrefix().orElse(null);
        if (prefix == null) {
            return Check.fail("subnet", "No subnet prefix allocated", "Run 'setup.sh rebuild-config'");
        }
        String compose;
        try {
            compose = Files.readString(sandbox.paths().compose());
        } catch (IOException e) {
            return Check.fail("subnet", "Cannot read docker-compose.yml", "Run 'setup.sh rebuild-config'");
        }
        if (!compose.contains(prefix + ".0/24")) {
            return Check.fail("subnet", "docker-compose.yml does not use subnet " + prefix + ".0/24", "Run 'setup.sh rebuild-config'");
        }
        Subnets subnets = new Subnets(sandbox.platform().stateDir().resolve("subnets.txt"), sandbox.runner());
        String ours = sandbox.paths().projectName() + "_internal";
        for (var net : subnets.podmanNetworks()) {
            if (net.prefixes().contains(prefix) && !net.name().equals(ours)) {
                return Check.fail("subnet", "Podman network '" + net.name() + "' already uses " + prefix + ".0/24",
                        "Remove that network or run 'setup.sh rebuild-config' after releasing the prefix");
            }
        }
        return Check.ok("subnet", prefix + ".0/24 (" + ours + ")");
    }

    private static Check composeMounts(Paths p) {
        try {
            for (String line : Files.readAllLines(p.compose())) {
                if (WORK_MOUNT.matcher(line).find()) {
                    return Check.fail("compose-mounts", "docker-compose.yml mounts something at /work: " + line.strip(),
                            "Never mount repositories into the stack; sessions get their own containers. Run 'setup.sh rebuild-config'");
                }
            }
        } catch (IOException e) {
            return Check.fail("compose-mounts", "Cannot read docker-compose.yml", "Run 'setup.sh rebuild-config'");
        }
        return Check.ok("compose-mounts", "no /work mounts in the stack");
    }

    private static Check cert(Sandbox sandbox) {
        Paths p = sandbox.paths();
        CertGenerator certs = new CertGenerator(sandbox.runner(), p.gatewayCerts());
        if (!Files.isRegularFile(certs.cert()) || !Files.isRegularFile(certs.key())) {
            return Check.warn("cert", "gateway certificate missing", "Run 'setup.sh rebuild-config'");
        }
        Manifest manifest = ManifestIo.read(p.gatewayManifest());
        String ip = sandbox.config().subnetPrefix().orElse("0.0.0") + "." + manifest.ipSuffix();
        if (!certs.covers(manifest.hostnames(), ip)) {
            return Check.warn("cert", "gateway certificate does not cover all gateway hostnames", "Run 'setup.sh rebuild-config' or 'setup.sh gateway cert renew'");
        }
        return Check.ok("cert", "gateway certificate covers " + (manifest.hostnames().isEmpty() ? "the gateway" : String.join(", ", manifest.hostnames())));
    }

    private static Check image(Sandbox sandbox) {
        if (!sandbox.stackService().baseImageExists()) {
            return Check.warn("image", StackService.baseImage() + " is not built", "Run 'setup.sh build'");
        }
        return Check.ok("image", StackService.baseImage() + " present");
    }

    private static long newest(List<Path> sources) {
        long newest = 0;
        for (Path s : sources) {
            if (Files.isDirectory(s)) {
                try (var files = Files.walk(s)) {
                    newest = Math.max(newest, files.filter(Files::isRegularFile).mapToLong(PodmanChecks::mtime).max().orElse(0));
                } catch (IOException ignored) {
                    // skip unreadable directory
                }
            } else {
                newest = Math.max(newest, mtime(s));
            }
        }
        return newest;
    }

    private static long mtime(Path f) {
        try {
            return Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }
}
