package no.syntakserror.lyngvi.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotEnvTest {

    @Test
    void createsTheFileFromTheExampleWhenMissing(@TempDir Path dir) throws Exception {
        Path example = Files.writeString(dir.resolve(".env.example"), "# build args\nNODE_VERSION=22\nJAVA_VERSION=21\n");
        Path env = dir.resolve(".env");

        List<String> added = DotEnv.ensure(env, example);

        assertThat(added).containsExactly("NODE_VERSION", "JAVA_VERSION");
        assertThat(Files.readString(env)).isEqualTo(Files.readString(example));
    }

    @Test
    void keepsExistingValuesAndAppendsMissingKeysUnderAMarker(@TempDir Path dir) throws Exception {
        Path example = Files.writeString(dir.resolve(".env.example"), "NODE_VERSION=22\nJAVA_VERSION=21\nPROXY_MEM_LIMIT=512m\n");
        Path env = Files.writeString(dir.resolve(".env"), "NODE_VERSION=20\n");

        List<String> added = DotEnv.ensure(env, example);

        assertThat(added).containsExactly("JAVA_VERSION", "PROXY_MEM_LIMIT");
        assertThat(Files.readString(env)).isEqualTo(
                "NODE_VERSION=20\n\n# -- added by lyngvi --\nJAVA_VERSION=21\nPROXY_MEM_LIMIT=512m\n");
    }

    @Test
    void isIdempotent(@TempDir Path dir) throws Exception {
        Path example = Files.writeString(dir.resolve(".env.example"), "A=1\nB=2\n");
        Path env = dir.resolve(".env");
        DotEnv.ensure(env, example);

        List<String> added = DotEnv.ensure(env, example);

        assertThat(added).isEmpty();
        assertThat(Files.readString(env)).isEqualTo("A=1\nB=2\n");
    }

    @Test
    void readsValuesIgnoringCommentsAndExportPrefix(@TempDir Path dir) throws Exception {
        Path env = Files.writeString(dir.resolve(".env"), "# c\nexport A=1\nB = two \n\nC=\n");

        assertThat(DotEnv.read(env)).containsExactly(
                java.util.Map.entry("A", "1"), java.util.Map.entry("B", "two"), java.util.Map.entry("C", ""));
    }
}
