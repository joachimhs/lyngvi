package no.syntakserror.lyngvi.core.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.syntakserror.lyngvi.core.contract.ApplyResult;
import no.syntakserror.lyngvi.core.contract.FileChange;
import no.syntakserror.lyngvi.core.contract.ReviewResult;
import no.syntakserror.lyngvi.core.delivery.Baseline;
import no.syntakserror.lyngvi.core.delivery.HostGit;
import no.syntakserror.lyngvi.core.delivery.PatchBuilder;
import no.syntakserror.lyngvi.core.delivery.PatchClassifier;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.session.Conversation;
import no.syntakserror.lyngvi.core.session.ConversationStore;
import no.syntakserror.lyngvi.core.session.Locks;
import no.syntakserror.lyngvi.core.session.WorkGit;
import no.syntakserror.lyngvi.core.stack.SessionVolume;

/** {@code session diff|review|apply} (spec §5.6–5.8). */
public final class DeliveryService {

    private final Sandbox sandbox;
    private final ConversationStore store;
    private final SessionService sessions;

    DeliveryService(Sandbox sandbox) {
        this.sandbox = sandbox;
        this.store = new ConversationStore(sandbox.paths());
        this.sessions = sandbox.sessionService();
    }

    private Conversation load(String id) {
        return store.load(sessions.resolve(id));
    }

    /** Git in the working copy: in the session's volume, through a helper container (spec §5.1). */
    private WorkGit copy(Conversation c, String repo) {
        return sessions.work(c, repo);
    }

    private Path hostPath(String repo) {
        return sandbox.config().repo(repo).orElseThrow(() -> new SandboxException(ErrorCode.VALIDATION_FAILED,
                "Repository '" + repo + "' is no longer registered", Map.of("repo", repo))).path();
    }

    private long maxBytes() {
        return (long) sandbox.profile().copy().maxFileMiB() << 20;
    }

    /** The raw unified diff, optionally for one repository and one path. */
    public String diff(String id, Optional<String> from, Optional<String> repo, Optional<String> path) {
        return diff(id, from, repo, path, false);
    }

    /**
     * The raw unified diff, optionally for one repository and one path. With {@code wholeFiles} every changed file
     * is included in full with its changes marked as usual, so a viewer can show the file with the diff in it.
     */
    public String diff(String id, Optional<String> from, Optional<String> repo, Optional<String> path, boolean wholeFiles) {
        Conversation c = load(id);
        Baseline base = Baseline.resolve(c, from);
        StringBuilder sb = new StringBuilder();
        for (String r : c.repos()) {
            if (repo.isPresent() && !repo.get().equals(r)) continue;
            String patch = PatchBuilder.build(copy(c, r), base.commits().get(r), wholeFiles);
            if (path.isPresent()) {
                String block = PatchClassifier.split(patch).get(path.get());
                if (block != null) sb.append(block);
            } else {
                sb.append(patch);
            }
        }
        return sb.toString();
    }

    public ReviewResult review(String id, Optional<String> from) {
        Conversation c = load(id);
        return review(c, Baseline.resolve(c, from), true);
    }

    private ReviewResult review(Conversation c, Baseline base, boolean failWhenEmpty) {
        Map<String, ReviewResult.RepoReview> repos = new LinkedHashMap<>();
        Path patchDir = sandbox.paths().sessionPatches(c.id());
        for (String repo : c.repos()) {
            String patch = PatchBuilder.build(copy(c, repo), base.commits().get(repo));
            Path patchFile = patchDir.resolve(repo + ".patch");
            try {
                Files.createDirectories(patchDir);
                Files.writeString(patchFile, patch);
            } catch (IOException e) {
                throw new SandboxException(ErrorCode.INTERNAL, "Cannot write " + patchFile + ": " + e.getMessage(), e);
            }
            HostGit host = new HostGit(sandbox.runner(), hostPath(repo));
            String branch = host.branch();
            List<FileChange> files = new ArrayList<>();
            for (FileChange f : PatchClassifier.classify(patch, maxBytes())) {
                boolean conflict = !f.bucket().equals("reject") && !host.checkApply(patchFile, f.path());
                files.add(f.withConflict(conflict));
            }
            repos.put(repo, new ReviewResult.RepoReview(patchFile.toString(), base.commits().get(repo),
                    new ReviewResult.HostInfo(host.repo().toString(), branch, isProtected(host, branch), host.isDirty()), files));
        }
        ReviewResult result = new ReviewResult(base.toFrom(), repos);
        if (failWhenEmpty && result.totalFiles() == 0) {
            throw new SandboxException(ErrorCode.NO_CHANGES, "No changes " + base.describe(),
                    Map.of("from", base.kind(), "alternatives", alternatives(c, base)));
        }
        return result;
    }

