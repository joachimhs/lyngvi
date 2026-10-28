package no.syntakserror.lyngvi.core.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Arguments of {@link Sandbox#init}.
 *
 * @param profilePack directory containing {@code profile.json}; empty means the bundled default profile
 * @param repos       repositories to register, in order
 * @param agent       default agent id; empty means the profile's default
 * @param force       overwrite an existing {@code sandbox.config}
 */
public record InitOptions(Optional<Path> profilePack, List<Path> repos, Optional<String> agent, boolean force) {

    public InitOptions {
        repos = List.copyOf(repos);
    }
}
