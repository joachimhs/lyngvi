package no.syntakserror.lyngvi.core.config;

import java.nio.file.Path;

/** Every known location inside a sandbox directory, resolved in one place (spec §3). */
public record Paths(Path root) {

    public static Paths of(Path root) {
        return new Paths(root.toAbsolutePath().normalize());
    }

    public Path profileJson() { return root.resolve("profile.json"); }
    public Path sandboxConfig() { return root.resolve("sandbox.config"); }
    public Path dotEnv() { return root.resolve(".env"); }
    public Path versionStamp() { return root.resolve("sandbox.version"); }

    public Path templates() { return root.resolve("templates"); }
    public Path dotEnvExample() { return templates().resolve(".env.example"); }
    public Path agentsMdTemplate() { return templates().resolve("AGENTS.md.template"); }
    public Path profiles() { return root.resolve("profiles"); }
    public Path defaultProfilePack() { return profiles().resolve("default"); }
    public Path agents() { return root.resolve("agents"); }
    public Path image() { return root.resolve("image"); }
    public Path proxy() { return root.resolve("proxy"); }
    public Path gateway() { return root.resolve("gateway"); }
    public Path scripts() { return root.resolve("scripts"); }
    public Path contract() { return root.resolve("contract"); }
    public Path cli() { return root.resolve("cli"); }

    /**
     * The sandbox's skills: one folder per skill, and {@code skills.json} with the on/off flags. User-owned —
     * nothing here is generated — and sandbox-local, which is what keeps the skills of one sandbox out of
     * another (spec §3.6).
     */
    public Path skills() { return root.resolve("skills"); }
    public Path skillsFile() { return skills().resolve("skills.json"); }
    public Path skill(String name) { return skills().resolve(name); }
    public Path skillFile(String name) { return skill(name).resolve("SKILL.md"); }
    /** The starter skills {@code init} lays down in a new sandbox, copied once and never overwritten. */
    public Path skillTemplates() { return templates().resolve("skills"); }

    public Path state() { return root.resolve("state"); }
    public Path conversations() { return state().resolve("conversations"); }
    public Path conversation(String sessionId) { return conversations().resolve(sessionId + ".json"); }
    public Path agentsState() { return state().resolve("agents"); }
    /** The starter skills this sandbox has been offered, so a deleted one is not laid down again (spec §3.6). */
    public Path skillsOffered() { return state().resolve("skills-offered.txt"); }
    public Path agentState(String agentId) { return agentsState().resolve(agentId); }
    public Path locks() { return state().resolve("locks"); }

    /**
     * Scratch directories the host hands to a container: a working copy on its way into a session's
     * volume, a file on its way out. They are inside the sandbox rather than in the system temp area
     * because that is a directory Podman can mount — on macOS the machine shares the user's files, not
     * {@code /var/folders}. Each one is deleted as soon as the command that made it is done.
     */
    public Path temp() { return state().resolve("tmp"); }
    public Path sessions() { return root.resolve(".sessions"); }

    /**
     * The host-side part of one session: the rendered environment file, the attachments the human sent
     * in, and the patches a review wrote. The working copies and the agent's output are <em>not</em>
     * here — they live in the session's Podman volume, where the agent can write without writing to the
     * host (spec §5.1).
     */
    public Path sessionMeta(String sessionId) { return sessions().resolve(sessionId); }
    public Path sessionAttachments(String sessionId) { return sessionMeta(sessionId).resolve("attachments"); }
    public Path sessionPatches(String sessionId) { return sessionMeta(sessionId).resolve("patches"); }
    public Path sessionEnvFile(String sessionId) { return sessionMeta(sessionId).resolve("container.env"); }

    public Path agentsMd() { return root.resolve("AGENTS.md"); }
    public Path containerfile() { return root.resolve("Containerfile"); }
    public Path compose() { return root.resolve("docker-compose.yml"); }
    public Path composeTemplate() { return templates().resolve("docker-compose.template.yml"); }
    public Path composeGatewayTemplate() { return templates().resolve("docker-compose.gateway.template.yml"); }

    public Path imageBase() { return image().resolve("base.Containerfile"); }
    public Path imageFragment(String name) { return image().resolve("fragments").resolve(name + ".Containerfile"); }
    /** Optional: what the fragment gives the agent, in the agent's words; rendered into {@code AGENTS.md} when the toolchain is selected. */
    public Path imageFragmentNote(String name) { return image().resolve("fragments").resolve(name + ".md"); }
    public Path agentDir(String agentId) { return agents().resolve(agentId); }
    /** Generated: base + toolchains + the agent's fragment, one per enabled agent with an image fragment. */
    public Path agentContainerfile(String agentId) { return image().resolve("agents").resolve(agentId + ".Containerfile"); }

    public Path allowedDomains() { return proxy().resolve("allowed-domains.txt"); }
    public Path blockedDomains() { return proxy().resolve("blocked-domains.txt"); }
    public Path gatewayHosts() { return proxy().resolve("gateway-hosts.txt"); }
    public Path squidConf() { return proxy().resolve("squid.conf"); }
    public Path squidConfTemplate() { return proxy().resolve("squid.conf.template"); }

    public Path gatewayManifest() { return gateway().resolve("gateway.manifest.json"); }
    public Path gatewayContainerfile() { return gateway().resolve("Containerfile"); }
    public Path gatewayAllowlists() { return gateway().resolve("allowlists"); }
    public Path gatewaySecrets() { return gateway().resolve("secrets"); }
    public Path gatewayCerts() { return gateway().resolve("certs"); }
    public Path gatewayPlugins() { return gateway().resolve("plugins"); }
    public Path gatewayCert() { return gatewayCerts().resolve("gateway.crt"); }
    public Path gatewayKey() { return gatewayCerts().resolve("gateway.key"); }

    /** Compose project name: the directory name, lower-cased, with anything but [a-z0-9_-] replaced by '-'. */
    public String projectName() {
        String name = root.getFileName() == null ? "lyngvi" : root.getFileName().toString();
        String cleaned = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        return cleaned.isBlank() ? "lyngvi" : cleaned;
    }
}
