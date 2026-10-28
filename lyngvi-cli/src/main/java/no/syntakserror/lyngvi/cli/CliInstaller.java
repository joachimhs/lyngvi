package no.syntakserror.lyngvi.cli;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Places the running CLI jar in {@code <sandbox>/cli/lyngvi-cli.jar} so the sandbox's own {@code setup.sh}
 * finds a runtime (spec §4.6). Nothing is copied when the CLI runs from a classes directory (tests) or when the
 * sandbox already holds a jlink image.
 */
final class CliInstaller {

    private CliInstaller() {}

    static Optional<Path> ownJar() {
        try {
            Path src = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(src) && src.toString().endsWith(".jar") ? Optional.of(src) : Optional.empty();
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The runtime image this CLI runs from (a jlink image with {@code bin/lyngvi}), if any. */
    static Optional<Path> ownImage() {
        return ownImage(Path.of(System.getProperty("java.home", "")));
    }

    static Optional<Path> ownImage(Path javaHome) {
        return Files.isExecutable(javaHome.resolve("bin").resolve("lyngvi")) ? Optional.of(javaHome) : Optional.empty();
    }

    static Optional<Path> install(Path sandboxDir, Optional<Path> jar) {
        return install(sandboxDir, jar, ownImage());
    }

    /**
     * Points the sandbox at this CLI: a symlink {@code cli -> <runtime image>} when running from an image (the GUI
     * package or a downloaded CLI image), else a copy of the jar. An image the sandbox owns itself is left alone.
     */
    static Optional<Path> install(Path sandboxDir, Optional<Path> jar, Optional<Path> image) {
        Path cliDir = sandboxDir.resolve("cli");
        if (image.isPresent()) {
            try {
                if (Files.isSymbolicLink(cliDir)) {
                    if (Files.readSymbolicLink(cliDir).equals(image.get())) return Optional.empty();
                    Files.delete(cliDir);
                } else if (Files.exists(cliDir)) {
                    return Optional.empty(); // the sandbox has its own runtime directory; 'upgrade' replaces it deliberately
                }
                Files.createDirectories(sandboxDir);
                Files.createSymbolicLink(cliDir, image.get());
                return Optional.of(cliDir);
            } catch (IOException | UnsupportedOperationException e) {
                return Optional.empty();
            }
        }
        if (jar.isEmpty()) return Optional.empty();
        if (Files.isExecutable(cliDir.resolve("bin").resolve("lyngvi"))) return Optional.empty();
        Path target = cliDir.resolve("lyngvi-cli.jar");
        try {
            Files.createDirectories(cliDir);
            if (Files.isRegularFile(target) && Files.size(target) == Files.size(jar.get()) && Files.mismatch(target, jar.get()) == -1L) {
                return Optional.empty();
            }
            Files.copy(jar.get(), target, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(target);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
