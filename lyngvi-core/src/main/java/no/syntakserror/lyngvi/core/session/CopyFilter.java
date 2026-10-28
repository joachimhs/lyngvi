package no.syntakserror.lyngvi.core.session;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.Profile;

/**
 * Decides what is left out of a working copy: the profile's {@code copy.excludes}
 * (matched against any path segment), the repository's {@code .lyngviignore}
 * (gitignore-like), and a per-file size limit. {@code .git} is always excluded.
 */
public final class CopyFilter {

    /** One gitignore-like rule. */
    private record Rule(PathMatcher matcher, boolean dirOnly, boolean negated, boolean anchored) {}

    private final List<PathMatcher> segmentExcludes;
    private final List<Rule> rules;
    private final long maxFileBytes;

    public CopyFilter(List<String> excludes, long maxFileBytes) {
        this(matchers(excludes), maxFileBytes, List.of());
    }

    private CopyFilter(List<PathMatcher> segmentExcludes, long maxFileBytes, List<Rule> rules) {
        this.segmentExcludes = List.copyOf(segmentExcludes);
        this.maxFileBytes = maxFileBytes;
        this.rules = List.copyOf(rules);
    }

    private static List<PathMatcher> matchers(List<String> excludes) {
        List<PathMatcher> m = new ArrayList<>();
        m.add(glob(".git"));
        for (String e : excludes) m.add(glob(e));
        return m;
    }

    /** The profile's filter; the repository's own {@code .lyngviignore} is added by {@link #withRepoIgnoreFile}. */
    public static CopyFilter forProfile(Profile.Copy copy) {
        return new CopyFilter(copy.excludes(), (long) copy.maxFileMiB() << 20);
    }

    /** This filter plus the rules of {@code <hostRepo>/.lyngviignore} when that file exists. */
    public CopyFilter withRepoIgnoreFile(Path hostRepo) {
        Path ignore = hostRepo.resolve(".lyngviignore");
        if (!Files.isRegularFile(ignore)) return this;
        try {
            return withIgnoreRules(Files.readAllLines(ignore));
        } catch (IOException e) {
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot read " + ignore, e);
        }
    }

    /** A copy of this filter with gitignore-like rules added (later rules win; {@code !} negates). */
    public CopyFilter withIgnoreRules(List<String> lines) {
        List<Rule> parsed = new ArrayList<>(rules);
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            boolean negated = line.startsWith("!");
            if (negated) line = line.substring(1);
            boolean dirOnly = line.endsWith("/");
            if (dirOnly) line = line.substring(0, line.length() - 1);
            boolean anchored = line.contains("/");
            if (line.startsWith("/")) line = line.substring(1);
            parsed.add(new Rule(glob(line), dirOnly, negated, anchored));
        }
        return new CopyFilter(segmentExcludes, maxFileBytes, parsed);
    }

    public long maxFileBytes() {
        return maxFileBytes;
    }

    /** True when {@code relative} (from the repository root) must not be copied. */
    public boolean excluded(Path relative, boolean isDir) {
        for (Path segment : relative) {
            for (PathMatcher m : segmentExcludes) {
                if (m.matches(segment)) return true;
            }
        }
        Boolean decision = null;
        for (Rule r : rules) {
            if (r.dirOnly() && !isDir) continue;
            boolean hit = r.anchored() ? r.matcher().matches(relative) : matchesAnySegment(r.matcher(), relative);
            if (hit) decision = !r.negated();
        }
        return decision != null && decision;
    }

    private static boolean matchesAnySegment(PathMatcher m, Path relative) {
        for (Path segment : relative) {
            if (m.matches(segment)) return true;
        }
        return false;
    }

    private static PathMatcher glob(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }
}
