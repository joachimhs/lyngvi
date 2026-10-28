package no.syntakserror.lyngvi.core.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.contract.SkillInfo;

/**
 * Carries the sandbox's switched-on skills into an agent's configuration directory.
 *
 * <p>The agent reads skills from a directory inside its own configuration — {@code skillsDir} in
 * {@code agent.json}, relative to {@code configDir} (spec §6.1) — and that configuration directory is
 * {@code state/agents/<id>/}, bind-mounted read-write into every session container of the sandbox. So a skill
 * that is copied here is in place in the containers that are already running, and the agent picks it up the
 * next time it is started: once per message, which is why a change takes effect on the next message rather
 * than only in the next conversation.
 *
 * <p>Lyngvi owns the names it has put there and nothing else. The names are kept in {@code .lyngvi-skills}
 * beside them, so switching a skill off removes exactly that folder and leaves anything the user installed in
 * the agent's own way untouched.
 */
public final class SkillSync {

    /** The names Lyngvi has copied into an agent's skills directory, one per line. */
    static final String MANIFEST = ".lyngvi-skills";

    private final Paths paths;

    public SkillSync(Paths paths) {
        this.paths = paths;
    }

    /** What one agent's directory looked like after a sync; {@code supported} is false for an agent without skills. */
    public record Result(String agent, boolean supported, List<String> copied, List<String> removed) {
        static Result unsupported(String agent) {
            return new Result(agent, false, List.of(), List.of());
        }
    }

    /**
     * Brings {@code state/agents/<agent>/<skillsDir>/} in line with the sandbox's switched-on skills. Does
     * nothing for an agent whose {@code agent.json} declares no {@code skillsDir} — not every agent takes
     * skills, and for those the sandbox's skills are simply not part of how they work.
     */
    public Result sync(String agent, List<SkillInfo> enabled) {
        AgentDescriptor descriptor = AgentDescriptor.find(paths.agents(), agent).orElse(null);
        if (descriptor == null || descriptor.skillsDir() == null || descriptor.skillsDir().isBlank()) {
            return Result.unsupported(agent);
        }
        Path target = skillsDirOf(descriptor);
        Set<String> managed = readManifest(target);
        List<String> copied = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot create " + target + ": " + e.getMessage(), e);
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (SkillInfo skill : enabled) wanted.add(skill.name());
        for (String name : managed) {
            if (wanted.contains(name)) continue;
            SkillStore.deleteTree(target.resolve(name));
            removed.add(name);
        }
        for (String name : wanted) {
            Path from = paths.skill(name);
            Path to = target.resolve(name);
            // A sync runs before every message, and almost every one of them has nothing to do: an unchanged
            // folder is left as it is rather than deleted and written again under a reading agent.
            if (sameTree(from, to)) continue;
            SkillStore.deleteTree(to);
            SkillStore.copyTree(from, to);
            copied.add(name);
        }
        writeManifest(target, wanted);
        return new Result(agent, true, copied, removed);
    }

    /**
     * Where the agent's skills live on the host. {@code skillsDir} is a path inside the container, under
     * {@code configDir}; the host side of that mount is {@code state/agents/<id>/}, so the part below
     * {@code configDir} is what is resolved there. A {@code skillsDir} that tries to leave it is a broken
     * descriptor, not a configuration choice.
     */
    private Path skillsDirOf(AgentDescriptor descriptor) {
        String relative = descriptor.skillsDir().strip();
        if (descriptor.configDir() != null && relative.startsWith(descriptor.configDir())) {
            relative = relative.substring(descriptor.configDir().length());
        }
        relative = relative.replaceFirst("^/+", "");
        Path host = paths.agentState(descriptor.id()).resolve(relative).normalize();
        if (!host.startsWith(paths.agentState(descriptor.id()))) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "agents/" + descriptor.id() + "/agent.json has a skillsDir outside the agent's configuration directory: "
                            + descriptor.skillsDir(), Map.of("agent", descriptor.id(), "skillsDir", descriptor.skillsDir()));
        }
        return host;
    }

    private static Set<String> readManifest(Path target) {
        Path file = target.resolve(MANIFEST);
        if (!Files.isRegularFile(file)) return Set.of();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static void writeManifest(Path target, Set<String> names) {
        String body = "# Skills Lyngvi keeps in step with the sandbox's skills/ folder; do not edit.\n"
                + String.join("\n", names) + (names.isEmpty() ? "" : "\n");
        SkillStore.writeFile(target.resolve(MANIFEST), body);
    }

    /** True when the two folders hold the same files with the same bytes, so there is nothing to copy. */
    private static boolean sameTree(Path from, Path to) {
        if (!Files.isDirectory(from) || !Files.isDirectory(to)) return false;
        try {
            List<String> left = relativeFiles(from);
            if (!left.equals(relativeFiles(to))) return false;
            for (String rel : left) {
                if (Files.size(from.resolve(rel)) != Files.size(to.resolve(rel))
                        || !java.util.Arrays.equals(Files.readAllBytes(from.resolve(rel)), Files.readAllBytes(to.resolve(rel)))) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false; // unreadable on either side: copy it again rather than guess
        }
    }

    private static List<String> relativeFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

}
