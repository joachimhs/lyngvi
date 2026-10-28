package no.syntakserror.lyngvi.core.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplatesTest {

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void indexListsExactlyTheFilesPackagedIntoTheBundle() throws Exception {
        // The index is a manifest of what the jar carries, so the jar is what it is checked against — not the
        // repository the files came from. The POM decides what gets packaged, and reading the result back means
        // this test needs no opinion about anything that merely lies in the source directories, and no second
        // copy of the POM's rules to drift out of step with it.
        Path bundle = Path.of(Templates.class.getResource("/bundle/index.txt").toURI()).getParent();
        List<String> packaged;
        try (Stream<Path> files = Files.walk(bundle)) {
            packaged = files.filter(Files::isRegularFile)
                    .map(p -> bundle.relativize(p).toString().replace('\\', '/'))
                    .filter(rel -> !rel.equals("index.txt")) // the manifest itself
                    .sorted()
                    .toList();
        }

        assertThat(Templates.index()).containsExactlyElementsOf(packaged);
    }

    @Test
    void writeAllCreatesEveryIndexedFile(@TempDir Path dir) throws Exception {
        List<Path> written = Templates.writeAll(dir);

        assertThat(written).hasSize(Templates.index().size());
        for (String rel : Templates.index()) {
            assertThat(dir.resolve(rel)).isRegularFile();
        }
        assertThat(Files.readString(dir.resolve("profiles/default/profile.json")))
                .isEqualTo(Files.readString(REPO_ROOT.resolve("profiles/default/profile.json")));
        assertThat(Templates.allPresent(dir)).isTrue();
    }

    @Test
    void secondRunKeepsExistingFilesAndWritesNothing(@TempDir Path dir) throws Exception {
        Templates.writeAll(dir);
        Path env = dir.resolve("templates/.env.example");
        Files.writeString(env, "EDITED=1\n");

        List<Path> written = Templates.writeAll(dir);

        assertThat(written).isEmpty();
        assertThat(Files.readString(env)).isEqualTo("EDITED=1\n");
    }

    @Test
    void overwriteReplacesExistingFiles(@TempDir Path dir) throws Exception {
        Templates.writeAll(dir);
        Path env = dir.resolve("templates/.env.example");
        Files.writeString(env, "EDITED=1\n");

        List<Path> written = Templates.writeAll(dir, true);

        assertThat(written).hasSize(Templates.index().size());
        assertThat(Files.readString(env)).isEqualTo(Files.readString(REPO_ROOT.resolve("templates/.env.example")));
    }

    @Test
    void allPresentIsFalseWhenAFileIsMissing(@TempDir Path dir) throws Exception {
        Templates.writeAll(dir);
        Files.delete(dir.resolve("templates/AGENTS.md.template"));

        assertThat(Templates.allPresent(dir)).isFalse();
        assertThat(Templates.missing(dir)).containsExactly("templates/AGENTS.md.template");
    }

    @Test
    void staleListsBundledFilesWhoseContentDiffersFromTheJar(@TempDir Path dir) throws Exception {
        Templates.writeAll(dir);
        assertThat(Templates.stale(dir)).isEmpty();
        Files.writeString(dir.resolve("image/base.Containerfile"), "FROM old\n");
        Files.delete(dir.resolve("proxy/blocked-domains.txt"));
        assertThat(Templates.stale(dir)).containsExactly("image/base.Containerfile", "proxy/blocked-domains.txt");
        assertThat(Templates.writeAll(dir, true)).hasSize(Templates.index().size());
        assertThat(Templates.stale(dir)).isEmpty();
    }
}