    /** Other baselines that do have changes, so a front-end can offer them. */
    private List<Map<String, Object>> alternatives(Conversation c, Baseline current) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        candidates.add("start");
        if (!c.deliveries().isEmpty()) candidates.add("last");
        for (int i = 0; i < c.deliveries().size(); i++) candidates.add(String.valueOf(i));
        for (String cand : candidates) {
            Baseline b = Baseline.resolve(c, Optional.of(cand));
            if (b.kind().equals(current.kind()) && java.util.Objects.equals(b.index(), current.index())) continue;
            int changes = 0;
            for (String repo : c.repos()) {
                changes += PatchClassifier.split(PatchBuilder.build(copy(c, repo), b.commits().get(repo))).size();
            }
            if (changes > 0) out.add(Map.of("from", cand, "describe", b.describe(), "changes", changes));
        }
        return out;
    }

    /** Protected means listed in this sandbox's {@code git.protectedBranches}; the user decides per sandbox. */
    private boolean isProtected(HostGit host, String branch) {
        return sandbox.profile().git().protectedBranches().contains(branch);
    }

    /** Local branches of the session's host repositories, the current branch of each first, then the rest sorted. */
    public List<String> branches(String id) {
        Conversation c = load(id);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        java.util.TreeSet<String> rest = new java.util.TreeSet<>();
        for (String repo : c.repos()) {
            HostGit host = new HostGit(sandbox.runner(), hostPath(repo));
            String current = host.branch();
            if (current != null && !current.isBlank()) out.add(current);
            rest.addAll(host.localBranches());
        }
        out.addAll(rest);
        return List.copyOf(out);
    }

    public ApplyResult apply(String id, ApplyOptions options) {
        Conversation c = load(id);
        if (!options.mode().equals("commit") && !options.mode().equals("stage")) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "--mode must be commit or stage");
        }
        String message = options.message().map(String::strip).orElse("");
        if (options.mode().equals("commit") && message.isBlank()) {
            throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "--message is required with --mode commit");
        }
        if (sandbox.sessionService().isBusy(c.id())) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Session " + c.id() + " is busy: an agent run is in progress",
                    Map.of("id", c.id(), "code", "SESSION_BUSY"));
        }
        try (Locks.Held ignored = Locks.session(sandbox.paths(), c.id())) {
            Baseline base = Baseline.resolve(c, options.from());
            ReviewResult review = review(c, base, false);
            List<String> targetRepos = options.repos().isEmpty() ? c.repos() : options.repos();
            Map<String, ApplyResult.RepoOutcome> outcomes = new LinkedHashMap<>();
            List<String> notices = new ArrayList<>();
            Map<String, Conversation.DeliveryRepo> delivered = new LinkedHashMap<>();
            for (String repo : targetRepos) {
                ReviewResult.RepoReview rr = review.repos().get(repo);
                if (rr == null) {
                    throw new SandboxException(ErrorCode.VALIDATION_FAILED, "Repository '" + repo + "' is not part of this session", Map.of("repo", repo));
                }
                List<FileChange> selected = select(rr.files(), options.includes(), repo, targetRepos.size() > 1);
                // What is left behind is said out loud: the delivery is the moment it stops being visible anywhere.
                int left = rr.files().size() - selected.size();
                if (left > 0) {
                    notices.add(repo + ": " + left + " of " + rr.files().size()
                            + " changed file(s) were not delivered (held back, rejected or not selected)");
                }
                if (selected.isEmpty()) continue;
                HostGit host = new HostGit(sandbox.runner(), hostPath(repo));
                validateBranch(host, options.branch());
                ApplyResult.RepoOutcome outcome = applyToHost(host, c, repo, rr.fromCommit(), Path.of(rr.patch()), selected, options, message, notices);
                outcomes.put(repo, outcome);
                delivered.put(repo, new Conversation.DeliveryRepo(outcome.files(), outcome.copyCommit(), outcome.hostCommit()));
            }
            if (outcomes.isEmpty()) {
                throw new SandboxException(ErrorCode.NO_CHANGES, "Nothing selected to apply " + base.describe(),
                        Map.of("from", base.kind(), "alternatives", alternatives(c, base)));
            }
            long now = System.currentTimeMillis();
            List<Conversation.Delivery> deliveries = new ArrayList<>(c.deliveries());
            deliveries.add(new Conversation.Delivery(now, options.branch(), message, options.mode(), delivered));
            Conversation latest = store.load(c.id());
            store.save(new Conversation(latest.schemaVersion(), latest.id(), latest.title(), latest.createdAt(), now, now, latest.agent(),
                    latest.agentSessionId(), latest.model(), latest.repos(), latest.resources(), latest.container(), latest.base(),
                    deliveries, latest.messages(), latest.cacheMode()));
            return new ApplyResult(deliveries.size() - 1, outcomes, notices);
        }
    }

    /** The files to deliver: the {@code apply} bucket by default, or the explicit includes (held files allowed, rejected never). */
    private static List<FileChange> select(List<FileChange> files, List<String> includes, String repo, boolean multiRepo) {
        if (includes.isEmpty()) {
            return files.stream().filter(f -> f.bucket().equals("apply")).toList();
        }
        List<FileChange> out = new ArrayList<>();
        for (String include : includes) {
            String path = multiRepo && include.startsWith(repo + "/") ? include.substring(repo.length() + 1) : include;
            Optional<FileChange> match = files.stream().filter(f -> f.path().equals(path)).findFirst();
            if (match.isEmpty()) {
                if (multiRepo) continue;
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, "'" + include + "' is not in the patch for " + repo, Map.of("path", include));
            }
            if (match.get().bucket().equals("reject")) {
                throw new SandboxException(ErrorCode.VALIDATION_FAILED, "'" + include + "' was rejected (" + match.get().reason() + ") and cannot be applied",
                        Map.of("path", include, "code", "REJECTED_FILE", "reason", match.get().reason()));
            }
            out.add(match.get());
        }
        return out;
    }

    private void validateBranch(HostGit host, String branch) {
        if (!host.isValidBranchName(branch)) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "'" + branch + "' is not a valid branch name", Map.of("branch", branch));
        }
        if (isProtected(host, branch)) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "'" + branch + "' is a protected branch; deliveries go to a working branch",
                    Map.of("branch", branch, "code", "PROTECTED_BRANCH"));
        }
    }

    private ApplyResult.RepoOutcome applyToHost(HostGit host, Conversation c, String repo, String baseCommit, Path patch, List<FileChange> selected,
                                                ApplyOptions options, String message, List<String> notices) {
        List<String> includes = new ArrayList<>();
        for (FileChange f : selected) {
            includes.add(f.path());
            if (f.renamedFrom() != null) includes.add(f.renamedFrom());
        }
        String originalBranch = host.branch();
        boolean created = !host.branchExists(options.branch());
        // The patch is measured from the working copy's baseline, and the host may hold that baseline only in its
        // working tree: the previous delivery went out with --mode stage, or the user edited the files afterwards.
        // Stashing it away before applying — as this used to do — took the patch's pre-image with it and made every
        // delivery after the first one fail until the user committed on the host. So the dirty state is recorded in
        // a snapshot commit instead, which leaves the working tree exactly as it is, and only a branch switch that
        // git itself refuses falls back to stashing (spec §5.7 step 3).
        String snapshot = host.snapshot();
        List<String> absent = List.of();
        boolean stashed = false;
        boolean threeWay = false;
        List<String> conflicts = List.of();
        try {
            if (!options.branch().equals(originalBranch) && !host.trySwitchTo(options.branch()).success()) {
                stashed = host.stashPush();
                host.switchTo(options.branch());
            }
            // Which of the files the patch touches the branch does not have: those are the ones a rollback has to
            // remove by hand, since a reset only knows the files git knows.
            absent = includes.stream().filter(p -> !Files.exists(host.repo().resolve(p))).toList();
            if (!host.applyIndex(patch, includes, false).success()) {
                // The host moved on since the baseline. Give git the pre-image blobs from the working copy's baseline
                // commit (they need not exist in the host, e.g. after an uncommitted overlay) and merge three-way:
                // clean hunks land, real conflicts are left as markers for the user (spec §5.7).
                importBaselineBlobs(host, c, repo, baseCommit, includes);
                Runner.Result three = host.applyIndex(patch, includes, true);
                conflicts = host.unmergedFiles();
                threeWay = three.success() || !conflicts.isEmpty();
                if (!threeWay) {
                    // Both refused the file outright, which is what --index does when the index and the working tree
                    // differ for it. Undo whatever the merge left behind and apply to the working tree instead.
                    clean(host, absent);
                    if (!stashed) host.applySnapshot(snapshot);
                    if (!host.applyWorkTree(patch, includes).success()) throw applyFailed(three);
                    host.stage(includes);
                    notices.add(repo + ": applied to the working tree; unstaged edits of your own in those files are staged with the delivery");
                }
                if (!conflicts.isEmpty()) {
                    notices.add(repo + ": " + conflicts.size() + " file(s) applied with conflict markers; resolve and commit them yourself");
                }
            }
        } catch (SandboxException e) {
            clean(host, absent);
            if (!originalBranch.equals(host.branch())) host.switchTo(originalBranch);
            if (created) host.deleteBranch(options.branch());
            if (stashed) host.stashPop();
            else host.applySnapshot(snapshot);
            throw e;
        }
        String hostCommit = null;
        if (options.mode().equals("commit") && conflicts.isEmpty()) {
            hostCommit = host.commit(message, includes);
            if (hostCommit == null) notices.add(repo + ": the delivered files were already on " + options.branch() + "; nothing to commit");
        }
        boolean stashPopConflict = false;
        if (stashed) {
            HostGit.StashResult pop = host.stashPop();
            stashPopConflict = pop != HostGit.StashResult.CLEAN;
            if (pop == HostGit.StashResult.CONFLICT) {
                notices.add(repo + ": your stashed local changes did not apply cleanly; resolve the conflicts in the working tree");
            } else if (pop == HostGit.StashResult.KEPT) {
                notices.add(repo + ": your local changes are still stashed; run 'git stash pop' in " + host.repo() + " when the delivery is resolved");
            }
        }
        String copyCommit = recordDelivery(c, repo, includes, options.branch());
        return new ApplyResult.RepoOutcome(options.branch(), hostCommit, copyCommit, selected.size(), stashed, stashPopConflict, threeWay, conflicts);
    }

    /**
     * Records the delivered files in the working copy so the next review starts after them, and returns the
     * commit that made. Only those files: anything else the agent left staged stays out of the commit, and so
     * shows up in the next review. The paths go in as a pathspec file, one {@code :(literal)} line each, so no
     * name the agent chose is ever read as a pathspec pattern or as shell.
     */
    private String recordDelivery(Conversation c, String repo, List<String> includes, String branch) {
        StringBuilder paths = new StringBuilder();
        for (String i : includes) paths.append(HostGit.literal(i)).append('\n');
        Runner.Result r = copy(c, repo).sh("""
                set -e
                printf '%s' "$PATHS" > /tmp/lyngvi-paths
                $GIT add -A --pathspec-from-file=/tmp/lyngvi-paths
                $GIT commit --quiet --allow-empty -m "$MESSAGE" --pathspec-from-file=/tmp/lyngvi-paths
                exec $GIT rev-parse HEAD
                """, Map.of("PATHS", paths.toString(), "MESSAGE", "lyngvi: delivered to " + branch), timeout());
        return r.requireSuccess("git commit in the working copy").stdout().strip();
    }

    /**
     * Drops a half-applied patch: HEAD's content again, and away with the files the patch created that were not
     * there before — a reset leaves those behind, because git never knew them.
     */
    private void clean(HostGit host, List<String> absent) {
        host.reset();
        for (String path : absent) {
            try {
                Files.deleteIfExists(host.repo().resolve(path));
            } catch (IOException ignored) {
                // A directory, or a file we may not remove: the reset above already put git's own view right.
            }
        }
    }

    /**
     * Copies each selected file's baseline content from the working copy into the host's object database.
     * The blobs are written in the helper container into a directory of ours, one run for all of them, and
     * never pass through a captured String: a pre-image is bytes, not text.
     */
    private void importBaselineBlobs(HostGit host, Conversation c, String repo, String baseCommit, List<String> paths) {
        if (baseCommit == null || baseCommit.isBlank() || paths.isEmpty()) return;
        Path dir = null;
        try {
            dir = copy(c, repo).tempDir("baseline-");
            StringBuilder list = new StringBuilder();
            for (String path : paths) list.append(path).append('\n');
            copy(c, repo).shToHost(dir, """
                    n=0
                    printf '%s' "$PATHS" | while IFS= read -r p; do
                      n=$((n+1))
                      [ -n "$p" ] || continue
                      $GIT show "$BASE:$p" > "$OUT/blob-$n" 2>/dev/null || rm -f "$OUT/blob-$n"
                    done
                    exit 0
                    """, Map.of("PATHS", list.toString(), "BASE", baseCommit), timeout());
            try (java.util.stream.Stream<Path> blobs = Files.list(dir)) {
                for (Path blob : blobs.sorted().toList()) {
                    try {
                        host.importBlob(blob);
                    } catch (SandboxException ignored) {
                        // one unreadable pre-image does not stop the others
                    }
                }
            }
        } catch (IOException | SandboxException ignored) {
            // A file that did not exist at the baseline has no pre-image; git treats it as an addition.
        } finally {
            if (dir != null) SessionVolume.deleteTemp(dir);
        }
    }

    private static SandboxException applyFailed(Runner.Result r) {
        String first = r.stderr().lines().filter(l -> l.startsWith("error:")).filter(l -> !l.contains("3-way")).findFirst()
                .orElse(r.stderr().lines().findFirst().orElse("git apply failed"));
        return new SandboxException(ErrorCode.VALIDATION_FAILED,
                "The patch does not apply to the host repository (its files changed since the baseline): " + first
                        + ". Deliver with a later baseline, or resolve the difference on the host first.",
                Map.of("code", "APPLY_FAILED", "stderr", r.stderr(), "stdout", r.stdout()));
    }

    static Duration timeout() {
        return Duration.ofMinutes(5);
    }
}
