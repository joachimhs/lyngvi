package no.syntakserror.lyngvi.core.contract;

import no.syntakserror.lyngvi.core.config.RepoEntry;

/** A registered repository. {@code history} is null when the profile default applies. */
public record RepoInfo(String path, String name, String history) {

    public static RepoInfo of(RepoEntry e) {
        return new RepoInfo(e.path().toString(), e.name(), e.history().map(h -> h.json()).orElse(null));
    }
}
