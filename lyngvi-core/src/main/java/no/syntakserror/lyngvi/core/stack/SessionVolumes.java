package no.syntakserror.lyngvi.core.stack;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.runtime.Runner;

/**
 * Opens the volume of a session, with the settings every helper container of this sandbox shares
 * (spec §5.1).
 *
 * @param cwd     the sandbox directory the podman commands run from
 * @param temp    where scratch directories a helper container mounts are made ({@code Paths.temp()})
 * @param project the compose project name, which labels the volumes
 * @param image   the image helper containers run; the base image, which has git
 */
public record SessionVolumes(Runner runner, Path cwd, Path temp, String project, String image, int uid, int gid) {

    public SessionVolume of(String container, String sessionId) {
        return new SessionVolume(runner, cwd, temp, container, sessionId, project, image, uid, gid);
    }

    public SessionVolume of(String container) {
        return of(container, null);
    }

    /**
     * Creates a shared cache volume if it is not there. Podman would make one by itself at {@code run},
     * but then it would carry no labels — and an unlabelled volume is one neither {@code doctor} nor
     * {@code clean} can tell apart from someone else's.
     */
    public void ensureCache(String volume, String cacheId) {
        try {
            if (runner.run(List.of("podman", "volume", "exists", volume), cwd, SessionVolume.SHORT).success()) return;
            runner.run(List.of("podman", "volume", "create",
                    "--label", "lyngvi.project=" + project,
                    "--label", "lyngvi.cache=" + cacheId, volume), cwd, SessionVolume.SHORT)
                    .requireSuccess("podman volume create");
        } catch (SandboxException e) {
            // podman unavailable: the run that follows will report it
        }
    }

    /** Removes a volume this sandbox owns, by the name {@link #list} reported. */
    public void removeByName(String volume) {
        try {
            runner.run(List.of("podman", "volume", "rm", "-f", volume), cwd, SessionVolume.SHORT);
        } catch (SandboxException ignored) {
            // podman unavailable: nothing to remove
        }
    }

    /** Every session volume of this sandbox, as volume name to the session id it was created for. */
    public Map<String, String> list() {
        Map<String, String> out = new LinkedHashMap<>();
        Runner.Result r;
        try {
            r = runner.run(List.of("podman", "volume", "ls", "--filter", "label=lyngvi.project=" + project,
                    "--format", "{{.Name}}\t{{index .Labels \"lyngvi.session\"}}"), cwd, SessionVolume.SHORT);
        } catch (SandboxException e) {
            return out;
        }
        if (!r.success()) return out;
        for (String line : r.stdout().split("\n")) {
            String[] parts = line.strip().split("\t", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) out.put(parts[0], parts[1]);
        }
        return out;
    }
}
