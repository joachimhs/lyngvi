package no.syntakserror.lyngvi.core.api;

import no.syntakserror.lyngvi.core.template.Templates;
import no.syntakserror.lyngvi.core.session.Locks;
import no.syntakserror.lyngvi.core.contract.UpgradeReport;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.contract.DoctorReport;
import no.syntakserror.lyngvi.core.doctor.Check;
import no.syntakserror.lyngvi.core.doctor.PodmanChecks;
import no.syntakserror.lyngvi.core.render.RenderReport;
import no.syntakserror.lyngvi.core.render.Renderer;
import no.syntakserror.lyngvi.core.contract.RepoInfo;
import no.syntakserror.lyngvi.core.contract.StatusReport;
import no.syntakserror.lyngvi.core.doctor.HostChecks;
import no.syntakserror.lyngvi.core.version.Version;

/** Sandbox-wide operations: status and doctor (more arrive with later increments). */
public final class SandboxService {

    private final Sandbox sandbox;

    SandboxService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public StatusReport status() {
        var c = sandbox.config();
        return new StatusReport(
                Version.current().toString(),
                sandbox.paths().root().toString(),
                sandbox.profileInfo(),
                c.uid(), c.gid(), c.resources(), c.defaultAgent(), c.language(),
                c.subnetPrefix().orElse(null),
                c.repos().stream().map(RepoInfo::of).toList());
    }

    public DoctorReport doctor() {
        return doctor(false);
    }

    /** With {@code deep}, an egress self-test runs from a throwaway container (needs the built image and a running proxy). */
    public DoctorReport doctor(boolean deep) {
        List<Check> checks = new ArrayList<>(HostChecks.run(sandbox));
        boolean podmanPresent = checks.stream().anyMatch(c -> c.id().equals("podman") && c.level() != Check.Level.FAIL);
        if (podmanPresent) {
            checks.addAll(PodmanChecks.run(sandbox));
            if (deep) checks.addAll(no.syntakserror.lyngvi.core.doctor.DeepChecks.run(sandbox));
        }
        return DoctorReport.of(checks);
    }

    /** {@code rebuild-config}: regenerate every generated file (spec §4.5). */
    /**
     * Brings the sandbox's bundled files (images, fragments, proxy, templates, agents, gateway runtime and plugins)
     * up to the running version, records the version in {@code VERSION} and re-renders. User-owned files
     * (profile.json, sandbox.config, .env, allowlists, secrets, manifest, state) are untouched.
     */
    public UpgradeReport upgrade() {
        Path root = sandbox.paths().root();
        String from = readVersion(root);
        List<String> stale = Templates.stale(root);
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            Templates.writeAll(root, true);
            try {
                Files.writeString(root.resolve("VERSION"), Version.current() + "\n");
            } catch (IOException e) {
                throw new SandboxException(ErrorCode.INTERNAL, "Cannot write VERSION: " + e.getMessage(), e);
            }
        }
        // Starter skills this sandbox has not been offered before: a sandbox made by an older version gets the
        // ones that ship now, and a skill the user deleted stays deleted (spec §3.6).
        new no.syntakserror.lyngvi.core.skills.SkillStore(sandbox.paths()).installStarters();
        RenderReport render = rebuildConfig();
        return new UpgradeReport(from, Version.current().toString(), stale, render);
    }

    /** The version recorded in the sandbox, or null before the first upgrade. */
    public static String readVersion(Path root) {
        try {
            return Files.isRegularFile(root.resolve("VERSION")) ? Files.readString(root.resolve("VERSION")).strip() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public RenderReport rebuildConfig() {
        return new Renderer(sandbox).render();
    }
}
