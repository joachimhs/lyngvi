package no.syntakserror.lyngvi.core.contract;

import java.util.List;

/** Result of {@code init}. */
public record InitReport(String sandboxDir, ProfileInfo profile, List<RepoInfo> repos, int filesWritten) {}
