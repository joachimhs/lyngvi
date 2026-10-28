package no.syntakserror.lyngvi.core.api;

import java.util.List;
import java.util.Optional;
import no.syntakserror.lyngvi.core.config.History;

/**
 * Options for adding repositories to a conversation that is already under way (spec §5.2.1).
 *
 * @param repos   names of registered repositories to add, in the order they should appear
 * @param history how much history the new working copies get; empty leaves it to the repository's own
 *                setting and then the profile, exactly as {@code session new} does
 * @param depth   the depth for {@code shallow}; empty means the profile's
 */
public record AddRepoOptions(List<String> repos, Optional<History> history, Optional<Integer> depth) {

    public AddRepoOptions {
        repos = List.copyOf(repos);
    }

    /** The repositories with the history the repository, and then the profile, asks for. */
    public static AddRepoOptions of(List<String> repos) {
        return new AddRepoOptions(repos, Optional.empty(), Optional.empty());
    }
}
