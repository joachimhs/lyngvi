package no.syntakserror.lyngvi.core.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything that differs between macOS and Linux, in one place (spec §7.6).
 *
 * @param os   detected operating system
 * @param home the user's home directory
 * @param env  environment variables (injectable for tests)
 * @param uid  numeric user id of the current user
 * @param gid  numeric group id of the current user
 */
public record Platform(Os os, Path home, Map<String, String> env, int uid, int gid) {

    public enum Os { MACOS, LINUX }

    public static Platform current() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Os os = name.contains("mac") ? Os.MACOS : Os.LINUX;
        Path home = Path.of(System.getProperty("user.home"));
        return new Platform(os, home, System.getenv(), unixAttribute(home, "unix:uid"), unixAttribute(home, "unix:gid"));
    }

    /** {@code $XDG_STATE_HOME/lyngvi} or {@code ~/.local/state/lyngvi}. */
    public Path stateDir() {
        String xdg = env.get("XDG_STATE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".local").resolve("state");
        return base.resolve("lyngvi");
    }

    /** {@code $XDG_CACHE_HOME/lyngvi} or {@code ~/.cache/lyngvi}. */
    public Path cacheDir() {
        String xdg = env.get("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".cache");
        return base.resolve("lyngvi");
    }

    /** Directories to search for executables in addition to {@code PATH} (Finder-launched apps have a minimal PATH). */
    public List<Path> extraPathDirs() {
        return switch (os) {
            case MACOS -> List.of(
                    Path.of("/opt/homebrew/bin"), Path.of("/opt/homebrew/sbin"),
                    Path.of("/usr/local/bin"), Path.of("/usr/local/sbin"),
                    Path.of("/opt/podman/bin"), home.resolve(".docker/bin"), home.resolve(".rd/bin"),
                    Path.of("/usr/bin"), Path.of("/bin"), Path.of("/usr/sbin"), Path.of("/sbin"));
            case LINUX -> List.of(
                    home.resolve(".local/bin"), Path.of("/usr/local/bin"), Path.of("/usr/local/sbin"),
                    Path.of("/usr/bin"), Path.of("/bin"), Path.of("/usr/sbin"), Path.of("/sbin"));
        };
    }

    /** Expands a leading {@code ~} to the home directory. */
    public Path expand(String path) {
        if (path.equals("~")) return home;
        if (path.startsWith("~/")) return home.resolve(path.substring(2));
        return Path.of(path);
    }

    /** Reads the numeric owner id of a path (the home directory's owner is the current user). */
    private static int unixAttribute(Path home, String name) {
        try {
            return (Integer) Files.getAttribute(home, name);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot read " + name + " of " + home, e);
        }
    }
}
