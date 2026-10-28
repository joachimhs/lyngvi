package no.syntakserror.lyngvi.core.render;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;

/** {@code Containerfile} = base + toolchain fragments in profile order (spec §7.1–7.2). */
public final class ContainerfileRenderer {

    private static final List<String> NEEDS_JAVA = List.of("kotlin", "maven", "gradle");

    private ContainerfileRenderer() {}

    static String render(RenderInputs in) {
        return render(in, in.profile().toolchains(), null);
    }

    /** The agent's image: base + the profile's toolchains + the agent's required fragments + the agent fragment. */
    static String renderAgent(RenderInputs in, AgentDescriptor agent) {
        List<String> toolchains = new ArrayList<>(in.profile().toolchains());
        for (String r : agent.requires()) {
            if (!toolchains.contains(r)) toolchains.add(r);
        }
        return render(in, toolchains, agent);
    }

    /** Image label naming the toolchain fragments the image was built with. */
    public static final String TOOLCHAINS_LABEL = "no.syntakserror.lyngvi.toolchains";

    private static String render(RenderInputs in, List<String> toolchains, AgentDescriptor agent) {
        boolean hasJava = toolchains.stream().anyMatch(t -> t.startsWith("java"));
        for (String t : toolchains) {
            if (NEEDS_JAVA.contains(t) && !hasJava) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                        "Toolchain '" + t + "' requires a java toolchain (java17, java21 or java25) in profile.toolchains",
                        Map.of("toolchain", t));
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(substitute(InPlace.read(in.paths().imageBase()), in));
        for (String t : toolchains) {
            Path fragment = in.paths().imageFragment(t);
            if (!Files.isRegularFile(fragment)) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                        "Unknown toolchain '" + t + "' in profile.toolchains (no " + fragment + ")", Map.of("toolchain", t));
            }
            sb.append('\n').append(InPlace.read(fragment));
        }
        // Recorded in the image so the host can tell whether the built image matches the profile.
        sb.append("\nLABEL " + TOOLCHAINS_LABEL + "=\"").append(String.join(",", toolchains)).append("\"\n");
        if (agent != null) {
            sb.append('\n').append(InPlace.read(agent.dir().resolve("image.Containerfile")));
        }
        return sb.toString();
    }

    static String substitute(String template, RenderInputs in) {
        return template
                .replace("{{UID}}", String.valueOf(in.config().uid()))
                .replace("{{GID}}", String.valueOf(in.config().gid()))
                .replace("{{GIT_NAME}}", in.profile().git().committer().name())
                .replace("{{GIT_EMAIL}}", in.profile().git().committer().email())
                .replace("{{VERSION}}", in.version())
                .replace("{{PREFIX}}", in.prefix())
                .replace("{{PROJECT}}", in.project());
    }

    static List<String> fragmentsOf(RenderInputs in) {
        return new ArrayList<>(in.profile().toolchains());
    }

    /** Every toolchain some session image carries: the profile's, then what the enabled agents require. */
    static List<String> imageToolchains(RenderInputs in) {
        List<String> toolchains = new ArrayList<>(in.profile().toolchains());
        for (AgentDescriptor a : in.agents()) {
            for (String r : a.requires()) {
                if (!toolchains.contains(r)) toolchains.add(r);
            }
        }
        return toolchains;
    }
}
