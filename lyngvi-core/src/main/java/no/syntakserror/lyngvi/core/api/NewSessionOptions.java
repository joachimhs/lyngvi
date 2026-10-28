package no.syntakserror.lyngvi.core.api;

import java.util.List;
import java.util.Optional;
import no.syntakserror.lyngvi.core.config.History;
import no.syntakserror.lyngvi.core.config.ResourceLimits;

/**
 * Arguments of {@code session new}.
 *
 * @param title     empty means a generated title
 * @param repos     registered repository names; empty means all registered repositories
 * @param agent     empty means the sandbox's default agent
 * @param model     empty means the agent's default
 * @param history   overrides the profile's and repositories' history mode for this session
 * @param depth     overrides the profile's depth
 * @param resources partial limits merged over the sandbox defaults
 * @param noRepos   create a session without working copies (plain question-and-answer)
 * @param cache     overrides the profile's shared-cache mode for this session ({@code off}, {@code shared},
 *                  {@code readonly}); empty follows the profile. {@code off} is the one to reach for when a
 *                  session is going to work on code you have no reason to trust.
 */
public record NewSessionOptions(Optional<String> title, Optional<List<String>> repos, Optional<String> agent, Optional<String> model,
                                Optional<History> history, Optional<Integer> depth, Optional<ResourceLimits> resources, boolean noRepos,
                                Optional<String> cache) {

    /** The options without a cache override: the profile decides. */
    public NewSessionOptions(Optional<String> title, Optional<List<String>> repos, Optional<String> agent, Optional<String> model,
                             Optional<History> history, Optional<Integer> depth, Optional<ResourceLimits> resources, boolean noRepos) {
        this(title, repos, agent, model, history, depth, resources, noRepos, Optional.empty());
    }
}
