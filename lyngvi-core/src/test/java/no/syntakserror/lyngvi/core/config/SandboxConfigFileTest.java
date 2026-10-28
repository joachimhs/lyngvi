package no.syntakserror.lyngvi.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxConfigFileTest {

    private static SandboxConfig sample() {
        return new SandboxConfig(
                2, 502, 20, Path.of("/packs/acme"), Optional.of("172.31.240"),
                new ResourceLimits("8g", "12g", "4.0", "2048"), "claude-code", "en",
                List.of(RepoEntry.parse("/Users/alice/src/api|api"),
                        RepoEntry.parse("/Users/alice/src/web"),
                        RepoEntry.parse("/Users/alice/src/legacy|billing|history=none")));
    }

    @Test
    void roundTripsThroughTheKeyValueFormat(@TempDir Path dir) {
        Path file = dir.resolve("sandbox.config");
        SandboxConfigFile.write(file, sample());

        SandboxConfig read = SandboxConfigFile.read(file);

        assertThat(read).isEqualTo(sample());
    }

    @Test
    void writesOneKeyPerLineWithRepoLinesLast(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sandbox.config");
        SandboxConfigFile.write(file, sample());

        List<String> lines = Files.readAllLines(file);

        assertThat(lines.getFirst()).startsWith("#");
        assertThat(lines).contains("SCHEMA_VERSION=2", "UID=502", "GID=20", "PROFILE=/packs/acme",
                "SUBNET_PREFIX=172.31.240", "MEM_LIMIT=8g", "MEMSWAP_LIMIT=12g", "CPUS=4.0",
                "PIDS_LIMIT=2048", "DEFAULT_AGENT=claude-code", "LANG_UI=en");
        // A name equal to the basename is redundant and is dropped in the canonical form.
        assertThat(lines.subList(lines.size() - 3, lines.size())).containsExactly(
                "REPO=/Users/alice/src/api",
                "REPO=/Users/alice/src/web",
                "REPO=/Users/alice/src/legacy|billing|history=none");
    }

    @Test
    void ignoresCommentsBlankLinesAndOmitsEmptySubnet(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sandbox.config");
        Files.writeString(file, """
                # comment
                SCHEMA_VERSION=2

                UID=1000
                GID=1000
                PROFILE=/p
                MEM_LIMIT=8g
                MEMSWAP_LIMIT=8g
                CPUS=2
                PIDS_LIMIT=512
                DEFAULT_AGENT=opencode
                LANG_UI=nb
                """);

        SandboxConfig c = SandboxConfigFile.read(file);

        assertThat(c.subnetPrefix()).isEmpty();
        assertThat(c.repos()).isEmpty();
        assertThat(c.language()).isEqualTo("nb");
    }

    @Test
    void missingRequiredKeyIsAValidationError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sandbox.config");
        Files.writeString(file, "SCHEMA_VERSION=2\nUID=1\n");

        assertThatThrownBy(() -> SandboxConfigFile.read(file))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("GID");
    }

    @Test
    void olderSchemaVersionRequiresMigration(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sandbox.config");
        Files.writeString(file, "UID=1\nGID=1\nREPO=/x\n");

        assertThatThrownBy(() -> SandboxConfigFile.read(file))
                .isInstanceOf(SandboxException.class)
                .satisfies(e -> assertThat(((SandboxException) e).code()).isEqualTo(ErrorCode.NOT_INITIALIZED))
                .hasMessageContaining("migrate");
    }

    @Test
    void duplicateRepoNamesAreRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sandbox.config");
        Files.writeString(file, """
                SCHEMA_VERSION=2
                UID=1
                GID=1
                PROFILE=/p
                MEM_LIMIT=8g
                MEMSWAP_LIMIT=8g
                CPUS=2
                PIDS_LIMIT=512
                DEFAULT_AGENT=opencode
                LANG_UI=en
                REPO=/a/web
                REPO=/b/web
                """);

        assertThatThrownBy(() -> SandboxConfigFile.read(file)).hasMessageContaining("web");
    }

    @Test
    void writeReplacesAtomicallyAndLeavesNoTempFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sandbox.config");
        SandboxConfigFile.write(file, sample());
        SandboxConfigFile.write(file, sample().withRepos(List.of()));

        assertThat(Files.list(dir).map(p -> p.getFileName().toString())).containsExactly("sandbox.config");
        assertThat(SandboxConfigFile.read(file).repos()).isEmpty();
    }
}
