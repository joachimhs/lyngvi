package no.syntakserror.lyngvi.core.contract;

import java.util.List;
import no.syntakserror.lyngvi.core.config.ResourceLimits;

/** Result of {@code status}. {@code subnetPrefix} is null until the network is allocated. */
public record StatusReport(
        String version,
        String sandboxDir,
        ProfileInfo profile,
        int uid,
        int gid,
        ResourceLimits resources,
        String defaultAgent,
        String language,
        String subnetPrefix,
        List<RepoInfo> repos) {}
