package no.syntakserror.lyngvi.core.doctor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.Paths;
import no.syntakserror.lyngvi.core.config.ProfileLoader;
import no.syntakserror.lyngvi.core.config.RepoEntry;
import no.syntakserror.lyngvi.core.runtime.Runner;
import no.syntakserror.lyngvi.core.template.Templates;
import no.syntakserror.lyngvi.core.version.Version;

/**
 * Host-side preflight checks: tools, identity, profile, files and repositories.
 * Podman-specific checks (machine, network, images) arrive with the runtime increment.
 */
public final class HostChecks {

    static final Version MIN_GIT = Version.parse("2.30.0");
    static final int MIN_PODMAN_MAJOR = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern VERSION_NUMBER = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private HostChecks() {}

    public static List<Check> run(Sandbox sandbox) {
        Runner runner = sandbox.runner();
        Paths paths = sandbox.paths();
        List<Check> checks = new ArrayList<>();
        checks.add(podman(runner, paths.root()));
        checks.add(compose(runner, paths.root()));
        checks.add(git(runner, paths.root()));
        checks.add(openssl(runner, paths.root()));
        checks.add(ids(sandbox));
        checks.add(profile(paths));
        checks.add(env(paths));
        checks.add(state(paths));
        checks.add(templates(paths));
        bundle(paths).ifPresent(checks::add);
        cli(paths).ifPresent(checks::add);
        for (RepoEntry repo : sandbox.config().repos()) {
            checks.add(repo(runner, paths.root(), repo));
        }
        return checks;
    }

    private static Check podman(Runner runner, Path cwd) {
        Runner.Result r = tryRun(runner, cwd, "podman", "--version");
        if (r == null || !r.success()) {
            return Check.fail("podman", "podman is not installed or not on PATH",
                    "Install Podman 5 or newer: https://podman.io/docs/installation");
        }
        String version = firstVersion(r.stdout());
        int major = version == null ? 0 : Integer.parseInt(version.split("\\.")[0]);
        if (major < MIN_PODMAN_MAJOR) {
            return Check.warn("podman", "podman " + version + " found; " + MIN_PODMAN_MAJOR + ".x or newer is recommended",
                    "Upgrade Podman");
        }
        Runner.Result info = tryRun(runner, cwd, "podman", "info", "--format", "{{.Version.Version}}");
        if (info != null && !info.success()) {
            String err = info.stderr() == null ? "" : info.stderr().strip();
            java.util.regex.Matcher kh = java.util.regex.Pattern.compile("knownhosts: (\\S+?):(\\d+): ([^\\n]+)").matcher(err);
            if (kh.find()) {
                return Check.fail("podman", "podman " + version + " cannot reach its machine over SSH: " + kh.group(1).replace(System.getProperty("user.home"), "~")
                                + " line " + kh.group(2) + " is malformed (" + kh.group(3) + ")",
                        "Remove line " + kh.group(2) + " from ~/.ssh/known_hosts (usually two entries merged into one line) and retry");
            }
            String first = err.lines().filter(l -> !l.isBlank()).reduce((a, b) -> b).orElse("podman info failed");
            return Check.fail("podman", "podman " + version + " cannot connect: " + first,
                    "Check 'podman machine list' and 'podman system connection list'; start the machine with 'podman machine start'");
        }
        return Check.ok("podman", "podman " + version);
    }

    private static Check compose(Runner runner, Path cwd) {
        Runner.Result modern = tryRun(runner, cwd, "podman", "compose", "version");
        if (modern != null && modern.success()) {
            return Check.ok("compose", "podman compose available (" + firstLine(modern.stdout()) + ")");
        }
        Runner.Result legacy = tryRun(runner, cwd, "podman-compose", "--version");
        if (legacy != null && legacy.success()) {
            return Check.ok("compose", "podman-compose available (" + firstLine(legacy.stdout()) + ")");
        }
        return Check.fail("compose", "Neither 'podman compose' nor 'podman-compose' works",
                "Install podman-compose (pip install podman-compose) or a Podman with the compose provider");
    }

    private static Check git(Runner runner, Path cwd) {
        Runner.Result r = tryRun(runner, cwd, "git", "--version");
        if (r == null || !r.success()) {
            return Check.fail("git", "git is not installed or not on PATH", "Install git " + MIN_GIT + " or newer");
        }
        String version = firstVersion(r.stdout());
        if (version == null || Version.parse(version).compareTo(MIN_GIT) < 0) {
            return Check.fail("git", "git " + version + " found; " + MIN_GIT + " or newer is required", "Upgrade git");
        }
        return Check.ok("git", "git " + version);
    }

    private static Check openssl(Runner runner, Path cwd) {
        Runner.Result r = tryRun(runner, cwd, "openssl", "version");
        if (r == null || !r.success()) {
            return Check.fail("openssl", "openssl is not installed or not on PATH",
                    "Install OpenSSL (needed to generate the gateway certificate)");
        }
        return Check.ok("openssl", firstLine(r.stdout()));
    }

