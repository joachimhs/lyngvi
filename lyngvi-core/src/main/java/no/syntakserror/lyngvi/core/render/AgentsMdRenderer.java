package no.syntakserror.lyngvi.core.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.gateway.api.Instance;
import no.syntakserror.lyngvi.gateway.api.Manifest;

/** {@code AGENTS.md} = template + gateway section + toolchain notes + profile pack additions + agent additions. */
final class AgentsMdRenderer {

    private AgentsMdRenderer() {}

    static String render(RenderInputs in) {
        String template = InPlace.read(in.paths().agentsMdTemplate());
        String out = template.replace("{{GATEWAY_SECTION}}", gatewaySection(in.manifest()));
        out = replaceSection(out, "{{TOOLCHAIN_SECTION}}", toolchainSection(in));
        out = replaceSection(out, "{{PROFILE_SECTION}}", concat(in.config().profilePack().resolve("AGENTS.md.d")));
        StringBuilder agentSections = new StringBuilder();
        for (AgentDescriptor a : in.agents()) {
            agentSections.append(concat(a.dir().resolve("AGENTS.d")));
        }
        out = replaceSection(out, "{{AGENT_SECTION}}", agentSections.toString());
        return out.stripTrailing() + "\n";
    }

    /** The note of every toolchain fragment in the image that ships one ({@code image/fragments/<name>.md}), in image order. */
    static String toolchainSection(RenderInputs in) {
        StringBuilder sb = new StringBuilder();
        for (String t : ContainerfileRenderer.imageToolchains(in)) {
            Path note = in.paths().imageFragmentNote(t);
            if (Files.isRegularFile(note)) sb.append(InPlace.read(note).stripTrailing()).append("\n\n");
        }
        return sb.toString();
    }

    private static String replaceSection(String text, String token, String content) {
        if (content.isBlank()) {
            return text.replace("\n" + token + "\n", "").replace(token, "");
        }
        return text.replace(token, content.stripTrailing());
    }

    static String gatewaySection(Manifest manifest) {
        if (manifest.instances().isEmpty()) {
            return "No internal systems are configured in this sandbox.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("The gateway gives read-only access to the systems below. Use the direct URL or the\n")
          .append("environment variable; the gateway adds authentication. Only allowlisted resources\n")
          .append("are available: a 403 means the human must allow the item first.\n\n");
        for (Instance i : manifest.instances()) {
            sb.append("- **").append(i.label()).append("** (`").append(i.id()).append("`): http://")
              .append(i.hostname()).append(" or `$").append(i.urlEnvName()).append("`\n");
        }
        return sb.toString();
    }

    /** All {@code *.md} files in a directory, sorted by name, joined with blank lines; empty when absent. */
    static String concat(Path dir) {
        if (!Files.isDirectory(dir)) return "";
        try (Stream<Path> files = Files.list(dir)) {
            StringBuilder sb = new StringBuilder();
            files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().forEach(p -> {
                sb.append(InPlace.read(p).stripTrailing()).append("\n\n");
            });
            return sb.toString();
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot list " + dir + ": " + e.getMessage(), e);
        }
    }
}
