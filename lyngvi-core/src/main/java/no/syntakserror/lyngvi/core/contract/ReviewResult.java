package no.syntakserror.lyngvi.core.contract;

import java.util.List;
import java.util.Map;

/** Result of {@code session review}. */
public record ReviewResult(From from, Map<String, RepoReview> repos) {

    /** The baseline the patch is measured from. {@code index} is set for {@code delivery}. */
    public record From(String kind, Integer index, String describe) {}

    public record RepoReview(String patch, String fromCommit, HostInfo host, List<FileChange> files) {}

    public record HostInfo(String path, String branch, boolean isProtected, boolean dirty) {}

    public int totalFiles() {
        return repos.values().stream().mapToInt(r -> r.files().size()).sum();
    }
}
