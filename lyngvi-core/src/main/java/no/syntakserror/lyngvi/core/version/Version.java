package no.syntakserror.lyngvi.core.version;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic version with an optional pre-release tag, e.g. {@code 2.0.0-M3}.
 *
 * <p>Milestone tags of the form {@code M<n>} are ordered numerically, so
 * {@code M2 < M10}. Any pre-release orders below the release with the same
 * numbers. The product version is read from the {@code VERSION} file that is
 * bundled as a classpath resource from the repository root.
 */
public record Version(int major, int minor, int patch, String preRelease) implements Comparable<Version> {

    private static final Pattern FORMAT =
            Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.]+))?");
    private static final Pattern MILESTONE = Pattern.compile("M(\\d+)");
    private static final String RESOURCE = "/VERSION";

    public Version {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version numbers must not be negative");
        }
        if (preRelease != null && preRelease.isEmpty()) {
            throw new IllegalArgumentException("Pre-release tag must not be empty");
        }
    }

    /** Parses {@code MAJOR.MINOR.PATCH[-PRERELEASE]}, ignoring surrounding whitespace. */
    public static Version parse(String text) {
        Objects.requireNonNull(text, "text");
        Matcher m = FORMAT.matcher(text.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a version: '" + text.strip() + "'");
        }
        return new Version(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                m.group(4));
    }

    /** The version of this build, read from the bundled {@code VERSION} resource. */
    public static Version current() {
        try (InputStream in = Version.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled resource " + RESOURCE + " is missing");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read bundled resource " + RESOURCE, e);
        }
    }

    public boolean isPreRelease() {
        return preRelease != null;
    }

    @Override
    public int compareTo(Version other) {
        int c = Integer.compare(major, other.major);
        if (c == 0) c = Integer.compare(minor, other.minor);
        if (c == 0) c = Integer.compare(patch, other.patch);
        if (c == 0) c = comparePreRelease(preRelease, other.preRelease);
        return c;
    }

    private static int comparePreRelease(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;   // a release is newer than any of its pre-releases
        if (b == null) return -1;
        Matcher ma = MILESTONE.matcher(a);
        Matcher mb = MILESTONE.matcher(b);
        if (ma.matches() && mb.matches()) {
            return Integer.compare(Integer.parseInt(ma.group(1)), Integer.parseInt(mb.group(1)));
        }
        return a.compareTo(b);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease == null ? "" : "-" + preRelease);
    }
}