    private static Check ids(Sandbox sandbox) {
        int cfgUid = sandbox.config().uid();
        int cfgGid = sandbox.config().gid();
        int uid = sandbox.platform().uid();
        int gid = sandbox.platform().gid();
        if (cfgUid != uid || cfgGid != gid) {
            return Check.fail("ids", "sandbox.config has UID/GID " + cfgUid + "/" + cfgGid
                    + " but the current user is " + uid + "/" + gid,
                    "Run 'setup.sh init --yes' as the user who will use the sandbox");
        }
        return Check.ok("ids", "UID/GID " + uid + "/" + gid + " match the current user");
    }

    private static Check profile(Paths paths) {
        try {
            var p = ProfileLoader.load(paths.profileJson());
            return Check.ok("profile", "profile '" + p.name() + "' is valid");
        } catch (SandboxException e) {
            return Check.fail("profile", e.getMessage(), "Fix profile.json or run 'setup.sh profile set <pack>'");
        }
    }

    private static Check env(Paths paths) {
        if (!Files.isRegularFile(paths.dotEnv())) {
            return Check.warn("env", ".env is missing", "Run 'setup.sh rebuild-config' to recreate it from templates/.env.example");
        }
        return Check.ok("env", ".env present");
    }

    private static Check state(Paths paths) {
        for (Path dir : List.of(paths.state(), paths.conversations(), paths.agentsState(), paths.locks(), paths.sessions())) {
            if (!Files.isDirectory(dir)) {
                return Check.fail("state", dir + " is missing", "Run 'setup.sh rebuild-config'");
            }
            if (!Files.isWritable(dir)) {
                return Check.fail("state", dir + " is not writable", "Fix the directory permissions");
            }
        }
        return Check.ok("state", "state directories present and writable");
    }

    /** Bundled files that differ from this version's copies: the sandbox was initialised by an older build. */
    private static java.util.Optional<Check> bundle(Paths paths) {
        List<String> stale = Templates.stale(paths.root());
        if (stale.isEmpty()) return java.util.Optional.empty();
        String recorded = no.syntakserror.lyngvi.core.api.SandboxService.readVersion(paths.root());
        return java.util.Optional.of(Check.warn("bundle", stale.size() + " bundled file(s) differ from version " + Version.current()
                        + (recorded == null ? "" : " (sandbox recorded " + recorded + ")") + ": " + String.join(", ", stale.subList(0, Math.min(3, stale.size()))) + (stale.size() > 3 ? ", ..." : ""),
                "Run 'setup.sh upgrade' to refresh them, then 'setup.sh build'"));
    }

    /** The sandbox's own runtime ({@code cli/}) must be the running version, or 'upgrade' has not been run. */
    static java.util.Optional<Check> cli(Paths paths) {
        Path cli = paths.cli();
        if (!Files.exists(cli)) return java.util.Optional.empty();
        String found = null;
        try {
            if (Files.isRegularFile(cli.resolve("VERSION"))) {
                found = Files.readString(cli.resolve("VERSION")).strip();
            } else if (Files.isRegularFile(cli.resolve("lyngvi-cli.jar"))) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(cli.resolve("lyngvi-cli.jar").toFile())) {
                    java.util.zip.ZipEntry e = zip.getEntry("VERSION");
                    if (e != null) found = new String(zip.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
                }
            }
        } catch (java.io.IOException e) {
            return java.util.Optional.of(Check.warn("cli", "cannot read the version of cli/: " + e.getMessage(), "Run 'setup.sh upgrade'"));
        }
        if (found == null) return java.util.Optional.of(Check.warn("cli", "cli/ exists but carries no version", "Run 'setup.sh upgrade' to replace it"));
        if (!found.equals(Version.current().toString())) {
            return java.util.Optional.of(Check.fail("cli", "cli/ is version " + found + " but this is " + Version.current(),
                    "Run 'setup.sh upgrade' from the newer installation, or 'setup.sh --download' to fetch the matching CLI image"));
        }
        return java.util.Optional.of(Check.ok("cli", "cli/ is version " + found));
    }

    private static Check templates(Paths paths) {
        List<String> missing = Templates.missing(paths.root());
        if (!missing.isEmpty()) {
            return Check.warn("templates", missing.size() + " bundled file(s) missing: " + String.join(", ", missing),
                    "Run 'setup.sh upgrade' to restore them");
        }
        return Check.ok("templates", "all bundled files present");
    }

    private static Check repo(Runner runner, Path cwd, RepoEntry repo) {
        String id = "repo:" + repo.name();
        if (!Files.isDirectory(repo.path())) {
            return Check.warn(id, repo.path() + " does not exist", "Remove it with 'setup.sh repo remove " + repo.name() + "' or restore the directory");
        }
        Runner.Result r = tryRun(runner, cwd, "git", "-C", repo.path().toString(), "rev-parse", "--is-inside-work-tree");
        if (r == null || !r.success()) {
            return Check.warn(id, repo.path() + " is not a git work tree", "Sessions need a git repository to measure changes against");
        }
        return Check.ok(id, repo.path() + " -> /work/" + repo.name());
    }

    private static Runner.Result tryRun(Runner runner, Path cwd, String... command) {
        try {
            return runner.run(List.of(command), cwd, TIMEOUT);
        } catch (SandboxException e) {
            return null;
        }
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse("").strip();
    }

    private static String firstVersion(String text) {
        Matcher m = VERSION_NUMBER.matcher(text);
        if (!m.find()) return null;
        return m.group(1) + "." + m.group(2) + "." + (m.group(3) == null ? "0" : m.group(3));
    }
}
