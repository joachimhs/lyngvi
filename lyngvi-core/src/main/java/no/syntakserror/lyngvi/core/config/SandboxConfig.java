package no.syntakserror.lyngvi.core.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Local sandbox state ({@code sandbox.config}, spec §3.2). Written only by the core.
 *
 * @param schemaVersion format version; {@link #CURRENT_SCHEMA_VERSION}
 * @param uid          host user id the containers run as
 * @param gid          host group id
 * @param profilePack  directory of the active profile pack
 * @param subnetPrefix first three octets of the internal network, once allocated
 * @param resources    default limits for new sessions
 * @param defaultAgent adapter id used when a session does not name one
 * @param language     UI language code
 * @param repos        registered repositories in order
 */
public record SandboxConfig(
        int schemaVersion,
        int uid,
        int gid,
        Path profilePack,
        Optional<String> subnetPrefix,
        ResourceLimits resources,
        String defaultAgent,
        String language,
        List<RepoEntry> repos) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public SandboxConfig {
        repos = List.copyOf(repos);
    }

    public SandboxConfig withRepos(List<RepoEntry> newRepos) {
        return new SandboxConfig(schemaVersion, uid, gid, profilePack, subnetPrefix, resources, defaultAgent, language, newRepos);
    }

    public SandboxConfig withResources(ResourceLimits newResources) {
        return new SandboxConfig(schemaVersion, uid, gid, profilePack, subnetPrefix, newResources, defaultAgent, language, repos);
    }

    public SandboxConfig withSubnetPrefix(String prefix) {
        return new SandboxConfig(schemaVersion, uid, gid, profilePack, Optional.ofNullable(prefix), resources, defaultAgent, language, repos);
    }

    public Optional<RepoEntry> repo(String name) {
        return repos.stream().filter(r -> r.name().equals(name)).findFirst();
    }
}
