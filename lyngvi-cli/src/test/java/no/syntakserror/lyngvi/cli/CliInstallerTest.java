package no.syntakserror.lyngvi.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliInstallerTest {

    @Test
    void copiesTheJarOnceAndSkipsWhenAnImageOrTheSameJarIsPresent(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("lyngvi-cli-1.jar");
        Files.writeString(jar, "jar-bytes");
        Path sb = Files.createDirectories(tmp.resolve("sb"));

        assertThat(CliInstaller.install(sb, Optional.of(jar))).contains(sb.resolve("cli/lyngvi-cli.jar"));
        assertThat(sb.resolve("cli/lyngvi-cli.jar")).hasContent("jar-bytes");
        assertThat(CliInstaller.install(sb, Optional.of(jar))).isEmpty();
        Files.writeString(jar, "jar-bytes-2");
        assertThat(CliInstaller.install(sb, Optional.of(jar))).isPresent();
        assertThat(sb.resolve("cli/lyngvi-cli.jar")).hasContent("jar-bytes-2");

        Files.createDirectories(sb.resolve("cli/bin"));
        Files.writeString(sb.resolve("cli/bin/lyngvi"), "#!/bin/sh\n");
        sb.resolve("cli/bin/lyngvi").toFile().setExecutable(true);
        assertThat(CliInstaller.install(sb, Optional.of(jar))).isEmpty();
        assertThat(CliInstaller.install(sb, Optional.empty())).isEmpty();
        // from a classes directory (as in this test) there is no jar to install
        assertThat(CliInstaller.ownJar()).isEmpty();
    }

    @Test
    void whenRunningFromAnImageTheSandboxGetsASymlinkToIt(@TempDir Path tmp) throws Exception {
        Path image = Files.createDirectories(tmp.resolve("runtime/bin")).getParent();
        Files.writeString(image.resolve("bin/lyngvi"), "#!/bin/sh\n");
        image.resolve("bin/lyngvi").toFile().setExecutable(true);
        Path sb = Files.createDirectories(tmp.resolve("sb"));
        assertThat(CliInstaller.ownImage(image)).contains(image);
        assertThat(CliInstaller.ownImage(tmp.resolve("nope"))).isEmpty();

        assertThat(CliInstaller.install(sb, Optional.empty(), Optional.of(image))).contains(sb.resolve("cli"));
        assertThat(Files.isSymbolicLink(sb.resolve("cli"))).isTrue();
        assertThat(Files.readSymbolicLink(sb.resolve("cli"))).isEqualTo(image);
        assertThat(CliInstaller.install(sb, Optional.empty(), Optional.of(image))).isEmpty();
        // a sandbox with its own (downloaded) runtime directory is left alone
        Path own = Files.createDirectories(tmp.resolve("sb2/cli/bin")).getParent().getParent();
        assertThat(CliInstaller.install(own, Optional.empty(), Optional.of(image))).isEmpty();
        assertThat(Files.isSymbolicLink(own.resolve("cli"))).isFalse();
    }
}
